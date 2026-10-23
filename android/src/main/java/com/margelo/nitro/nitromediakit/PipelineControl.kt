package com.margelo.nitro.nitromediakit

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class PipelineFailure(val code: String, message: String) : Exception(message)

/** A deadline is armed before queueing; cancellation hooks also interrupt blocking I/O. */
internal class PipelineControl(
    val id: String = UUID.randomUUID().toString(),
    limits: ProcessingLimits? = null,
    private val progress: ((ExportProgress) -> Unit)? = null
) : Closeable {
    val started = SystemClock.elapsedRealtime()
    val timeoutMs = limit(limits?.timeoutMs, 1_800_000.0, 1.0, 86_400_000.0, "timeoutMs").toLong()
    val maxInputBytes = limit(limits?.maxInputBytes, 2_147_483_648.0, 1.0, 68_719_476_736.0, "maxInputBytes").toLong()
    val maxOutputBytes = limit(limits?.maxOutputBytes, 4_294_967_296.0, 1.0, 68_719_476_736.0, "maxOutputBytes").toLong()
    val minFreeDiskBytes = limit(limits?.minFreeDiskBytes, 67_108_864.0, 0.0, 68_719_476_736.0, "minFreeDiskBytes").toLong()
    @Volatile private var failure: PipelineFailure? = null
    @Volatile private var terminal = false
    private val hooks = ConcurrentHashMap<String, () -> Unit>()
    private var lastStage: ExportStage? = null
    private var lastProgress = 0L
    @Volatile var peakMemoryBytes: Double? = residentBytes()
        private set
    private var lastMemorySample = started
    @Volatile var resourceCheck: (() -> Unit)? = null
    private val timer = scheduler.scheduleAtFixedRate({
        if (SystemClock.elapsedRealtime() - lastMemorySample >= 1000) {
            residentBytes()?.let { peakMemoryBytes = maxOf(peakMemoryBytes ?: 0.0, it) }
            lastMemorySample = SystemClock.elapsedRealtime()
        }
        if (failure == null) try { resourceCheck?.invoke() } catch (e: Exception) {
            cancel((e as? PipelineFailure)?.code ?: "RESOURCE_LIMIT", e.message ?: "Resource budget exceeded.")
        }
        if (SystemClock.elapsedRealtime() - started >= timeoutMs) cancel("TIMEOUT", "Media job deadline exceeded.")
    }, 100, 100, TimeUnit.MILLISECONDS)

    fun check() { if (terminal) return; failure?.let { throw it }; if (SystemClock.elapsedRealtime() - started >= timeoutMs) {
        cancel("TIMEOUT", "Media job deadline exceeded."); throw requireNotNull(failure)
    } }

    @Synchronized fun cancel(code: String = "CANCELLED", message: String = "Media job cancelled."): Boolean {
        if (failure != null || terminal) return false
        failure = PipelineFailure(code, message)
        hooks.values.toList().forEach { runCatching { it() } }
        return true
    }

    fun onCancel(action: () -> Unit): Closeable {
        val key = UUID.randomUUID().toString()
        hooks[key] = action
        if (failure != null) runCatching(action)
        return Closeable { hooks.remove(key) }
    }

    @Synchronized fun emit(stage: ExportStage, fraction: Double? = null) {
        val now = SystemClock.elapsedRealtime()
        if (terminal && stage != ExportStage.COMPLETED) return
        if (stage == lastStage && now - lastProgress < 200) return
        lastStage = stage; lastProgress = now
        // Nitro dispatches callbacks asynchronously to JS; observer failures never break exports.
        runCatching { progress?.invoke(ExportProgress(id, stage, fraction?.coerceIn(0.0, 1.0))) }
    }

    @Synchronized fun seal() { check(); terminal = true }

    override fun close() { timer.cancel(false); hooks.clear() }

    companion object {
        private fun residentBytes(): Double? = runCatching {
            File("/proc/self/statm").readText().trim().split(Regex("\\s+"))[1].toDouble() * Os.sysconf(OsConstants._SC_PAGESIZE)
        }.getOrNull()
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "MediaKit.deadlines").apply { isDaemon = true } }
        val jobs = ConcurrentHashMap<String, PipelineControl>()
        @Synchronized fun register(control: PipelineControl) {
            if (jobs[control.id] === control) return
            require(!jobs.containsKey(control.id)) { "Job ID is already active." }
            if (jobs.size >= 64) throw PipelineFailure("QUEUE_FULL", "Media job queue is full.")
            jobs[control.id] = control
        }
        fun limit(value: Double?, default: Double, min: Double, max: Double, name: String): Double {
            val resolved = value ?: default
            require(resolved.isFinite() && resolved in min..max) { "$name must be finite and in [$min, $max]." }
            return resolved
        }
    }
}
