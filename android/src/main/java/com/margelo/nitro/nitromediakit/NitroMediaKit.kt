package com.margelo.nitro.nitromediakit

import kotlin.math.PI
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.os.Environment
import android.opengl.*
import com.margelo.nitro.core.Promise
import com.margelo.nitro.NitroModules
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import android.media.MediaCodecInfo.VideoCapabilities
import android.util.Range
import com.margelo.nitro.core.ArrayBuffer
import java.nio.ByteBuffer
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.Color
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class NitroMediaKit : HybridNitroMediaKitSpec() {
    override val memorySize: Long
        get() = 0L

    // Obtain application context from NitroModules
    private val applicationContext = NitroModules.applicationContext
        ?: throw IllegalStateException("Application context is null")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private fun getLocalFilePath(pathOrUrl: String): String {
        return try {
            if (isRemoteUrl(pathOrUrl)) downloadFile(pathOrUrl) else pathOrUrl
        } catch (e: Exception) {
            throw IOException("Failed to fetch input media: $pathOrUrl", e)
        }
    }

    private class MonotonicPts {
        private var last = -1L
        fun nextFrom(candidateUs: Long): Long {
            // Ensure strictly increasing by at least 1us
            val safe = if (candidateUs <= last) last + 1 else candidateUs
            last = safe
            return safe
        }
        fun reset() { last = -1L }
    }

    private class MuxerTrackState {
        var started = false
        var trackIndex = -1
        var firstPtsUs = -1L
        val mono = MonotonicPts()
    }

    private fun drainEncoderToMuxer(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        state: MuxerTrackState,
        bufferInfo: MediaCodec.BufferInfo,
        timeoutUs: Long,
        untilEos: Boolean
    ) {
        var idleRounds = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)

            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    // During EOS drain, don't bail instantly — give encoder time to flush.
                    idleRounds++
                    if (idleRounds >= 50) return
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!state.started) {
                        state.trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        state.started = true
                    }
                }

                else -> if (outIndex >= 0) {
                    idleRounds = 0
                    val encodedData = encoder.getOutputBuffer(outIndex)

                    if (encodedData != null) {
                        val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val hasData = bufferInfo.size > 0

                        if (!isCodecConfig && hasData && state.started) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)

                            if (state.firstPtsUs < 0) state.firstPtsUs = bufferInfo.presentationTimeUs
                            val normPts = maxOf(0L, bufferInfo.presentationTimeUs - state.firstPtsUs)
                            val safePts = state.mono.nextFrom(normPts)

                            val writeInfo = MediaCodec.BufferInfo().apply {
                                set(bufferInfo.offset, bufferInfo.size, safePts, bufferInfo.flags)
                            }
                            muxer.writeSampleData(state.trackIndex, encodedData, writeInfo)
                        }
                    }

                    encoder.releaseOutputBuffer(outIndex, false)

                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (eos) return
                }
            }
        }
    }

    private class PtsTracker {
        var prev = -1L
        var last = -1L
        fun record(pts: Long) { prev = last; last = pts }
        fun estimatedLastSampleDuration() = if (prev >= 0) last - prev else 0L
    }

    // Helper method to check if a string is a remote URL
    private fun isRemoteUrl(path: String): Boolean {
        return path.startsWith("http://") || path.startsWith("https://")
    }

    override fun getMediaInfo(inputUri: String): Promise<MediaInfoResult> {
        return Promise.async {
            var extractor: MediaExtractor? = null
            var retriever: MediaMetadataRetriever? = null
            var typeGuess = MediaType.VIDEO
            try {
                val localPath = getLocalFilePath(inputUri)
                val file = File(localPath)
                val sizeBytes = if (file.exists()) file.length().toDouble() else null
                val extension = file.extension.lowercase().ifEmpty { null }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(localPath, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    typeGuess = MediaType.IMAGE
                    val media = buildMediaInfo(
                        width = options.outWidth.toDouble(),
                        height = options.outHeight.toDouble(),
                        format = extension,
                        sizeBytes = sizeBytes,
                        audioTracks = 0.0,
                        videoTracks = 0.0
                    )
                    return@async makeResult(
                        ok = true,
                        operation = OperationType.GETMEDIAINFO,
                        type = MediaType.IMAGE,
                        inputUri = inputUri,
                        media = media
                    )
                }

                retriever = MediaMetadataRetriever().apply { setDataSource(localPath) }
                val durationMs = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
                val width = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toDoubleOrNull()
                val height = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toDoubleOrNull()

                extractor = MediaExtractor().apply { setDataSource(localPath) }
                var videoTracks = 0
                var audioTracks = 0
                var fps: Double? = null
                for (i in 0 until extractor!!.trackCount) {
                    val format = extractor!!.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTracks++
                        if (fps == null && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                        }
                    } else if (mime.startsWith("audio/")) {
                        audioTracks++
                    }
                }

                val media = buildMediaInfo(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    format = extension,
                    sizeBytes = sizeBytes,
                    audioTracks = audioTracks.toDouble(),
                    videoTracks = videoTracks.toDouble()
                )

                makeResult(
                    ok = true,
                    operation = OperationType.GETMEDIAINFO,
                    type = MediaType.VIDEO,
                    inputUri = inputUri,
                    media = media
                )
            } catch (e: Exception) {
                makeErrorResult(
                    operation = OperationType.GETMEDIAINFO,
                    type = typeGuess,
                    inputUri = inputUri,
                    error = e
                )
            } finally {
                runCatching { extractor?.release() }
                runCatching { retriever?.release() }
            }
        }
    }

    override fun convertImageToVideo(image: String, duration: Double): Promise<MediaInfoResult> {
        return Promise.async {
            var result: MediaInfoResult
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var eglHelper: EglHelper? = null

            val bufferInfo = MediaCodec.BufferInfo()
            val muxState = MuxerTrackState()

            try {
                val localImagePath = getLocalFilePath(image)
                val bitmap = BitmapFactory.decodeFile(localImagePath)
                    ?: throw IOException("Cannot decode image at path: $localImagePath")

                val codecInfo = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC)
                    ?: throw IllegalStateException("No suitable codec found")
                val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val adjustedBitmap = adjustBitmapToSupportedSize(bitmap, capabilities.videoCapabilities)

                val width = adjustedBitmap.width
                val height = adjustedBitmap.height
                val frameRate = 30
                val bitRate = 2_000_000

                val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                encoder = MediaCodec.createByCodecName(codecInfo.name).apply {
                    configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }

                val inputSurface = encoder!!.createInputSurface()
                encoder!!.start()

                eglHelper = EglHelper().apply {
                    createEglContext(inputSurface, width, height)
                    loadStaticBitmapTexture(adjustedBitmap)
                }

                val videoFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "video_${System.currentTimeMillis()}.mp4"
                )
                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val totalFrames = maxOf(2, kotlin.math.round(duration * frameRate).toInt())
                val ptsMono = MonotonicPts()

                for (frameIndex in 0 until totalFrames) {
                    val ptsUs = ptsMono.nextFrom((frameIndex * 1_000_000L) / frameRate)

                    eglHelper!!.drawStaticFrame()
                    eglHelper!!.setPresentationTime(ptsUs * 1000L)
                    eglHelper!!.swapBuffers()

                    // drain what’s available (non-blocking)
                    drainEncoderToMuxer(
                        encoder = encoder!!,
                        muxer = muxer!!,
                        state = muxState,
                        bufferInfo = bufferInfo,
                        timeoutUs = 0L,
                        untilEos = false
                    )
                }

                encoder!!.signalEndOfInputStream()

                // IMPORTANT: fully drain until EOS
                drainEncoderToMuxer(
                    encoder = encoder!!,
                    muxer = muxer!!,
                    state = muxState,
                    bufferInfo = bufferInfo,
                    timeoutUs = 10_000L,
                    untilEos = true
                )

                val outputPath = videoFile.absolutePath
                val media = buildMediaInfo(
                    durationMs = duration * 1000.0,
                    width = width.toDouble(),
                    height = height.toDouble(),
                    fps = frameRate.toDouble(),
                    format = "mp4",
                    sizeBytes = File(outputPath).length().toDouble(),
                    audioTracks = 0.0,
                    videoTracks = 1.0
                )
                result = makeResult(
                    ok = true,
                    operation = OperationType.CONVERTIMAGETOVIDEO,
                    type = MediaType.VIDEO,
                    inputUri = image,
                    outputUri = outputPath,
                    media = media
                )
            } catch (e: Exception) {
                result = makeErrorResult(
                    operation = OperationType.CONVERTIMAGETOVIDEO,
                    type = MediaType.VIDEO,
                    inputUri = image,
                    error = e
                )
            } finally {
                eglHelper?.release()
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                if (muxState.started) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
            result
        }
    }

    override fun mergeVideos(videos: Array<String>): Promise<MediaInfoResult> {
        return Promise.async {
            var result: MediaInfoResult
            var muxer: MediaMuxer? = null
            var isMuxerStarted = false
            var outputVideoTrackIndex = -1
            var outputAudioTrackIndex = -1
            val tempFiles = mutableListOf<String>()
            var timelineUs = 0L

            try {
                // Step 1: Analyze all videos
                val counts = mutableMapOf<String, Int>()
                val sigToProps = mutableMapOf<String, VideoProperties>()

                for (video in videos) {
                    val local = getLocalFilePath(video)
                    val sig = videoSignature(local)
                    counts[sig] = (counts[sig] ?: 0) + 1
                    sigToProps[sig] = getVideoProperties(local) // still used for width/height/fps target
                }
                val targetSig = counts.maxByOrNull { it.value }?.key
                    ?: throw IllegalArgumentException("No videos to merge")

                val targetProps = sigToProps[targetSig]!!
                // Step 2: Prepare videos (re-encode non-matching ones)
                val videosToMerge = mutableListOf<String>()
                for (video in videos) {
                    val local = getLocalFilePath(video)
                    val sig = videoSignature(local)

                    if (sig == targetSig) {
                        videosToMerge.add(local)
                    } else {
                        val reencodedPath = reencodeVideo(local, targetProps.width, targetProps.height, targetProps.frameRate)
                        videosToMerge.add(reencodedPath)
                        tempFiles.add(reencodedPath)
                    }
                }
                // Step 3: Output file + muxer
                val videoFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "merged_${System.currentTimeMillis()}.mp4"
                )

                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val outMuxer = muxer!!

                // Step 4: Track formats from first video
                val firstExtractor = MediaExtractor()
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                try {
                    firstExtractor.setDataSource(videosToMerge[0])
                    for (i in 0 until firstExtractor.trackCount) {
                        val format = firstExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") && videoFormat == null) videoFormat = format
                        else if (mime.startsWith("audio/") && audioFormat == null) audioFormat = format
                    }
                } finally {
                    firstExtractor.release()
                }

                // ✅ ADD TRACKS + START MUXER (this was missing)
                if (videoFormat != null) outputVideoTrackIndex = outMuxer.addTrack(videoFormat!!)
                if (audioFormat != null) outputAudioTrackIndex = outMuxer.addTrack(audioFormat!!)

                if (outputVideoTrackIndex == -1 && outputAudioTrackIndex == -1) {
                    throw IllegalArgumentException("No audio/video tracks found in first input")
                }

                outMuxer.start()
                isMuxerStarted = true

                // Step 5: Copy samples (separate extractors inside copyTrack)
                val vMono = MonotonicPts()
                val aMono = MonotonicPts()
                for (videoPath in videosToMerge) {
                    val vDur = if (outputVideoTrackIndex != -1) {
                        copyTrack(
                            srcPath = videoPath,
                            mimePrefix = "video/",
                            muxer = outMuxer,
                            outTrackIndex = outputVideoTrackIndex,
                            ptsOffsetUs = timelineUs,
                            mono = vMono
                        )
                    } else 0L

                    val aDur = if (outputAudioTrackIndex != -1) {
                        copyTrack(
                            srcPath = videoPath,
                            mimePrefix = "audio/",
                            muxer = outMuxer,
                            outTrackIndex = outputAudioTrackIndex,
                            ptsOffsetUs = timelineUs,
                            mono = aMono
                        )
                    } else 0L

                    timelineUs += maxOf(vDur, aDur)
                }
                val outputPath = videoFile.absolutePath
                val media = buildMediaInfo(
                    durationMs = if (timelineUs > 0) timelineUs / 1000.0 else null,
                    format = "mp4",
                    sizeBytes = File(outputPath).length().toDouble(),
                    audioTracks = if (outputAudioTrackIndex != -1) 1.0 else 0.0,
                    videoTracks = if (outputVideoTrackIndex != -1) 1.0 else 0.0
                )
                result = makeResult(
                    ok = true,
                    operation = OperationType.MERGEVIDEOS,
                    type = MediaType.VIDEO,
                    outputUri = outputPath,
                    media = media
                )
            } catch (e: Exception) {
                Log.e("MediaKit", "Merge failed", e)
                result = makeErrorResult(
                    operation = OperationType.MERGEVIDEOS,
                    type = MediaType.VIDEO,
                    error = e
                )
            } finally {
                try {
                    if (isMuxerStarted) muxer?.stop()
                } catch (_: Throwable) {}
                try {
                    muxer?.release()
                } catch (_: Throwable) {}

                for (tempFile in tempFiles) runCatching { File(tempFile).delete() }
            }
            result
        }
    }

    override fun watermarkVideo(video: String, watermark: String, position: String): Promise<MediaInfoResult> {
        return Promise.async {
            var result: MediaInfoResult
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var eglHelper: EglHelper? = null

            var muxerStarted = false
            var outVideoTrackIndex = -1
            var outAudioTrackIndex = -1

            try {
                val localVideoPath = getLocalFilePath(video)

                extractor = MediaExtractor().apply { setDataSource(localVideoPath) }

                // ---- Find tracks (video + audio format for passthrough) ----
                var videoTrackIndex = -1
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor!!.trackCount) {
                    val format = extractor!!.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && videoTrackIndex == -1) videoTrackIndex = i
                    if (mime.startsWith("audio/") && audioFormat == null) audioFormat = format
                }

                if (videoTrackIndex == -1) {
                    throw IllegalArgumentException("No video track found in $localVideoPath")
                }

                extractor!!.selectTrack(videoTrackIndex)
                extractor!!.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val inputFormat = extractor!!.getTrackFormat(videoTrackIndex)
                val width = inputFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, "KEY_WIDTH")
                val height = inputFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, "KEY_HEIGHT")
                val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Missing MIME")

                val frameRate = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 120)
                } else 30
                val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else null

                // ---- Encoder ----
                val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                val inputSurface = encoder!!.createInputSurface()
                encoder!!.start()

                // ---- EGL ----
                eglHelper = EglHelper().apply {
                    createEglContext(inputSurface, width, height)
                }

                // ---- Decoder (to EGL surface) ----
                val decoderSurface = eglHelper!!.getVideoSurface()
                decoder = MediaCodec.createDecoderByType(inputMime).apply {
                    configure(inputFormat, decoderSurface, null, 0)
                    start()
                }

                // ---- Muxer ----
                val outputFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "watermarked_video_${System.currentTimeMillis()}.mp4"
                )
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // Add audio track BEFORE muxer.start() (video track added later on format change)
                outAudioTrackIndex = if (audioFormat != null) muxer!!.addTrack(audioFormat!!) else -1

                // ---- Watermark overlay ----
                val watermarkBitmap = eglHelper!!.createTextBitmap(watermark, 64f, Color.WHITE)
                eglHelper!!.setOverlayBitmap(watermarkBitmap)
                val (posX, posY) = calculateWatermarkPosition(position, watermarkBitmap, width, height)

                // ---- Timing (normalize to 0 + monotonic) ----
                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()

                var sawDecoderEOS = false
                var signaledEncoderEOS = false
                var sawEncoderEOS = false

                var firstVideoPtsUs = -1L
                val renderPtsMono = MonotonicPts()

                var firstEncPtsUs = -1L
                val writePtsMono = MonotonicPts()

                val timeoutUs = 10_000L
                var eosDrainIdleRounds = 0

                while (!sawEncoderEOS) {
                    // ---- Feed decoder input ----
                    if (!sawDecoderEOS) {
                        val inIndex = decoder!!.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val inBuf = decoder!!.getInputBuffer(inIndex)!!
                            val size = extractor!!.readSampleData(inBuf, 0)

                            if (size < 0) {
                                decoder!!.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawDecoderEOS = true
                            } else {
                                val ptsUs = extractor!!.sampleTime
                                decoder!!.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
                                extractor!!.advance()
                            }
                        }
                    }

                    // ---- Drain decoder output -> render -> encoder surface ----
                    var decoderOut = true
                    while (decoderOut) {
                        val outIndex = decoder!!.dequeueOutputBuffer(decInfo, timeoutUs)
                        when (outIndex) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOut = false
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            else -> if (outIndex >= 0) {
                                val doRender = shouldRenderDecodedToSurface(decInfo)
                                decoder!!.releaseOutputBuffer(outIndex, doRender)

                                if (doRender) {
                                    if (firstVideoPtsUs < 0) firstVideoPtsUs = decInfo.presentationTimeUs
                                    val normPtsUs = maxOf(0L, decInfo.presentationTimeUs - firstVideoPtsUs)
                                    val safePtsUs = renderPtsMono.nextFrom(normPtsUs)

                                    eglHelper!!.drawFrameWithOverlay(posX, posY, videoWidth = width, videoHeight = height)
                                    eglHelper!!.setPresentationTime(safePtsUs * 1000L)
                                    eglHelper!!.swapBuffers()
                                }

                                val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                if (eos && !signaledEncoderEOS) {
                                    encoder!!.signalEndOfInputStream()
                                    signaledEncoderEOS = true
                                    decoderOut = false
                                }
                            }
                        }
                    }

                    // ---- Drain encoder output -> muxer ----
                    var encoderOut = true
                    while (encoderOut) {
                        val outIndex = encoder!!.dequeueOutputBuffer(encInfo, timeoutUs)
                        when (outIndex) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (signaledEncoderEOS) {
                                    eosDrainIdleRounds++
                                    if (eosDrainIdleRounds >= 50) encoderOut = false
                                } else {
                                    encoderOut = false
                                }
                            }

                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (muxerStarted) throw RuntimeException("Encoder format changed twice")
                                outVideoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }

                            else -> if (outIndex >= 0) {
                                eosDrainIdleRounds = 0

                                val encodedData = encoder!!.getOutputBuffer(outIndex)
                                    ?: throw RuntimeException("Encoder output buffer $outIndex was null")

                                val isCodecConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                                if (isCodecConfig) {
                                    encoder!!.releaseOutputBuffer(outIndex, false)
                                    continue
                                }

                                if (muxerStarted && encInfo.size > 0) {
                                    encodedData.position(encInfo.offset)
                                    encodedData.limit(encInfo.offset + encInfo.size)

                                    if (firstEncPtsUs < 0) firstEncPtsUs = encInfo.presentationTimeUs
                                    val normPtsUs = maxOf(0L, encInfo.presentationTimeUs - firstEncPtsUs)
                                    val safePtsUs = writePtsMono.nextFrom(normPtsUs)

                                    val writeInfo = MediaCodec.BufferInfo().apply {
                                        set(encInfo.offset, encInfo.size, safePtsUs, encInfo.flags)
                                    }

                                    muxer!!.writeSampleData(outVideoTrackIndex, encodedData, writeInfo)
                                }

                                encoder!!.releaseOutputBuffer(outIndex, false)

                                if (isEos) {
                                    sawEncoderEOS = true
                                    encoderOut = false
                                }
                            }
                        }
                    }
                }

                // ---- Copy audio (passthrough) ----
                if (muxerStarted && outAudioTrackIndex != -1) {
                    copyTrack(
                        srcPath = localVideoPath,
                        mimePrefix = "audio/",
                        muxer = muxer!!,
                        outTrackIndex = outAudioTrackIndex,
                        ptsOffsetUs = 0L,
                        mono = MonotonicPts()
                    )
                }

                val outputPath = outputFile.absolutePath
                val media = buildMediaInfo(
                    durationMs = durationUs?.let { it / 1000.0 },
                    width = width.toDouble(),
                    height = height.toDouble(),
                    fps = frameRate.toDouble(),
                    format = "mp4",
                    sizeBytes = File(outputPath).length().toDouble(),
                    audioTracks = if (audioFormat != null) 1.0 else 0.0,
                    videoTracks = 1.0
                )
                result = makeResult(
                    ok = true,
                    operation = OperationType.WATERMARKVIDEO,
                    type = MediaType.VIDEO,
                    inputUri = video,
                    outputUri = outputPath,
                    media = media
                )
            } catch (e: Exception) {
                result = makeErrorResult(
                    operation = OperationType.WATERMARKVIDEO,
                    type = MediaType.VIDEO,
                    inputUri = video,
                    error = e
                )
            } finally {
                runCatching { extractor?.release() }
                runCatching { decoder?.stop(); decoder?.release() }
                runCatching { encoder?.stop(); encoder?.release() }
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { eglHelper?.release() }
            }
            result
        }
    }


    private fun reencodeVideo(
        inputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFrameRate: Float
    ): String {
        var videoExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglHelper: EglHelper? = null

        val bufferInfo = MediaCodec.BufferInfo()
        val muxState = MuxerTrackState()

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(inputPath) }

            var videoTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor!!.trackCount) {
                val format = videoExtractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) videoTrackIndex = i
                if (mime.startsWith("audio/") && audioFormat == null) audioFormat = format
            }
            if (videoTrackIndex == -1) throw IllegalArgumentException("No video track in $inputPath")

            videoExtractor!!.selectTrack(videoTrackIndex)
            videoExtractor!!.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val inputFormat = videoExtractor!!.getTrackFormat(videoTrackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            val outputFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                targetWidth,
                targetHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate.toInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder!!.createInputSurface()
            encoder!!.start()

            eglHelper = EglHelper().apply {
                createEglContext(inputSurface, targetWidth, targetHeight)
            }
            val decoderSurface = eglHelper!!.getVideoSurface()

            decoder = MediaCodec.createDecoderByType(inputMime).apply {
                configure(inputFormat, decoderSurface, null, 0)
                start()
            }

            val outputFile = File(
                applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "reencoded_${System.currentTimeMillis()}.mp4"
            )
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Add audio track BEFORE muxer.start() (muxer starts later on encoder format change)
            val outAudioTrackIndex = if (audioFormat != null) muxer!!.addTrack(audioFormat!!) else -1

            val timeoutUs = 10_000L
            var sawDecoderEOS = false
            var signaledEncoderEOS = false

            var firstDecoderPtsUs = -1L
            val ptsMono = MonotonicPts()

            while (true) {
                // ---- Feed decoder input ----
                if (!sawDecoderEOS) {
                    val inIndex = decoder!!.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = decoder!!.getInputBuffer(inIndex)!!
                        val size = videoExtractor!!.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder!!.queueInputBuffer(
                                inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawDecoderEOS = true
                        } else {
                            val pts = videoExtractor!!.sampleTime
                            decoder!!.queueInputBuffer(inIndex, 0, size, pts, 0)
                            videoExtractor!!.advance()
                        }
                    }
                }

                // ---- Drain decoder output -> render -> encoder surface ----
                var decoderOut = true
                while (decoderOut) {
                    val outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when (outIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOut = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIndex >= 0) {
                            val doRender = shouldRenderDecodedToSurface(bufferInfo)
                            decoder!!.releaseOutputBuffer(outIndex, doRender)

                            if (doRender) {
                                if (firstDecoderPtsUs < 0) firstDecoderPtsUs = bufferInfo.presentationTimeUs
                                val normPtsUs = maxOf(0L, bufferInfo.presentationTimeUs - firstDecoderPtsUs)
                                val safePtsUs = ptsMono.nextFrom(normPtsUs)

                                // ✅ NO OVERLAY HERE
                                eglHelper!!.drawVideoFrame()
                                eglHelper!!.setPresentationTime(safePtsUs * 1000L)
                                eglHelper!!.swapBuffers()
                            }

                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (eos && !signaledEncoderEOS) {
                                encoder!!.signalEndOfInputStream()
                                signaledEncoderEOS = true
                                decoderOut = false
                            }
                        }
                    }
                }

                // ---- Drain encoder output (non-blocking) ----
                drainEncoderToMuxer(
                    encoder = encoder!!,
                    muxer = muxer!!,
                    state = muxState,
                    bufferInfo = bufferInfo,
                    timeoutUs = 0L,
                    untilEos = false
                )

                // Once we signaled EOS to encoder, we can break and do a full EOS drain
                if (signaledEncoderEOS) break
            }

            // ---- Final: drain encoder until EOS ----
            drainEncoderToMuxer(
                encoder = encoder!!,
                muxer = muxer!!,
                state = muxState,
                bufferInfo = bufferInfo,
                timeoutUs = timeoutUs,
                untilEos = true
            )

            // ---- Copy audio after muxer has started ----
            if (muxState.started && outAudioTrackIndex != -1) {
                copyTrack(
                    srcPath = inputPath,
                    mimePrefix = "audio/",
                    muxer = muxer!!,
                    outTrackIndex = outAudioTrackIndex,
                    ptsOffsetUs = 0L,
                    mono = MonotonicPts()
                )
            }

            return outputFile.absolutePath
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
            if (muxState.started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { eglHelper?.release() }
        }
    }
    // Extensions to handle missing keys in MediaFormat
    private fun MediaFormat.getIntegerOrDefault(key: String, keyName: String, defaultValue: Int = 0): Int {
        return if (containsKey(key)) {
            getInteger(key)
        } else {
            Log.w("HybridMediaKit", "$keyName is missing; defaulting to $defaultValue")
            defaultValue
        }
    }

    private fun csdHash(format: MediaFormat, key: String): Pair<Int, Int> {
        if (!format.containsKey(key)) return 0 to 0
        val bb = format.getByteBuffer(key) ?: return 0 to 0
        val dup = bb.duplicate()
        dup.clear()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes.contentHashCode() to bytes.size
    }

    private fun videoSignature(path: String): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("video/")) continue

                val w = f.getInteger(MediaFormat.KEY_WIDTH)
                val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
                val (h0, s0) = csdHash(f, "csd-0")
                val (h1, s1) = csdHash(f, "csd-1")

                val prof = if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else -1
                val lvl  = if (f.containsKey(MediaFormat.KEY_LEVEL)) f.getInteger(MediaFormat.KEY_LEVEL) else -1

                return "$mime|${w}x$h@$fps|csd0=$h0:$s0|csd1=$h1:$s1|p=$prof|l=$lvl"
            }
            throw IllegalArgumentException("No video track found in $path")
        } finally {
            ex.release()
        }
    }

    private fun MediaFormat.getLongOrDefault(key: String, keyName: String, defaultValue: Long = 0L): Long {
        return if (containsKey(key)) {
            getLong(key)
        } else {
            Log.w("HybridMediaKit", "$keyName is missing; defaulting to $defaultValue")
            defaultValue
        }
    }

    private fun shouldRenderDecodedToSurface(info: MediaCodec.BufferInfo): Boolean {
        // Only safe "no-frame" case we skip: the empty EOS buffer.
        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        if (eos && info.size == 0) return false
        // Some codecs can also emit codec-config buffers; never render those.
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return false
        return true
    }

    private fun getVideoProperties(videoPath: String): VideoProperties {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    // Frame rate might not always be present; default to 30 if absent
                    val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } else {
                        30f
                    }
                    return VideoProperties(width, height, frameRate)
                }
            }
            throw IllegalArgumentException("No video track found in $videoPath")
        } finally {
            extractor.release()
        }
    }

    data class VideoProperties(val width: Int, val height: Int, val frameRate: Float)

    private fun calculateWatermarkPosition(
        position: String,
        watermarkBitmap: Bitmap,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float> {
        var posX = 0f   // give them a default so they’re always initialised
        var posY = 0f

        when (position.lowercase()) {
            "top-left" -> {
                posX = 0f
                posY = (videoHeight - watermarkBitmap.height).toFloat()
            }
            "top-right" -> {
                posX = (videoWidth  - watermarkBitmap.width ).toFloat()
                posY = (videoHeight - watermarkBitmap.height).toFloat()
            }
            "bottom-left" -> {
                posX = 0f
                posY = 0f
            }
            "bottom-right" -> {
                posX = (videoWidth  - watermarkBitmap.width ).toFloat()
                posY = 0f
            }
            else -> {
                // centre by default
                posX = ((videoWidth  - watermarkBitmap.width )  / 2f)
                posY = ((videoHeight - watermarkBitmap.height) / 2f)
            }
        }

        return Pair(posX, posY)
    }

    private fun adjustBitmapToSupportedSize(
        bitmap: Bitmap,
        videoCapabilities: VideoCapabilities
    ): Bitmap {
        val widthAlignment = videoCapabilities.widthAlignment
        val heightAlignment = videoCapabilities.heightAlignment

        val standardResolutions = listOf(
            Pair(1920, 1080),
            Pair(1280, 720),
            Pair(640, 480)
        )

        val aspectRatio = bitmap.width.toFloat() / bitmap.height

        for ((stdWidth, stdHeight) in standardResolutions) {
            var width = stdWidth - (stdWidth % widthAlignment)
            var height = stdHeight - (stdHeight % heightAlignment)

            // Adjust dimensions to maintain aspect ratio
            if (aspectRatio >= 1) {
                // Landscape orientation
                height = (width / aspectRatio).toInt()
                height -= height % heightAlignment
            } else {
                // Portrait orientation
                width = (height * aspectRatio).toInt()
                width -= width % widthAlignment
            }

            // Ensure dimensions are within encoder's supported ranges
            if (videoCapabilities.isSizeSupported(width, height)) {
                Log.d("HybridMediaKit", "Using standard resolution: ${width}x${height}")
                return Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
        }

        throw IllegalArgumentException("No supported standard resolution found")
    }


    private fun selectCodec(mimeType: String): MediaCodecInfo? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfos = codecList.codecInfos
        for (info in codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.contains(mimeType)) {
                return info
            }
        }
        return null
    }

    private fun copyTrack(
        srcPath: String,
        mimePrefix: String,
        muxer: MediaMuxer,
        outTrackIndex: Int,
        ptsOffsetUs: Long,
        mono: MonotonicPts
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(srcPath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith(mimePrefix)) { trackIndex = i; format = f; break }
            }
            if (trackIndex == -1 || format == null) return 0L

            extractor.selectTrack(trackIndex)

            // For VIDEO: make sure we start on a sync sample (prevents black flashes at joins)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            if (mimePrefix == "video/") {
                while ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                    if (!extractor.advance()) return 0L
                }
            }

            val cap = if (format!!.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                maxOf(format!!.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 2 * 1024 * 1024)
            else
                4 * 1024 * 1024

            val buffer = java.nio.ByteBuffer.allocateDirect(cap)
            val info = MediaCodec.BufferInfo()

            var firstPtsUs = -1L
            val ptsTracker = PtsTracker()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val samplePts = extractor.sampleTime
                if (firstPtsUs < 0) firstPtsUs = samplePts
                ptsTracker.record(samplePts)

                info.offset = 0
                info.size = size
                info.flags = extractor.sampleFlags

                val cand = (samplePts - firstPtsUs) + ptsOffsetUs
                info.presentationTimeUs = mono.nextFrom(maxOf(0L, cand))

                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outTrackIndex, buffer, info)

                extractor.advance()
            }

            if (firstPtsUs < 0) return 0L
            val durUs = (ptsTracker.last - firstPtsUs) + ptsTracker.estimatedLastSampleDuration()
            return maxOf(0L, durUs)
        } finally {
            extractor.release()
        }
    }

    private fun downloadFile(urlString: String): String {
        val url = URL(urlString)

        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NitroMediaKit/1.0 (Android)")
        }

        try {
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                throw IOException("Download failed: HTTP $code ${connection.responseMessage}${if (!err.isNullOrBlank()) " - $err" else ""}")
            }

            // Safer filename
            val rawName = url.path.substringAfterLast('/').ifBlank { "temp_${System.currentTimeMillis()}" }
            val file = File(applicationContext.cacheDir, rawName)

            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            return file.absolutePath
        } finally {
            connection.disconnect()
        }
    }
}   
