package com.margelo.nitro.nitromediakit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Effect
import androidx.media3.transformer.ProgressHolder
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.util.concurrent.MoreExecutors
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** No codecs, bitmaps, Surfaces or per-call buffers survive an operation. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
@DoNotStrip
class NitroMediaKit constructor(private val applicationContext: Context) : HybridNitroMediaKitSpec() {
    constructor() : this(requireNotNull(NitroModules.applicationContext))

    private fun execute(
        operation: OperationType, input: String?, type: MediaType = MediaType.VIDEO,
        control: PipelineControl = PipelineControl(),
        block: suspend (MediaJob) -> MediaInfoResult
    ): Promise<MediaInfoResult> = Promise.async(ioScope) {
        var job: MediaJob? = null
        var success = false
        val owner = currentCoroutineContext().job
        val cancellation = control.onCancel { owner.cancel(CancellationException("Media job interrupted.")) }
        try {
            PipelineControl.register(control)
            control.check()
            job = MediaJob(applicationContext, control)
            val result = block(job)
            control.emit(ExportStage.VALIDATING)
            val committed = job.commit(result)
            success = committed.ok
            if (success) control.emit(ExportStage.COMPLETED, 1.0)
            committed
        } catch (error: Exception) {
            val reason = runCatching { control.check() }.exceptionOrNull() as? Exception ?: error
            makeErrorResult(operation, type, input, error = reason)
        } finally {
            cancellation.close()
            job?.finish(success) ?: control.close()
            PipelineControl.jobs.remove(control.id, control)
        }
    }

    override fun exportVideo(video: String, options: ExportVideoOptions, jobId: String, onProgress: ((ExportProgress) -> Unit)?): Promise<MediaInfoResult> {
        require(jobId.isNotBlank() && jobId.length <= 128) { "Invalid job ID." }
        val control = PipelineControl(jobId, options.limits, onProgress)
        try { PipelineControl.register(control) } catch (e: Exception) { control.close(); throw e }
        control.emit(ExportStage.QUEUED)
        return execute(OperationType.EXPORTVIDEO, video, control = control) { job ->
            exportResolved(job, job.resolve(video), video, options, OperationType.EXPORTVIDEO)
        }
    }

    override fun cancelJob(jobId: String): Promise<Boolean> = Promise.async(ioScope) { PipelineControl.jobs[jobId]?.cancel() ?: false }

    override fun getMediaInfo(inputUri: String): Promise<MediaInfoResult> =
        execute(OperationType.GETMEDIAINFO, inputUri) { job ->
            val file = job.resolve(inputUri)
            val (type, media) = inspect(file)
            makeResult(true, OperationType.GETMEDIAINFO, type, inputUri, media = media)
        }

    private fun inspect(file: File): Pair<MediaType, MediaInfoMedia> {
        val image = detectImageInfo(file.path)
        if (image != null) return MediaType.IMAGE to requireNotNull(buildMediaInfo(
            durationMs = null, width = image.displayWidth.toDouble(), height = image.displayHeight.toDouble(),
            fps = null, format = image.extension, sizeBytes = file.length().toDouble(), audioTracks = 0.0, videoTracks = 0.0
        ))
        val info = detectVideoInfo(file.path)
        return MediaType.VIDEO to requireNotNull(buildMediaInfo(
            durationMs = info.durationMs, width = info.displayWidth.toDouble(), height = info.displayHeight.toDouble(),
            fps = info.fps.takeIf { it > 0 }, format = file.extension, sizeBytes = file.length().toDouble(),
            audioTracks = info.audioTracks.toDouble(), videoTracks = 1.0, codec = info.mime, hdr = info.hdr, bitDepth = info.bitDepth.toDouble().takeIf { it > 0 },
            bitrateKbps = info.bitrateKbps, audioChannels = info.audioChannels.toDouble(), audioSampleRate = info.audioSampleRate.toDouble()
        ))
    }

    private fun outputResult(operation: OperationType, input: String?, file: File, warnings: Array<MediaInfoWarning>? = null): MediaInfoResult {
        val (type, media) = inspect(file)
        return makeResult(true, operation, type, input, file.path, media = media, warnings = warnings)
    }

    override fun compressMedia(inputUri: String, options: CompressMediaOptions?): Promise<MediaInfoResult> =
        execute(OperationType.COMPRESSMEDIA, inputUri) { job ->
            val file = job.resolve(inputUri)
            val resolved = resolveCompressOptions(options)
            val image = detectImageInfo(file.path)
            if (image != null) {
                imagePermits.withPermit { compressImage(inputUri, file.path, image, resolved, job) }
            } else {
                exportResolved(job, file, inputUri, ExportVideoOptions(null, null, null,
                    options?.preset ?: CompressionPreset.AUTO, options?.video, null, options?.preserveMetadata, null), OperationType.COMPRESSMEDIA)
            }
        }

