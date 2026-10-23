package com.margelo.nitro.nitromediakit

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Private staging, byte/disk budgets and input leases last until atomic output commit. */
internal class MediaJob(private val context: Context, val control: PipelineControl = PipelineControl()) {
    private val directory = File(context.cacheDir, "nitro-media-kit/jobs/${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val outputs = mutableListOf<File>()
    private val committed = mutableListOf<File>()
    private val leases = mutableSetOf<String>()
    private val resolved = mutableMapOf<String, File>()
    init {
        activeDirectories.add(directory.canonicalPath)
        control.resourceCheck = { synchronized(outputs) {
            control.check()
            if (directory.usableSpace < control.minFreeDiskBytes) throw PipelineFailure("DISK_FULL", "Insufficient free disk space.")
            if (outputs.any { it.length() > control.maxOutputBytes }) throw PipelineFailure("OUTPUT_TOO_LARGE", "Output byte budget exceeded.")
        } }
        reapAbandoned(context)
    }

    fun output(extension: String): File = File(directory, "${UUID.randomUUID()}.$extension").also { synchronized(outputs) { outputs.add(it) } }

    suspend fun resolve(source: String): File = withContext(Dispatchers.IO) {
        resolved[source]?.let { return@withContext it }
        control.check()
        require(source.isNotBlank()) { "Input URI must not be empty." }
        val uri = Uri.parse(source)
        val file = when (uri.scheme?.lowercase()) {
            "http", "https" -> ioPermits.withPermit {
                control.emit(ExportStage.DOWNLOADING)
                val connection = URL(source).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000; connection.readTimeout = 30_000
                val hook = control.onCancel { connection.disconnect() }
                try {
                    require(connection.responseCode in 200..299) { "Media download failed: HTTP ${connection.responseCode}." }
                    require(connection.contentLengthLong <= control.maxInputBytes) { "Input byte budget exceeded." }
                    connection.inputStream.use { copyInput(it) }
                } finally { hook.close(); connection.disconnect() }
            }
            "content" -> ioPermits.withPermit {
                val signal = android.os.CancellationSignal()
                val hook = control.onCancel { signal.cancel() }
                try {
                    val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                        ?: throw PipelineFailure("INPUT_UNREADABLE", "Cannot read content URI.")
                    descriptor.use { it.createInputStream().use { input -> copyInput(input) } }
                } finally { hook.close() }
            }
            null, "file" -> {
                require(uri.scheme == "file" || source.startsWith("/")) { "Input path must be absolute." }
                val local = File(if (uri.scheme == null) source else requireNotNull(uri.path)).canonicalFile
                // Deletion and lease acquisition use the same lock.
                synchronized(inputLeases) {
                    require(local.isAbsolute && local.isFile && local.canRead()) { "Input must be a readable absolute path or file URI." }
                    if (leases.add(local.path)) inputLeases[local.path] = (inputLeases[local.path] ?: 0) + 1
                }
                local
            }
            else -> throw IllegalArgumentException("Unsupported input URI scheme.")
        }
        control.check()
        require(file.length() in 1..control.maxInputBytes) { "Input is empty or exceeds the input byte budget." }
        resolved[source] = file
        file
    }

    private fun copyInput(input: InputStream): File {
        val destination = File(directory, "input-${UUID.randomUUID()}.bin")
        val hook = control.onCancel { input.close() }
        try {
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    control.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > control.maxInputBytes) throw PipelineFailure("INPUT_TOO_LARGE", "Input byte budget exceeded.")
                    if (directory.usableSpace - count < control.minFreeDiskBytes) throw PipelineFailure("DISK_FULL", "Insufficient free disk space.")
                    output.write(buffer, 0, count)
                }
            }
            return destination
        } finally { hook.close() }
    }

    fun commit(result: MediaInfoResult): MediaInfoResult {
        if (!result.ok) return result
        control.check()
        val paths = listOfNotNull(result.outputUri) + (result.segments?.toList() ?: emptyList())
        val mapping = paths.associateWith { path ->
            val source = File(path)
            check(source in outputs && source.length() in 1..control.maxOutputBytes) { "Invalid staged output." }
            val target = File(outputDirectory(context), source.name)
            check(source.renameTo(target)) { "Cannot commit generated output." }
            committed.add(target)
            target.path
        }
        control.seal()
        return result.copy(outputUri = result.outputUri?.let { mapping.getValue(it) }, segments = result.segments?.map { mapping.getValue(it) }?.toTypedArray())
    }

    fun finish(success: Boolean) {
        control.resourceCheck = null
        if (!success) committed.forEach { it.delete() }
        directory.deleteRecursively()
        activeDirectories.remove(directory.canonicalPath)
        synchronized(inputLeases) { leases.forEach { path ->
            val remaining = (inputLeases[path] ?: 1) - 1
            if (remaining == 0) inputLeases.remove(path) else inputLeases[path] = remaining
        } }
        control.close()
    }

    companion object {
        private val ioPermits = Semaphore(2)
        private val inputLeases = mutableMapOf<String, Int>()
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()
        private fun reapAbandoned(context: Context) {
            val cutoff = System.currentTimeMillis() - 86_400_000L
            File(context.cacheDir, "nitro-media-kit/jobs").listFiles()?.filter {
                it.isDirectory && it.lastModified() < cutoff && !activeDirectories.contains(it.canonicalPath)
            }?.forEach { it.deleteRecursively() }
        }
        fun outputDirectory(context: Context): File = File(context.cacheDir, "nitro-media-kit/output").apply { check(isDirectory || mkdirs()) }
        fun deleteOutput(context: Context, source: String): Boolean = synchronized(inputLeases) {
            val uri = Uri.parse(source)
            require(uri.scheme == null || uri.scheme == "file") { "Expected a generated output file URI." }
            val file = File(if (uri.scheme == null) source else requireNotNull(uri.path)).canonicalFile
            require(file.parentFile == outputDirectory(context).canonicalFile && file.extension in listOf("mp4", "jpg", "png")) { "Only generated outputs can be deleted." }
            require(runCatching { UUID.fromString(file.nameWithoutExtension) }.isSuccess) { "Invalid generated filename." }
            check(!inputLeases.containsKey(file.path)) { "Output is in use by an active job." }
            if (!file.exists()) false else { check(file.isFile && file.delete()); true }
        }
    }
}
