package com.margelo.nitro.nitromediakit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
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
        operation: OperationType,
        input: String?,
        type: MediaType = MediaType.VIDEO,
        block: suspend (MediaJob) -> MediaInfoResult
    ): Promise<MediaInfoResult> = Promise.async(ioScope) {
        workMutex.withLock {
            val job = MediaJob(applicationContext)
            var success = false
            try {
                block(job).also { success = it.ok }
            } catch (error: Exception) {
                makeErrorResult(operation, type, input, error = error)
            } finally {
                job.finish(success)
            }
        }
    }

    override fun getMediaInfo(inputUri: String): Promise<MediaInfoResult> =
        execute(OperationType.GETMEDIAINFO, inputUri) { job ->
            val file = job.resolve(inputUri)
            val (type, media) = inspect(file)
            makeResult(true, OperationType.GETMEDIAINFO, type, inputUri, media = media)
        }

    private fun inspect(file: File): Pair<MediaType, MediaInfoMedia> {
        val image = detectImageInfo(file.path)
        if (image != null) return MediaType.IMAGE to MediaInfoMedia(
            durationMs = null, width = image.displayWidth.toDouble(), height = image.displayHeight.toDouble(),
            fps = null, format = image.extension, sizeBytes = file.length().toDouble(), audioTracks = 0.0, videoTracks = 0.0
        )
        val info = detectVideoInfo(file.path)
        return MediaType.VIDEO to MediaInfoMedia(
            durationMs = info.durationMs, width = info.displayWidth.toDouble(), height = info.displayHeight.toDouble(),
            fps = info.fps, format = file.extension, sizeBytes = file.length().toDouble(),
            audioTracks = info.audioTracks.toDouble(), videoTracks = 1.0
        )
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
                compressImage(inputUri, file.path, image, resolved, job)
            } else {
                val info = detectVideoInfo(file.path)
                val plan = resolveVideoCompressionPlan(info, resolved)
                val (width, height) = computeScaledDimensions(info.displayWidth, info.displayHeight, plan.maxLongEdge)
                val effects = listOf(
                    Presentation.createForWidthAndHeight(width.coerceAtLeast(2) / 2 * 2, height.coerceAtLeast(2) / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT),
                    FrameDropEffect.createDefaultFrameDropEffect(minOf(plan.maxFps.toDouble(), info.fps).toFloat())
                )
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
                    .setRemoveAudio(!plan.keepAudio).setEffects(Effects(emptyList(), effects)).build()
                val output = job.output("mp4")
                transform(listOf(item), output, plan.videoBitrateKbps, plan.audioBitrateKbps, includeAudio = plan.keepAudio && info.audioTracks > 0, preserveMetadata = resolved.preserveMetadata)
                val warnings = mutableListOf<MediaInfoWarning>()
                if (resolved.preserveMetadata) warnings.add(warning("METADATA_PRESERVE_PARTIAL", "Only metadata supported by the device MP4 muxer is retained."))
                if (output.length() >= file.length()) warnings.add(warning("LIMITED_SAVINGS", "Output is not smaller than the source; reduce size or bitrate for more savings."))
                outputResult(OperationType.COMPRESSMEDIA, inputUri, output, warnings.takeIf { it.isNotEmpty() }?.toTypedArray())
            }
        }

    override fun convertImageToVideo(image: String, duration: Double): Promise<MediaInfoResult> =
        execute(OperationType.CONVERTIMAGETOVIDEO, image) { job ->
            require(duration.isFinite() && duration > 0 && duration <= 86_400) { "Duration must be in (0, 86400] seconds." }
            val input = job.resolve(image)
            val info = detectImageInfo(input.path) ?: throw UnsupportedMediaException("Input must be an image.")
            val (width, height) = computeScaledDimensions(info.displayWidth, info.displayHeight, 1920)
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(input)).setImageDurationMs((duration * 1000).roundToInt().toLong().coerceAtLeast(1)).build())
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width.coerceAtLeast(2) / 2 * 2, height.coerceAtLeast(2) / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            val output = job.output("mp4")
            transform(listOf(item), output)
            outputResult(OperationType.CONVERTIMAGETOVIDEO, image, output)
        }

    override fun mergeVideos(videos: Array<String>): Promise<MediaInfoResult> =
        execute(OperationType.MERGEVIDEOS, videos.firstOrNull()) { job ->
            require(videos.isNotEmpty()) { "At least one video is required." }
            val files = videos.map { job.resolve(it) }
            val infos = files.map { detectVideoInfo(it.path) }
            val first = infos.first()
            val (width, height) = computeScaledDimensions(first.displayWidth, first.displayHeight, 1920)
            val presentation = Presentation.createForWidthAndHeight(width.coerceAtLeast(2) / 2 * 2, height.coerceAtLeast(2) / 2 * 2, Presentation.LAYOUT_SCALE_TO_FIT)
            val items = files.map { file ->
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
                    .setEffects(Effects(emptyList(), listOf(presentation))).build()
            }
            val output = job.output("mp4")
            transform(items, output, includeAudio = infos.any { it.audioTracks > 0 })
            outputResult(OperationType.MERGEVIDEOS, videos.first(), output)
        }

    override fun splitVideo(video: String, segments: Array<VideoSegment>): Promise<MediaInfoResult> =
        execute(OperationType.SPLITVIDEO, video) { job ->
            require(segments.isNotEmpty()) { "At least one segment is required." }
            val file = job.resolve(video)
            val info = detectVideoInfo(file.path)
            segments.forEach { validateSegment(it, info.durationMs) }
            val outputs = segments.map { segment ->
                job.output("mp4").also { transform(listOf(clipped(file, segment)), it, includeAudio = info.audioTracks > 0) }.path
            }
            makeResult(true, OperationType.SPLITVIDEO, MediaType.VIDEO, video, segments = outputs.toTypedArray())
        }

    override fun trimVideo(video: String, segment: VideoSegment): Promise<MediaInfoResult> =
        execute(OperationType.TRIMVIDEO, video) { job ->
            val file = job.resolve(video)
            val info = detectVideoInfo(file.path)
            validateSegment(segment, info.durationMs)
            val output = job.output("mp4")
            transform(listOf(clipped(file, segment)), output, includeAudio = info.audioTracks > 0)
            outputResult(OperationType.TRIMVIDEO, video, output)
        }

    private fun validateSegment(segment: VideoSegment, durationMs: Double) {
        require(segment.startMs.isFinite() && segment.endMs.isFinite() && segment.startMs >= 0 && segment.endMs > segment.startMs && segment.endMs <= durationMs) {
            "Segments must satisfy 0 <= startMs < endMs <= video duration."
        }
    }

    private fun clipped(file: File, segment: VideoSegment): EditedMediaItem = EditedMediaItem.Builder(
        MediaItem.Builder().setUri(Uri.fromFile(file)).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionUs((segment.startMs * 1000).toLong())
                .setEndPositionUs((segment.endMs * 1000).toLong()).build()
        ).build()
    ).build()

    override fun removeAudio(video: String): Promise<MediaInfoResult> =
        execute(OperationType.REMOVEAUDIO, video) { job ->
            val file = job.resolve(video)
            detectVideoInfo(file.path)
            val output = job.output("mp4")
            transform(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).setRemoveAudio(true).build()), output)
            outputResult(OperationType.REMOVEAUDIO, video, output)
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
            val (width, height) = computeScaledDimensions(info.displayWidth, info.displayHeight, edge.toInt())
            val retriever = MediaMetadataRetriever()
            var bitmap: Bitmap? = null
            try {
                retriever.setDataSource(file.path)
                bitmap = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime((timeMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                } else retriever.getFrameAtTime((timeMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                val frame = bitmap ?: throw IOException("Cannot decode the requested frame.")
                val scaled = if (frame.width > width || frame.height > height) Bitmap.createScaledBitmap(frame, width, height, true) else frame
                try {
                    val output = job.output("jpg")
                    output.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), it)) { "Cannot encode thumbnail." } }
                    outputResult(OperationType.EXTRACTTHUMBNAIL, video, output)
                } finally { if (scaled !== frame) scaled.recycle() }
            } finally {
                bitmap?.recycle()
                retriever.release()
            }
        }

    override fun deleteGeneratedFile(uri: String): Promise<Boolean> = Promise.async(ioScope) {
        workMutex.withLock { MediaJob.deleteOutput(applicationContext, uri) }
    }

    override fun watermarkVideo(video: String, watermark: String, options: WatermarkOptions?): Promise<MediaInfoResult> =
        execute(OperationType.WATERMARKVIDEO, video) { job ->
            require(watermark.isNotBlank() && watermark.length <= 4096) { "Watermark must contain 1 to 4096 characters." }
            val input = job.resolve(video)
            val info = detectVideoInfo(input.path)
            val fontSize = options?.fontSize ?: minOf(info.displayWidth, info.displayHeight) * 0.05
            val margin = options?.margin ?: minOf(info.displayWidth, info.displayHeight) * 0.025
            val opacity = options?.opacity ?: 1.0
            require(fontSize.isFinite() && fontSize >= 1 && fontSize <= 512) { "fontSize must be in [1, 512]." }
            require(margin.isFinite() && margin >= 0 && margin * 2 < minOf(info.displayWidth, info.displayHeight)) { "margin leaves no room for the watermark." }
            require(opacity.isFinite() && opacity in 0.0..1.0) { "opacity must be in [0, 1]." }
            val color = parseColor(options?.color ?: "#FFFFFFFF")
            val bitmap = textBitmap(watermark, fontSize.toFloat(), color, info.displayWidth - 2 * margin.toInt(), info.displayHeight - 2 * margin.toInt())
            try {
                val position = options?.position ?: WatermarkPosition.BOTTOM_RIGHT
                val x = when (position) {
                    WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> margin
                    WatermarkPosition.TOP_RIGHT, WatermarkPosition.BOTTOM_RIGHT -> info.displayWidth - margin - bitmap.width
                    WatermarkPosition.CENTER -> (info.displayWidth - bitmap.width) / 2.0
                }
                val y = when (position) {
                    WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> margin
                    WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT -> info.displayHeight - margin - bitmap.height
                    WatermarkPosition.CENTER -> (info.displayHeight - bitmap.height) / 2.0
                }
                val settings = StaticOverlaySettings.Builder().setAlphaScale(opacity.toFloat())
                    .setOverlayFrameAnchor(-1f, 1f)
                    .setBackgroundFrameAnchor((2 * x / info.displayWidth - 1).toFloat(), (1 - 2 * y / info.displayHeight).toFloat()).build()
                val overlay = BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)
                val effects = Effects(emptyList(), listOf(OverlayEffect(listOf<TextureOverlay>(overlay))))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(effects).build()
                val output = job.output("mp4")
                transform(listOf(item), output, includeAudio = info.audioTracks > 0)
                outputResult(OperationType.WATERMARKVIDEO, video, output)
            } finally { bitmap.recycle() }
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
        val bitmap = Bitmap.createBitmap((0 until layout.lineCount).maxOf { layout.getLineWidth(it) }.toInt().coerceIn(1, width), layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        return bitmap
    }

    /** Media3 owns codec backpressure, frame transforms, timestamps, audio and muxing. */
    private suspend fun transform(items: List<EditedMediaItem>, output: File, videoKbps: Int? = null, audioKbps: Int? = null, includeAudio: Boolean = false, preserveMetadata: Boolean = false) {
        withTimeout(30 * 60 * 1000L) {
            withContext(Dispatchers.Main.immediate) {
                var activeTransformer: Transformer? = null
                try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val factory = DefaultEncoderFactory.Builder(applicationContext).setEnableFallback(true)
                    if (videoKbps != null) factory.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoKbps * 1000).build())
                    if (audioKbps != null) factory.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(audioKbps * 1000).build())
                    val transformer = Transformer.Builder(applicationContext).setLooper(Looper.getMainLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(factory.build())
                        .setMuxerFactory(MetadataMuxerFactory(preserveMetadata))
                        .setAssetLoaderFactory(DefaultAssetLoaderFactory(applicationContext,
                            DataSourceBitmapLoader.Builder(applicationContext).setExecutorService(bitmapExecutor)
                                .setMaximumOutputDimension(1920).build()))
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        }).build()
                    activeTransformer = transformer
                    try {
                        val sequence = if (includeAudio) EditedMediaItemSequence.withAudioAndVideoFrom(items)
                        else EditedMediaItemSequence.withVideoFrom(items)
                        val composition = Composition.Builder(sequence)
                            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
                        transformer.start(composition, output.path)
                    } catch (error: Exception) {
                        transformer.cancel()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                } finally {
                    // Resume/cancellation returns to the application looper before recycling overlays or deleting files.
                    activeTransformer?.cancel()
                }
            }
        }
        check(output.isFile && output.length() > 0) { "Export produced no media." }
    }

    companion object {
        private val ioScope = CoroutineScope(Dispatchers.IO)
        // Serial across HybridObject instances: device hardware encoders are scarce.
        private val workMutex = Mutex()
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
        videoTracks: Double? = null
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
        return MediaInfoMedia(
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            format = format,
            sizeBytes = sizeBytes,
            audioTracks = audioTracks,
            videoTracks = videoTracks
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
        error: MediaInfoError? = null
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
            error = error
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
            code = error.javaClass.simpleName ?: "Error",
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
        val extension: String?
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

    private data class VideoCompressionPlan(
        val maxLongEdge: Int,
        val maxFps: Int,
        val videoBitrateKbps: Int,
        val audioBitrateKbps: Int,
        val keepAudio: Boolean
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
        val hasAlpha = detectImageAlpha(localPath)

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
            var fps =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()

            extractor = MediaExtractor().apply { setDataSource(localPath) }
            var audioTracks = 0
            var hasVideoTrack = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    hasVideoTrack = true
                    if (width == null && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height == null && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (fps == null && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                            .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
                    }
                } else if (mime.startsWith("audio/")) {
                    audioTracks++
                }
            }

            if (!hasVideoTrack) {
                throw UnsupportedMediaException("No video track found in input.")
            }

            require(durationMs.isFinite() && durationMs > 0) { "Invalid video duration." }
            val safeWidth = (width ?: 0).coerceAtLeast(1)
            val safeHeight = (height ?: 0).coerceAtLeast(1)
            val safeFps = (fps ?: 30.0).takeIf { it.isFinite() && it > 0 } ?: 30.0
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
                extension = extension
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

        val decodeSampleSize = calculateInSampleSize(
            width = imageInfo.width,
            height = imageInfo.height,
            targetLongEdge = plan.maxLongEdge
        )
        val decodedBitmap = BitmapFactory.decodeFile(
            localPath,
            BitmapFactory.Options().apply {
                inSampleSize = decodeSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw UnsupportedMediaException("Unable to decode image input.")

        var orientedBitmap: Bitmap? = null
        var resizedBitmap: Bitmap? = null
        try {
            orientedBitmap = applyExifOrientation(
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

            CompressionPreset.HIGH -> minOf(sourceLongEdge, 2560)
            CompressionPreset.BALANCED -> minOf(sourceLongEdge, 1920)
            CompressionPreset.SMALL -> minOf(sourceLongEdge, 1280)
        }

        val manualLongEdge = imageOptions?.maxLongEdge?.let { value ->
            requirePositiveFinite(value, "image.maxLongEdge")
            require(value <= 8192) { "image.maxLongEdge must not exceed 8192." }
            value.roundToInt()
        }
        val targetLongEdge = minOf(
            sourceLongEdge,
            (manualLongEdge ?: presetLongEdge).coerceAtLeast(2)
        )

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

            CompressionPreset.HIGH -> 0.9
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

    private fun resolveVideoCompressionPlan(
        info: DetectedVideoInfo,
        options: ResolvedCompressOptions
    ): VideoCompressionPlan {
        val videoOptions = options.video
        val durationSec = (info.durationMs / 1000.0).coerceAtLeast(0.001)
        val sourceLongEdge = maxOf(info.displayWidth, info.displayHeight)
        val sourceFps = if (info.fps.isFinite() && info.fps > 0) info.fps else 30.0
        val inputBitrateKbps = (info.sizeBytes.toDouble() * 8.0) / durationSec / 1000.0

        val presetLongEdge = when (options.preset) {
            CompressionPreset.AUTO -> when {
                sourceLongEdge > 1920 -> 1280
                durationSec > 120.0 -> minOf(sourceLongEdge, 720)
                durationSec > 30.0 -> minOf(sourceLongEdge, 960)
                else -> minOf(sourceLongEdge, 1080)
            }

            CompressionPreset.HIGH -> minOf(sourceLongEdge, 1280)
            CompressionPreset.BALANCED -> minOf(sourceLongEdge, 960)
            CompressionPreset.SMALL -> minOf(sourceLongEdge, 720)
        }

        val presetFps = when (options.preset) {
            CompressionPreset.AUTO -> minOf(sourceFps, 30.0)
            CompressionPreset.HIGH -> minOf(sourceFps, 30.0)
            CompressionPreset.BALANCED -> minOf(sourceFps, 30.0)
            CompressionPreset.SMALL -> minOf(sourceFps, 24.0)
        }

        val presetVideoBitrate = when (options.preset) {
            CompressionPreset.AUTO -> {
                val base = when {
                    presetLongEdge <= 480 -> 700.0
                    presetLongEdge <= 720 -> 1200.0
                    presetLongEdge <= 960 -> 1800.0
                    else -> 2600.0
                }
                val durationMultiplier = when {
                    durationSec <= 30.0 -> 1.15
                    durationSec <= 120.0 -> 1.0
                    else -> 0.82
                }
                val target = clampDouble(base * durationMultiplier, 600.0, 3200.0)
                minOf(target, maxOf(600.0, inputBitrateKbps * 0.85))
            }

            CompressionPreset.HIGH -> 2800.0
            CompressionPreset.BALANCED -> 1800.0
            CompressionPreset.SMALL -> 1100.0
        }

        val presetAudioBitrate = when (options.preset) {
            CompressionPreset.AUTO -> when {
                durationSec > 300.0 -> 64.0
                durationSec <= 30.0 -> 128.0
                else -> 96.0
            }

            CompressionPreset.HIGH -> 128.0
            CompressionPreset.BALANCED -> 96.0
            CompressionPreset.SMALL -> 64.0
        }

        val overrideLongEdge = videoOptions?.maxLongEdge?.also { requirePositiveFinite(it, "video.maxLongEdge"); require(it <= 8192) { "video.maxLongEdge must not exceed 8192." } }
        val overrideFps = videoOptions?.maxFps?.also { requirePositiveFinite(it, "video.maxFps") }
        val overrideVideoBitrate =
            videoOptions?.videoBitrateKbps?.also { requirePositiveFinite(it, "video.videoBitrateKbps") }
        val overrideAudioBitrate =
            videoOptions?.audioBitrateKbps?.also { requirePositiveFinite(it, "video.audioBitrateKbps") }

        return VideoCompressionPlan(
            maxLongEdge = minOf(
                sourceLongEdge,
                (overrideLongEdge?.roundToInt() ?: presetLongEdge).coerceAtLeast(2)
            ),
            maxFps = minOf(sourceFps, overrideFps ?: presetFps).roundToInt().coerceIn(1, 120),
            videoBitrateKbps = (overrideVideoBitrate ?: presetVideoBitrate).roundToInt().coerceIn(300, 12_000),
            audioBitrateKbps = (overrideAudioBitrate ?: presetAudioBitrate).roundToInt().coerceIn(32, 320),
            keepAudio = videoOptions?.keepAudio ?: true
        )
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetLongEdge: Int): Int {
        var inSampleSize = 1
        val sourceLongEdge = maxOf(width, height)
        if (targetLongEdge <= 0 || sourceLongEdge <= targetLongEdge) {
            return inSampleSize
        }

        while ((sourceLongEdge / (inSampleSize * 2)) >= targetLongEdge) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
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

    private fun clampDouble(value: Double, min: Double, max: Double): Double {
        return kotlin.math.max(min, kotlin.math.min(max, value))
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

    private fun detectImageAlpha(path: String): Boolean {
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 128
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