    override fun convertImageToVideo(image: String, duration: Double): Promise<MediaInfoResult> =
        execute(OperationType.CONVERTIMAGETOVIDEO, image) { job ->
            require(duration.isFinite() && duration > 0 && duration <= 86_400) { "Duration must be in (0, 86400] seconds." }
            val input = job.resolve(image)
            val info = detectImageInfo(input.path) ?: throw UnsupportedMediaException("Input must be an image.")
            val (width, height) = computeScaledDimensions(info.displayWidth, info.displayHeight, 1920)
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(input)).setMimeType("image/${info.extension ?: "jpeg"}").setImageDurationMs(kotlin.math.ceil(kotlin.math.ceil(duration * 30) * 1000 / 30).toLong()).build())
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width.coerceAtLeast(2) / 2 * 2, height.coerceAtLeast(2) / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            val output = job.output("mp4")
            transform(listOf(item), output, control = job.control)
            outputResult(OperationType.CONVERTIMAGETOVIDEO, image, output)
        }

    override fun mergeVideos(videos: Array<String>): Promise<MediaInfoResult> =
        execute(OperationType.MERGEVIDEOS, videos.firstOrNull()) { job ->
            require(videos.isNotEmpty()) { "At least one video is required." }
            val files = videos.map { job.resolve(it) }
            val infos = files.map { detectVideoInfo(it.path) }
            val first = infos.first()
            require(infos.none { it.bitDepth > 8 && !it.hdr }) { "Merging high-bit-depth SDR cannot guarantee precision on this pipeline; convert inputs with an explicit SDR policy first." }
            val audioSource = infos.firstOrNull { it.audioTracks > 0 }
            require(infos.filter { it.audioTracks > 0 }.all { it.audioChannels == audioSource?.audioChannels }) { "Merge audio layouts differ; normalize inputs with an explicit stereo policy first." }
            val (width, height) = computeScaledDimensions(first.displayWidth, first.displayHeight, maxOf(first.displayWidth, first.displayHeight))
            val presentation = Presentation.createForWidthAndHeight(width.coerceAtLeast(2) / 2 * 2, height.coerceAtLeast(2) / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT)
            val items = files.map { file ->
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
                    .setEffects(Effects(emptyList(), listOf(presentation))).build()
            }
            val output = job.output("mp4")
            val hdr = infos.any { it.hdr }
            val mergedSource = VideoSource(first.displayWidth, first.displayHeight, infos.maxOf { it.fps }, infos.sumOf { it.durationMs },
                if (hdr) "video/hevc" else first.mime, hdr, infos.mapNotNull { it.bitrateKbps }.maxOrNull(),
                infos.maxOf { it.audioTracks }, infos.maxOf { it.audioChannels }, first.audioSampleRate)
            val plan = ExportPlanner.plan(mergedSource, ExportVideoOptions(null, null, null, CompressionPreset.PRESERVE, null, null, false, null), forceEncode = true)
            val exported = transform(items, output, videoKbps = plan.bitrateKbps, includeAudio = audioSource != null, control = job.control, plan = plan)
            outputResult(OperationType.MERGEVIDEOS, videos.first(), output).also {
                require(!hdr || it.media?.hdr == true) { "Encoder could not preserve merged HDR output." }
                require(audioSource == null || it.media?.audioChannels == audioSource.audioChannels.toDouble()) { "Encoder changed the merged audio channel layout." }
            }.copy(diagnostics = ExportDiagnostics((SystemClock.elapsedRealtime() - job.control.started).toDouble(), output.length().toDouble(), job.control.peakMemoryBytes,
                VideoProcessingPath.ENCODED, if (audioSource == null) AudioProcessingPath.ABSENT else if (exported.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED) AudioProcessingPath.COPIED else AudioProcessingPath.ENCODED,
                exported.videoMimeType, plan.estimatedSizeBytes, exported.videoFrameCount.toDouble().takeIf { it > 0 }))
        }

    override fun splitVideo(video: String, segments: Array<VideoSegment>): Promise<MediaInfoResult> =
        execute(OperationType.SPLITVIDEO, video) { job ->
            require(segments.isNotEmpty()) { "At least one segment is required." }
            val file = job.resolve(video)
            val info = detectVideoInfo(file.path)
            segments.forEach { validateSegment(it, info.durationMs) }
            val outputs = segments.map { segment ->
                requireNotNull(exportResolved(job, file, video, ExportVideoOptions(segment, null, null, CompressionPreset.PRESERVE, null, null, false, null), OperationType.TRIMVIDEO, info).outputUri)
            }
            makeResult(true, OperationType.SPLITVIDEO, MediaType.VIDEO, video, segments = outputs.toTypedArray())
        }

    override fun trimVideo(video: String, segment: VideoSegment): Promise<MediaInfoResult> =
        execute(OperationType.TRIMVIDEO, video) { job ->
            val file = job.resolve(video)
            exportResolved(job, file, video, ExportVideoOptions(segment, null, null, CompressionPreset.PRESERVE, null, null, false, null), OperationType.TRIMVIDEO)
        }

    private fun validateSegment(segment: VideoSegment, durationMs: Double) {
        require(segment.startMs.isFinite() && segment.endMs.isFinite() && segment.startMs >= 0 && segment.endMs > segment.startMs && segment.endMs <= durationMs) {
            "Segments must satisfy 0 <= startMs < endMs <= video duration."
        }
    }

    override fun removeAudio(video: String): Promise<MediaInfoResult> =
        execute(OperationType.REMOVEAUDIO, video) { job ->
            exportResolved(job, job.resolve(video), video, ExportVideoOptions(null, null, null, CompressionPreset.PRESERVE, null, AudioPolicy.MUTE, false, null), OperationType.REMOVEAUDIO)
        }

    override fun extractThumbnail(video: String, options: ThumbnailOptions?): Promise<MediaInfoResult> =
        execute(OperationType.EXTRACTTHUMBNAIL, video, MediaType.IMAGE) { job ->
            val timeMs = options?.timeMs ?: 0.0
            val edge = options?.maxLongEdge ?: 1280.0
            val quality = options?.quality ?: 0.9
            require(timeMs.isFinite() && timeMs >= 0) { "timeMs must be finite and nonnegative." }
            require(edge.isFinite() && edge >= 1 && edge <= 8192) { "maxLongEdge must be in [1, 8192]." }
            require(quality.isFinite() && quality in 0.0..1.0) { "quality must be in [0, 1]." }
            val file = job.resolve(video)
            val info = detectVideoInfo(file.path)
            require(timeMs < info.durationMs) { "timeMs must be less than the video duration." }
            val boundedEdge = ExportPlanner.imageEdge(info.displayWidth, info.displayHeight, edge.toInt(), options?.maxLongEdge != null, Runtime.getRuntime().maxMemory())
            val (width, height) = computeScaledDimensions(info.displayWidth, info.displayHeight, boundedEdge)
            imagePermits.withPermit {
                val extractor = FrameExtractor.Builder(applicationContext, MediaItem.fromUri(Uri.fromFile(file)))
                    .setSeekParameters(if ((options?.precision ?: ThumbnailPrecision.PRECISE) == ThumbnailPrecision.PRECISE) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
                    .setEffects(listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))).build()
                try {
                    val future = extractor.getFrame(timeMs.toLong())
                    val hook = job.control.onCancel { future.cancel(true) }
                    val frame = try {
                        suspendCancellableCoroutine<FrameExtractor.Frame> { continuation ->
                            future.addListener({
                                runCatching { future.get() }.onSuccess { if (continuation.isActive) continuation.resume(it) }
                                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                            }, MoreExecutors.directExecutor())
                            continuation.invokeOnCancellation { future.cancel(true) }
                        }
                    } finally { hook.close() }
                    try {
                        job.control.check()
                        val output = job.output("jpg")
                        output.outputStream().use { check(frame.bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), it)) }
                        outputResult(OperationType.EXTRACTTHUMBNAIL, video, output).copy(actualTimeMs = frame.presentationTimeMs.toDouble())
                    } finally { frame.bitmap.recycle() }
                } finally { extractor.close() }
            }
        }

    override fun deleteGeneratedFile(uri: String): Promise<Boolean> = Promise.async(ioScope) {
        MediaJob.deleteOutput(applicationContext, uri)
    }

    override fun watermarkVideo(video: String, watermark: String, options: WatermarkOptions?): Promise<MediaInfoResult> =
        execute(OperationType.WATERMARKVIDEO, video) { job ->
            exportResolved(job, job.resolve(video), video,
                ExportVideoOptions(null, watermark, options, CompressionPreset.PRESERVE, null, null, false, null), OperationType.WATERMARKVIDEO)
        }

    private fun parseColor(value: String): Int {
        require(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "color must be #RRGGBB or #RRGGBBAA." }
        val rgba = value.drop(1).toLong(16)
        return if (value.length == 7) (0xFF000000L or rgba).toInt() else ((rgba ushr 8) or ((rgba and 255) shl 24)).toInt()
    }

    private fun textBitmap(text: String, fontSize: Float, color: Int, width: Int, height: Int): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = fontSize; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        var layout: StaticLayout
        do {
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
            if (layout.height <= height || paint.textSize <= 1f) break
            paint.textSize = (paint.textSize * 0.85f).coerceAtLeast(1f)
        } while (true)
        require(layout.height <= height) { "Watermark cannot fit inside the video." }
        val bitmapWidth = kotlin.math.ceil((0 until layout.lineCount).maxOf { layout.getLineWidth(it) }.toDouble()).toInt().coerceIn(1, width)
        require(bitmapWidth.toLong() * layout.height <= minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 32)) { "Watermark exceeds the image memory budget." }
        val bitmap = Bitmap.createBitmap(bitmapWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        return bitmap
    }

    /** Media3 owns codec backpressure, frame transforms, timestamps, audio and muxing. */
    private suspend fun exportResolved(job: MediaJob, input: File, inputUri: String, options: ExportVideoOptions, operation: OperationType, sourceInfo: DetectedVideoInfo? = null): MediaInfoResult {
        val info = sourceInfo ?: detectVideoInfo(input.path)
        val source = VideoSource(info.displayWidth, info.displayHeight, info.fps, info.durationMs, info.mime, info.hdr, info.bitrateKbps, info.audioTracks, info.audioChannels, info.audioSampleRate, info.bitDepth)
        val plan = ExportPlanner.plan(source, options)
        require(!(source.bitDepth > 8 && !source.hdr && plan.color != ColorPolicy.SDR && !plan.canCopy)) { "This device pipeline cannot guarantee high-bit-depth SDR re-encoding; choose sdr conversion explicitly." }
        val videoEffects = mutableListOf<Effect>()
        if (plan.width != info.displayWidth || plan.height != info.displayHeight) videoEffects.add(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT))
        when (plan.timing) {
            FrameRatePolicy.PRESERVE -> Unit
            FrameRatePolicy.CAP -> videoEffects.add(CappedFrameRateEffect(plan.fps))
            FrameRatePolicy.CONSTANT -> videoEffects.addAll(ConstantFrameRateEffect.create(plan.fps, (plan.durationMs * 1000).toLong()))
        }
        var bitmap: Bitmap? = null
        try {
            options.watermark?.let { text ->
                require(text.isNotBlank() && text.length <= 4096) { "Watermark must contain 1 to 4096 characters." }
                val opt = options.watermarkOptions
                val font = opt?.fontSize ?: minOf(plan.width, plan.height) * 0.05
                val margin = opt?.margin ?: minOf(plan.width, plan.height) * 0.025
                val opacity = opt?.opacity ?: 1.0
                ExportPlanner.finite(font, 1.0, 512.0, "fontSize"); ExportPlanner.finite(opacity, 0.0, 1.0, "opacity")
                require(margin.isFinite() && margin >= 0 && margin * 2 < minOf(plan.width, plan.height)) { "Invalid watermark margin." }
                val overlayBitmap = textBitmap(text, font.toFloat(), parseColor(opt?.color ?: "#FFFFFFFF"), (plan.width - 2 * margin).toInt(), (plan.height - 2 * margin).toInt())
                bitmap = overlayBitmap
                val position = opt?.position ?: WatermarkPosition.BOTTOM_RIGHT
                val x = when(position) {
                    WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> margin
                    WatermarkPosition.TOP_RIGHT, WatermarkPosition.BOTTOM_RIGHT -> plan.width - margin - overlayBitmap.width
                    WatermarkPosition.CENTER -> (plan.width - overlayBitmap.width) / 2.0
                }
                val y = when(position) {
                    WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> margin
                    WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT -> plan.height - margin - overlayBitmap.height
                    WatermarkPosition.CENTER -> (plan.height - overlayBitmap.height) / 2.0
                }
                val settings = StaticOverlaySettings.Builder().setAlphaScale(opacity.toFloat()).setOverlayFrameAnchor(-1f, 1f)
                    .setBackgroundFrameAnchor((2 * x / plan.width - 1).toFloat(), (1 - 2 * y / plan.height).toFloat()).build()
                videoEffects.add(OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlayBitmap, settings))))
            }
            val audioEffects = if (plan.audio == AudioPolicy.STEREO && info.audioTracks > 0) {
                listOf(ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(info.audioChannels, 2)) })
            } else emptyList()
            val media = MediaItem.Builder().setUri(Uri.fromFile(input))
            options.trim?.let { media.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs((it.startMs * 1000).toLong()).setEndPositionUs((it.endMs * 1000).toLong()).build()) }
            val item = EditedMediaItem.Builder(media.build()).setRemoveAudio(plan.audio == AudioPolicy.MUTE)
                .setEffects(Effects(audioEffects, videoEffects)).build()
            val output = job.output("mp4")
            val exported = transform(listOf(item), output,
                videoKbps = if (plan.canCopy) null else plan.bitrateKbps,
                audioKbps = if (plan.audio == AudioPolicy.STEREO || options.video?.audioBitrateKbps != null) plan.audioBitrateKbps else null,
                includeAudio = plan.audio != AudioPolicy.MUTE && info.audioTracks > 0, preserveMetadata = options.preserveMetadata ?: false,
                control = job.control, plan = plan)
            job.control.emit(ExportStage.VALIDATING)
            val result = outputResult(operation, inputUri, output)
            val warnings = mutableListOf<MediaInfoWarning>()
            if (result.media?.width != plan.width.toDouble() || result.media.height != plan.height.toDouble()) warnings.add(warning("ENCODER_SIZE_FALLBACK", "Encoder changed the requested output dimensions."))
            if (exported.videoMimeType != null && exported.videoMimeType != plan.mime) warnings.add(warning("ENCODER_CODEC_FALLBACK", "Encoder changed the requested output codec."))
            require(!(info.hdr && plan.color == ColorPolicy.PRESERVE && result.media?.hdr != true)) { "Encoder did not preserve HDR; choose sdr conversion explicitly." }
            if ((result.media?.audioTracks ?: 0.0) > 0) {
                val expectedChannels = if (plan.audio == AudioPolicy.STEREO) 2 else info.audioChannels
                require(expectedChannels == 0 || result.media?.audioChannels == expectedChannels.toDouble()) { "Encoder changed the requested audio channel layout." }
            }
            require(plan.color != ColorPolicy.SDR || result.media?.hdr != true) { "Export did not produce the requested SDR output." }
            require(options.trim != null || plan.audio == AudioPolicy.MUTE || info.audioTracks == 0 || (result.media?.audioTracks ?: 0.0) > 0) { "Export unexpectedly lost the primary audio track." }
            if (options.video?.targetSizeBytes != null && output.length() > options.video.targetSizeBytes) warnings.add(warning("TARGET_SIZE_EXCEEDED", "Target size is an estimate; actual encoded output is larger."))
            if (operation == OperationType.COMPRESSMEDIA && output.length() >= input.length()) warnings.add(warning("LIMITED_SAVINGS", "Output is not smaller than the input."))
            if (options.preserveMetadata == true) warnings.add(warning("METADATA_PRESERVE_PARTIAL", "Only supported container metadata is retained."))
            return result.copy(warnings = warnings.takeIf { it.isNotEmpty() }?.toTypedArray(), diagnostics = ExportDiagnostics(
                (SystemClock.elapsedRealtime() - job.control.started).toDouble(), output.length().toDouble(), job.control.peakMemoryBytes,
                when(exported.videoConversionProcess) { ExportResult.CONVERSION_PROCESS_TRANSMUXED -> VideoProcessingPath.COPIED; ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED -> VideoProcessingPath.MIXED; else -> VideoProcessingPath.ENCODED },
                if (plan.audio == AudioPolicy.MUTE || info.audioTracks == 0) AudioProcessingPath.ABSENT else if (exported.audioConversionProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED) AudioProcessingPath.COPIED else AudioProcessingPath.ENCODED,
                exported.videoMimeType, plan.estimatedSizeBytes, exported.videoFrameCount.toDouble().takeIf { it > 0 }))
        } finally { bitmap?.recycle() }
    }

    /** Control stays on Transformer's application looper; the encoder permit excludes I/O/probing. */
    private suspend fun transform(items: List<EditedMediaItem>, output: File, videoKbps: Int? = null, audioKbps: Int? = null, includeAudio: Boolean = false, preserveMetadata: Boolean = false,
        control: PipelineControl, plan: VideoPlan? = null): ExportResult = workMutex.withLock {
        control.check()
        withContext(Dispatchers.Main.immediate) {
            var active: Transformer? = null
            val handler = Handler(Looper.getMainLooper())
            var progressTask: Runnable? = null
            var cancellation: java.io.Closeable? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    val factory = DefaultEncoderFactory.Builder(applicationContext).setEnableFallback(true).setEnableCodecDbLite(true)
                    videoKbps?.let { factory.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(it * 1000).build()) }
                    audioKbps?.let { factory.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(it * 1000).build()) }
                    val builder = Transformer.Builder(applicationContext).setLooper(Looper.getMainLooper()).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(factory.build()).setMuxerFactory(MetadataMuxerFactory(preserveMetadata))
                        .setAssetLoaderFactory(DefaultAssetLoaderFactory(applicationContext, DataSourceBitmapLoader.Builder(applicationContext).setExecutorService(bitmapExecutor).setMaximumOutputDimension(1920).build()))
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(exportResult) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (continuation.isActive) continuation.resumeWithException(exportException) }
                        })
                    plan?.let { builder.setVideoMimeType(it.mime) }
                    val transformer = builder.build()
                    active = transformer
                    val poll = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            val holder = ProgressHolder()
                            val state = transformer.getProgress(holder)
                            control.emit(ExportStage.PROCESSING, if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100.0 else null)
                            handler.postDelayed(this, 200)
                        }
                    }
                    progressTask = poll
                    cancellation = control.onCancel { handler.post { transformer.cancel(); if (continuation.isActive) continuation.resumeWithException(runCatching { control.check() }.exceptionOrNull() ?: PipelineFailure("CANCELLED", "Media job cancelled.")) } }
                    try {
                        control.check()
                        val sequence = if (includeAudio) EditedMediaItemSequence.withAudioAndVideoFrom(items) else EditedMediaItemSequence.withVideoFrom(items)
                        val composition = Composition.Builder(sequence).setHdrMode(if (plan?.color == ColorPolicy.SDR) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR).build()
                        transformer.start(composition, output.path)
                        handler.post(poll)
                    } catch (e: Exception) { transformer.cancel(); if (continuation.isActive) continuation.resumeWithException(e) }
                }
            } finally {
                cancellation?.close()
                progressTask?.let { handler.removeCallbacks(it) }
                withContext(NonCancellable + Dispatchers.Main.immediate) { active?.cancel() }
            }
        }.also { control.check(); check(output.isFile && output.length() > 0) { "Export produced no media." } }
    }


    companion object {
        private val ioScope = CoroutineScope(Dispatchers.IO)
        // Serial across HybridObject instances: device hardware encoders are scarce.
        private val workMutex = Mutex()
        private val imagePermits = Semaphore(1)
        private val bitmapExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor { task ->
            Thread(task, "NitroMediaKit.bitmap").apply { isDaemon = true }
        })
    }

    private fun buildMediaInfo(
        durationMs: Double? = null,
        width: Double? = null,
        height: Double? = null,
        fps: Double? = null,
        format: String? = null,
        sizeBytes: Double? = null,
        audioTracks: Double? = null,
        videoTracks: Double? = null,
        codec: String? = null, hdr: Boolean? = null, bitDepth: Double? = null,
        bitrateKbps: Double? = null, audioChannels: Double? = null, audioSampleRate: Double? = null
    ): MediaInfoMedia? {
        if (
            durationMs == null &&
            width == null &&
            height == null &&
            fps == null &&
            format == null &&
            sizeBytes == null &&
            audioTracks == null &&
            videoTracks == null
        ) {
            return null
        }
        return buildMediaInfo(
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            format = format,
            sizeBytes = sizeBytes,
            audioTracks = audioTracks,
            videoTracks = videoTracks, codec = codec, hdr = hdr, bitDepth = bitDepth,
            bitrateKbps = bitrateKbps, audioChannels = audioChannels, audioSampleRate = audioSampleRate
        )
    }

    private fun makeResult(
        ok: Boolean,
        operation: OperationType,
        type: MediaType,
        inputUri: String? = null,
        outputUri: String? = null,
        segments: Array<String>? = null,
        media: MediaInfoMedia? = null,
        warnings: Array<MediaInfoWarning>? = null,
        error: MediaInfoError? = null, actualTimeMs: Double? = null, diagnostics: ExportDiagnostics? = null
    ): MediaInfoResult {
        return MediaInfoResult(
            ok = ok,
            operation = operation,
            type = type,
            inputUri = inputUri,
            outputUri = outputUri,
            segments = segments,
            media = media,
            warnings = warnings,
            error = error, actualTimeMs = actualTimeMs, diagnostics = diagnostics
        )
    }

    private fun makeErrorResult(
        operation: OperationType,
        type: MediaType,
        inputUri: String? = null,
        outputUri: String? = null,
        error: Exception
    ): MediaInfoResult {
        val errorInfo = MediaInfoError(
            code = (error as? PipelineFailure)?.code ?: error.javaClass.simpleName,
            message = error.message ?: "Unknown error"
        )
        return makeResult(
            ok = false,
            operation = operation,
            type = type,
            inputUri = inputUri,
            outputUri = outputUri,
            error = errorInfo
        )
    }

    private class ValidationException(message: String) : IllegalArgumentException(message)

    private class UnsupportedMediaException(message: String) : IllegalArgumentException(message)

    private data class DetectedImageInfo(
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotationDegrees: Int,
        val hasAlpha: Boolean,
        val sizeBytes: Long,
        val extension: String?
    )

    private data class DetectedVideoInfo(
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotationDegrees: Int,
        val durationMs: Double,
        val fps: Double,
        val sizeBytes: Long,
        val audioTracks: Int,
        val extension: String?, val mime: String, val hdr: Boolean, val bitDepth: Int,
        val bitrateKbps: Double?, val audioChannels: Int, val audioSampleRate: Int
    )

    private data class ResolvedCompressOptions(
        val preset: CompressionPreset,
        val preserveMetadata: Boolean,
        val image: CompressImageOptions?,
        val video: CompressVideoOptions?
    )

    private data class ImageCompressionPlan(
        val format: CompressImageFormat,
        val quality: Double?,
        val maxLongEdge: Int
    )

    private fun warning(code: String, message: String): MediaInfoWarning {
        return MediaInfoWarning(code = code, message = message)
    }

    private fun resolveCompressOptions(options: CompressMediaOptions?): ResolvedCompressOptions {
        return ResolvedCompressOptions(
            preset = options?.preset ?: CompressionPreset.AUTO,
            preserveMetadata = options?.preserveMetadata ?: false,
            image = options?.image,
            video = options?.video
        )
    }

    private fun detectImageInfo(localPath: String): DetectedImageInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(localPath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val file = File(localPath)
        val rotationDegrees = readImageRotationDegrees(localPath)
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val displayWidth = if (isRotated) bounds.outHeight else bounds.outWidth
        val displayHeight = if (isRotated) bounds.outWidth else bounds.outHeight
        val hasAlpha = detectImageAlpha(localPath, bounds.outWidth, bounds.outHeight)

        return DetectedImageInfo(
            width = bounds.outWidth,
            height = bounds.outHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            rotationDegrees = rotationDegrees,
            hasAlpha = hasAlpha,
            sizeBytes = if (file.exists()) file.length() else 0L,
            extension = bounds.outMimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: file.extension.lowercase().takeIf { it.isNotEmpty() && it != "bin" }
        )
    }

    private fun detectVideoInfo(localPath: String): DetectedVideoInfo {
        var extractor: MediaExtractor? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            val file = File(localPath)
            val extension = file.extension.lowercase().ifEmpty { null }
            val sizeBytes = if (file.exists()) file.length() else 0L

            retriever = MediaMetadataRetriever().apply { setDataSource(localPath) }
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
                    ?: throw UnsupportedMediaException("Unable to read video duration.")
            val rotationDegrees =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                    ?: 0

            var width =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            var fps: Double? = null
            var mimeType = "video/avc"
            var hdr = false
            var bitDepth = 0
            var bitrate: Double? = null
            var channels = 0
            var sampleRate = 0

            extractor = MediaExtractor().apply { setDataSource(localPath) }
            var audioTracks = 0
            var hasVideoTrack = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (hasVideoTrack) continue
                    hasVideoTrack = true
                    mimeType = mime
                    val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                    hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                    val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else 0
                    bitDepth = when {
                        hdr -> 10
                        mime == "video/hevc" && profile in listOf(2, 4096, 8192) -> 10
                        mime == "video/hevc" && profile == 1 -> 8
                        mime == "video/avc" && profile == 16 -> 10
                        mime == "video/avc" && profile in listOf(1, 2, 4, 8, 65536, 524288) -> 8
                        else -> 0
                    }
                    bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000.0 else null
                    if ((width ?: 0) <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if ((height ?: 0) <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (fps == null && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                            .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
                    }
                } else if (mime.startsWith("audio/")) {
                    audioTracks++
                    if (audioTracks == 1) {
                        channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                        sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                    }
                }
            }

            if (!hasVideoTrack) {
                throw UnsupportedMediaException("No video track found in input.")
            }

            if (fps == null || !fps.isFinite() || fps <= 0) {
                val videoIndex = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                extractor.selectTrack(videoIndex)
                val times = mutableListOf<Long>()
                repeat(120) { if (extractor.sampleTime >= 0) { times.add(extractor.sampleTime); extractor.advance() } }
                val sorted = times.distinct().sorted()
                val gaps = sorted.zipWithNext { x, y -> y - x }.filter { it > 0 }.sorted()
                if (gaps.isNotEmpty()) fps = 1_000_000.0 / gaps[gaps.size / 2]
            }
            require(durationMs.isFinite() && durationMs > 0) { "Invalid video duration." }
            val safeWidth = requireNotNull(width).also { require(it > 0) { "Invalid video width." } }
            val safeHeight = requireNotNull(height).also { require(it > 0) { "Invalid video height." } }
            val safeFps = fps?.takeIf { it.isFinite() && it > 0 } ?: 0.0
            val normalizedRotation = normalizeRotationDegrees(rotationDegrees)
            val isRotated = normalizedRotation == 90 || normalizedRotation == 270
            val displayWidth = if (isRotated) safeHeight else safeWidth
            val displayHeight = if (isRotated) safeWidth else safeHeight

            return DetectedVideoInfo(
                width = safeWidth,
                height = safeHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotationDegrees = normalizedRotation,
                durationMs = durationMs,
                fps = safeFps,
                sizeBytes = sizeBytes,
                audioTracks = audioTracks,
                extension = extension, mime = mimeType, hdr = hdr, bitDepth = bitDepth, bitrateKbps = bitrate, audioChannels = channels, audioSampleRate = sampleRate
            )
        } finally {
            runCatching { extractor?.release() }
            runCatching { retriever?.release() }
        }
    }

    private fun compressImage(
        inputUri: String,
        localPath: String,
        imageInfo: DetectedImageInfo,
        options: ResolvedCompressOptions,
        job: MediaJob
    ): MediaInfoResult {
        val warnings = mutableListOf<MediaInfoWarning>()
        val plan = resolveImageCompressionPlan(imageInfo, options)
        val imageOptions = options.image
        if (plan.maxLongEdge < (imageOptions?.maxLongEdge?.toInt() ?: maxOf(imageInfo.displayWidth, imageInfo.displayHeight))) {
            warnings += warning("IMAGE_SIZE_LIMIT", "Output dimensions are bounded by the preset and image memory budget.")
        }

        if (plan.format == CompressImageFormat.PNG && imageOptions?.quality != null) {
            warnings += warning(
                code = "IMAGE_QUALITY_IGNORED",
                message = "`image.quality` is ignored for PNG output."
            )
        }
        if (imageInfo.sizeBytes in 1..(400 * 1024L) &&
            plan.maxLongEdge >= maxOf(imageInfo.displayWidth, imageInfo.displayHeight)
        ) {
            warnings += warning(
                code = "ALREADY_SMALL",
                message = "Input image is already small; compression savings may be limited."
            )
        }

        val decodeSampleSize = ExportPlanner.sampleSize(
            width = imageInfo.width,
            height = imageInfo.height,
            targetLongEdge = plan.maxLongEdge
        )
        val decodedBitmap = if (Build.VERSION.SDK_INT >= 28) {
            val (w, h) = computeScaledDimensions(imageInfo.displayWidth, imageInfo.displayHeight, plan.maxLongEdge)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(localPath))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(w, h)
            }
        } else BitmapFactory.decodeFile(localPath, BitmapFactory.Options().apply {
            inSampleSize = decodeSampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw UnsupportedMediaException("Unable to decode image input.")

        var orientedBitmap: Bitmap? = null
        var resizedBitmap: Bitmap? = null
        try {
            orientedBitmap = if (Build.VERSION.SDK_INT >= 28) decodedBitmap else applyExifOrientation(
                bitmap = decodedBitmap,
                orientation = readImageExifOrientation(localPath)
            )

            val (targetWidth, targetHeight) = computeScaledDimensions(
                width = orientedBitmap.width,
                height = orientedBitmap.height,
                maxLongEdge = plan.maxLongEdge
            )

            resizedBitmap = if (
                orientedBitmap.width == targetWidth &&
                orientedBitmap.height == targetHeight
            ) {
                orientedBitmap
            } else {
                Bitmap.createScaledBitmap(orientedBitmap, targetWidth, targetHeight, true)
            }

            val outputExt = if (plan.format == CompressImageFormat.PNG) "png" else "jpg"
            val outputFile = job.output(outputExt)

            val encoded = FileOutputStream(outputFile).use { output ->
                when (plan.format) {
                    CompressImageFormat.PNG -> resizedBitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        output
                    )

                    else -> {
                        val qualityInt = ((plan.quality ?: 0.8) * 100.0).roundToInt().coerceIn(0, 100)
                        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, qualityInt, output)
                    }
                }
            }

            if (!encoded) {
                throw IOException("Failed to encode compressed image.")
            }

            if (options.preserveMetadata) {
                if (plan.format == CompressImageFormat.PNG) {
                    warnings += warning(
                        code = "METADATA_PRESERVE_PARTIAL",
                        message = "PNG metadata passthrough is not supported on Android; output keeps visual orientation only."
                    )
                } else {
                    runCatching { copyExifMetadata(sourcePath = localPath, targetPath = outputFile.absolutePath) }
                        .onFailure {
                            warnings += warning(
                                code = "METADATA_PRESERVE_FAILED",
                                message = "Unable to preserve EXIF metadata: ${it.message ?: "unknown error"}"
                            )
                        }
                }
            }

            val outputSizeBytes = if (outputFile.exists()) outputFile.length().toDouble() else null
            val sourceSizeBytes = imageInfo.sizeBytes.toDouble()
            if (sourceSizeBytes > 0.0 && outputSizeBytes != null && outputSizeBytes >= sourceSizeBytes * 0.95) {
                warnings += warning(
                    code = "LIMITED_SAVINGS",
                    message = "Compressed output is close to source size."
                )
            }

            val media = buildMediaInfo(
                width = targetWidth.toDouble(),
                height = targetHeight.toDouble(),
                format = outputExt,
                sizeBytes = outputSizeBytes,
                audioTracks = 0.0,
                videoTracks = 0.0
            )

            return makeResult(
                ok = true,
                operation = OperationType.COMPRESSMEDIA,
                type = MediaType.IMAGE,
                inputUri = inputUri,
                outputUri = outputFile.absolutePath,
                media = media,
                warnings = warnings.takeIf { it.isNotEmpty() }?.toTypedArray()
            )
        } finally {
            if (resizedBitmap != null && resizedBitmap !== orientedBitmap) {
                resizedBitmap.recycle()
            }
            if (orientedBitmap != null && orientedBitmap !== decodedBitmap) {
                orientedBitmap.recycle()
            }
            decodedBitmap.recycle()
        }
    }

    private fun resolveImageCompressionPlan(
        info: DetectedImageInfo,
        options: ResolvedCompressOptions
    ): ImageCompressionPlan {
        val imageOptions = options.image
        val sourceLongEdge = maxOf(info.displayWidth, info.displayHeight)
        val sizeBytes = info.sizeBytes
        val presetLongEdge = when (options.preset) {
            CompressionPreset.AUTO -> when {
                sizeBytes <= 1_500_000L -> minOf(sourceLongEdge, 4096)
                sizeBytes <= 5_000_000L -> minOf(sourceLongEdge, 2048)
                else -> minOf(sourceLongEdge, 1600)
            }

            CompressionPreset.PRESERVE -> sourceLongEdge
            CompressionPreset.HIGH -> minOf(sourceLongEdge, 4096)
            CompressionPreset.BALANCED -> minOf(sourceLongEdge, 1920)
            CompressionPreset.SMALL -> minOf(sourceLongEdge, 1280)
        }

        val manualLongEdge = imageOptions?.maxLongEdge?.let { value ->
            requirePositiveFinite(value, "image.maxLongEdge")
            require(value <= 8192) { "image.maxLongEdge must not exceed 8192." }
            value.roundToInt()
        }
        val targetLongEdge = ExportPlanner.imageEdge(info.displayWidth, info.displayHeight, minOf(
            sourceLongEdge,
            (manualLongEdge ?: presetLongEdge).coerceAtLeast(2)
        ), manualLongEdge != null, Runtime.getRuntime().maxMemory() / if (Build.VERSION.SDK_INT < 28) 2 else 1)

        val explicitFormat = imageOptions?.format ?: CompressImageFormat.AUTO
        val outputFormat = when (explicitFormat) {
            CompressImageFormat.AUTO -> if (info.hasAlpha) CompressImageFormat.PNG else CompressImageFormat.JPEG
            CompressImageFormat.JPEG -> CompressImageFormat.JPEG
            CompressImageFormat.PNG -> CompressImageFormat.PNG
        }

        val manualQuality = imageOptions?.quality?.also { quality ->
            requireFinite(quality, "image.quality")
            if (quality < 0.0 || quality > 1.0) {
                throw ValidationException("`image.quality` must be between 0 and 1.")
            }
        }
        val megapixels = (info.displayWidth.toDouble() * info.displayHeight.toDouble()) / 1_000_000.0
        val presetQuality = when (options.preset) {
            CompressionPreset.AUTO -> when {
                megapixels <= 2.0 -> 0.86
                megapixels <= 8.0 -> 0.80
                else -> 0.74
            }

            CompressionPreset.PRESERVE -> 1.0
            CompressionPreset.HIGH -> 0.94
            CompressionPreset.BALANCED -> 0.82
            CompressionPreset.SMALL -> 0.72
        }

        val outputQuality = if (outputFormat == CompressImageFormat.JPEG) {
            manualQuality ?: presetQuality
        } else {
            null
        }

        return ImageCompressionPlan(
            format = outputFormat,
            quality = outputQuality,
            maxLongEdge = targetLongEdge
        )
    }

    private fun computeScaledDimensions(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val sourceLong = maxOf(safeWidth, safeHeight).toDouble()
        val targetLong = maxLongEdge.coerceAtLeast(2).toDouble()
        if (sourceLong <= targetLong) {
            return safeWidth to safeHeight
        }

        val scale = targetLong / sourceLong
        val scaledWidth = maxOf(1, (safeWidth * scale).roundToInt())
        val scaledHeight = maxOf(1, (safeHeight * scale).roundToInt())
        return scaledWidth to scaledHeight
    }

    private fun requireFinite(value: Double, name: String) {
        if (!value.isFinite()) {
            throw ValidationException("`$name` must be a finite number.")
        }
    }

    private fun requirePositiveFinite(value: Double, name: String) {
        requireFinite(value, name)
        if (value <= 0.0) {
            throw ValidationException("`$name` must be greater than 0.")
        }
    }

    private fun normalizeRotationDegrees(rotation: Int): Int {
        val normalized = ((rotation % 360) + 360) % 360
        return when (normalized) {
            90, 180, 270 -> normalized
            else -> 0
        }
    }

    private fun readImageExifOrientation(path: String): Int {
        return runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun readImageRotationDegrees(path: String): Int {
        return when (readImageExifOrientation(path)) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90

            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180

            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270

            else -> 0
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return bitmap
        }

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    preScale(-1f, 1f)
                    postRotate(270f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    preScale(-1f, 1f)
                    postRotate(90f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun detectImageAlpha(path: String, width: Int, height: Int): Boolean {
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = ExportPlanner.sampleSize(width, height, 64)
            }
        ) ?: return false
        return try {
            bitmap.hasAlpha()
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyExifMetadata(sourcePath: String, targetPath: String) {
        val sourceExif = ExifInterface(sourcePath)
        val targetExif = ExifInterface(targetPath)
        val tags = arrayOf(
            "ApertureValue",
            "Artist",
            "DateTime",
            "DateTimeDigitized",
            "ExposureBiasValue",
            "ExposureMode",
            "ExposureProgram",
            "ExposureTime",
            "Flash",
            "FocalLength",
            "FNumber",
            "GPSAltitude",
            "GPSAltitudeRef",
            "GPSDateStamp",
            "GPSLatitude",
            "GPSLatitudeRef",
            "GPSLongitude",
            "GPSLongitudeRef",
            "GPSProcessingMethod",
            "GPSTimeStamp",
            "ISOSpeedRatings",
            "Make",
            "Model",
            "WhiteBalance"
        )
        for (tag in tags) {
            val value = sourceExif.getAttribute(tag)
            if (!value.isNullOrEmpty()) {
                targetExif.setAttribute(tag, value)
            }
        }
        // Pixel data is already transformed to upright orientation.
        targetExif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        targetExif.saveAttributes()
    }


}
