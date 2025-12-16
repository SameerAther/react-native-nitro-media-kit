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
import com.margelo.nitro.core.AnyMap
import com.margelo.nitro.core.AnyValue
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

    override fun convertImageToVideo(image: String, duration: Double): Promise<String> {
        return Promise.async {
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var eglHelper: EglHelper? = null
            var isMuxerStarted = false
            try {
                val localImagePath = getLocalFilePath(image)
                val bitmap = BitmapFactory.decodeFile(localImagePath)
                    ?: throw IOException("Cannot decode image at path: $localImagePath")

                Log.d("HybridMediaKit", "Original bitmap dimensions: ${bitmap.width}x${bitmap.height}")

                // Validate codec capabilities
                val codecInfo = selectCodec(MediaFormat.MIMETYPE_VIDEO_AVC)
                    ?: throw IllegalStateException("No suitable codec found")
                val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoCapabilities = capabilities.videoCapabilities

                // Adjust bitmap to supported size
                val adjustedBitmap = adjustBitmapToSupportedSize(bitmap, videoCapabilities)
                Log.d("HybridMediaKit", "Adjusted bitmap dimensions: ${adjustedBitmap.width}x${adjustedBitmap.height}")

                val width = adjustedBitmap.width
                val height = adjustedBitmap.height
                val frameRate = 30
                val bitRate = 2000000

                val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                encoder = MediaCodec.createByCodecName(codecInfo.name)
                encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                val inputSurface = encoder.createInputSurface()
                encoder.start()
                eglHelper = EglHelper()
                eglHelper.createEglContext(inputSurface, width, height)
                val videoFileName = "video_${System.currentTimeMillis()}.mp4"
                val videoFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    videoFileName
                )
                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                eglHelper.loadStaticBitmapTexture(adjustedBitmap) 
                val totalFrames = maxOf(2, kotlin.math.round(duration * frameRate).toInt())
                var videoTrackIndex = -1
                val bufferInfo = MediaCodec.BufferInfo()
                isMuxerStarted = false
                val monoPts = MonotonicPts()
                for (frameIndex in 0 until totalFrames) {
                    val ptsUs = monoPts.nextFrom((frameIndex * 1_000_000L) / frameRate)
                    eglHelper.drawStaticFrame()
                    eglHelper.setPresentationTime(ptsUs * 1000)      // ns
                    eglHelper.swapBuffers()

                    // Drain encoder output
                    while (true) {
                        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = encoder.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                        } else if (outputBufferIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
                encoder.signalEndOfInputStream()

                while (true) {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    when (outputBufferIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = encoder.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }
                        else -> if (outputBufferIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                            if (encodedData != null && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }

                videoFile.absolutePath
            } catch (e: Exception) {
                Log.e("HybridMediaKit", "Error converting image to video: ${e.message}")
                throw e
            } finally {
                eglHelper?.release()
                try { if (isMuxerStarted) muxer?.stop() } catch (_: Throwable) {}
                muxer?.release()
                try { encoder?.stop() } catch (_: Throwable) {}
                encoder?.release()
            }
        }
    }

    override fun mergeVideos(videos: Array<String>): Promise<String> {
        return Promise.async {
            var muxer: MediaMuxer? = null
            var isMuxerStarted = false
            var outputVideoTrackIndex = -1
            var outputAudioTrackIndex = -1
            val tempFiles = mutableListOf<String>()

            try {
                // Step 1: Analyze all videos
                val propertiesCounts = mutableMapOf<VideoProperties, Int>()
                for (video in videos) {
                    val localVideoPath = getLocalFilePath(video)
                    val props = getVideoProperties(localVideoPath)
                    propertiesCounts[props] = propertiesCounts.getOrDefault(props, 0) + 1
                }
                val targetProps = propertiesCounts.maxByOrNull { it.value }?.key
                    ?: throw IllegalArgumentException("No videos to merge")

                // Step 2: Prepare videos (re-encode non-matching ones)
                val videosToMerge = mutableListOf<String>()
                for (video in videos) {
                    val localVideoPath = getLocalFilePath(video)
                    val props = getVideoProperties(localVideoPath)
                    if (props == targetProps) {
                        videosToMerge.add(localVideoPath)
                    } else {
                        val reencodedPath = reencodeVideo(
                            localVideoPath,
                            targetProps.width,
                            targetProps.height,
                            targetProps.frameRate
                        )
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
                var videoOffsetUs = 0L
                var audioOffsetUs = 0L

                for (videoPath in videosToMerge) {
                    if (outputVideoTrackIndex != -1) {
                        videoOffsetUs += copyTrack(
                            srcPath = videoPath,
                            mimePrefix = "video/",
                            muxer = outMuxer,
                            outTrackIndex = outputVideoTrackIndex,
                            ptsOffsetUs = videoOffsetUs,
                            mono = vMono
                        )
                    }
                    if (outputAudioTrackIndex != -1) {
                        audioOffsetUs += copyTrack(
                            srcPath = videoPath,
                            mimePrefix = "audio/",
                            muxer = outMuxer,
                            outTrackIndex = outputAudioTrackIndex,
                            ptsOffsetUs = audioOffsetUs,
                            mono = aMono
                        )
                    }
                }

                videoFile.absolutePath
            } catch (e: Exception) {
                Log.e("MediaKit", "Merge failed", e)
                throw e
            } finally {
                try {
                    if (isMuxerStarted) muxer?.stop()
                } catch (_: Throwable) {}
                try {
                    muxer?.release()
                } catch (_: Throwable) {}

                for (tempFile in tempFiles) runCatching { File(tempFile).delete() }
            }
        }
    }

    override fun watermarkVideo(video: String, watermark: String, position: String): Promise<String> {
        return Promise.async {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var eglHelper: EglHelper? = null

            try {
                Log.d("HybridMediaKit", "Starting watermarkVideo with video=$video, watermark=$watermark, position=$position")

                // Get local video path
                val localVideoPath = getLocalFilePath(video)
                Log.d("HybridMediaKit", "Local video path: $localVideoPath")

                extractor = MediaExtractor()
                extractor.setDataSource(localVideoPath)

                // Find video track
                var videoTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    Log.d("HybridMediaKit", "Track $i MIME type: $mime")
                    if (mime.startsWith("video/") && videoTrackIndex == -1) {
                        videoTrackIndex = i
                        Log.d("HybridMediaKit", "Selected video track index: $videoTrackIndex")
                    }
                }

                if (videoTrackIndex == -1) {
                    throw IllegalArgumentException("No video track found in $localVideoPath")
                }

                // Set up decoder
                extractor.selectTrack(videoTrackIndex)
                val inputFormat = extractor.getTrackFormat(videoTrackIndex)
                val width = inputFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, "KEY_WIDTH")
                val height = inputFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, "KEY_HEIGHT")
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)

                // Set up encoder
                val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2000000) // Default bit rate
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30) // Default frame rate
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()
                Log.d("HybridMediaKit", "Encoder configured and input surface created successfully")

                eglHelper = EglHelper()
                eglHelper.createEglContext(inputSurface, width, height) // use decoded width/height

                // Use EglHelper's video surface for the decoder
                val decoderSurface = eglHelper.getVideoSurface()
                decoder = MediaCodec.createDecoderByType(mime!!)
                decoder.configure(inputFormat, decoderSurface, null, 0)
                decoder.start()
                Log.d("HybridMediaKit", "Decoder configured successfully")

                // Set up muxer
                val outputFileName = "watermarked_video_${System.currentTimeMillis()}.mp4"
                val outputFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    outputFileName
                )
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                Log.d("HybridMediaKit", "Muxer initialized at path: ${outputFile.absolutePath}")

                // Prepare watermark bitmap
                val watermarkBitmap = eglHelper.createTextBitmap(watermark, 64f, Color.WHITE)
                eglHelper.setOverlayBitmap(watermarkBitmap) // Load the bitmap into the texture
                val (posX, posY) = calculateWatermarkPosition(position, watermarkBitmap, width, height)
                Log.d("HybridMediaKit", "Watermark bitmap created at position: ($posX, $posY)")

                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10000L
                var sawDecoderEOS = false
                var sawEncoderEOS = false
                var isMuxerStarted = false
                var outputVideoTrackIndex = -1
                val monoPts = MonotonicPts()
                while (!sawEncoderEOS) {
                    // Feed decoder input
                    if (!sawDecoderEOS) {
                        val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawDecoderEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    // Drain decoder output
                    var decoderOutputAvailable = true
                    while (decoderOutputAvailable) {
                        val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            decoderOutputAvailable = false
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // Handle format change
                        } else if (outputBufferIndex >= 0) {
                            val doRender = shouldRenderDecodedToSurface(bufferInfo)
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                            if (doRender) {
                                eglHelper.drawFrameWithOverlay(posX, posY, videoWidth = width, videoHeight = height)
                                val ptsUs = monoPts.nextFrom(bufferInfo.presentationTimeUs) // <- decoder PTS (good)
                                eglHelper.setPresentationTime(ptsUs * 1000L)
                                eglHelper.swapBuffers()
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoder.signalEndOfInputStream()
                                decoderOutputAvailable = false
                            }
                        }
                    }

                    // Drain encoder output
                    var encoderOutputAvailable = true
                    while (encoderOutputAvailable) {
                        val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)

                        when (outIndex) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                encoderOutputAvailable = false
                            }

                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (isMuxerStarted) throw RuntimeException("Format changed twice")

                                val newFormat = encoder.outputFormat
                                outputVideoTrackIndex = muxer!!.addTrack(newFormat)
                                muxer!!.start()
                                isMuxerStarted = true
                            }

                            else -> if (outIndex >= 0) {
                                val encodedData = encoder.getOutputBuffer(outIndex)
                                    ?: throw RuntimeException("Encoder output buffer $outIndex was null")

                                // ✅ Skip codec config buffers (muxer gets CSD from INFO_OUTPUT_FORMAT_CHANGED)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    encoder.releaseOutputBuffer(outIndex, false)
                                    continue
                                }

                                // ✅ Safety: don't write before muxer is started
                                if (!isMuxerStarted) {
                                    encoder.releaseOutputBuffer(outIndex, false)
                                    continue
                                }

                                if (bufferInfo.size > 0) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer!!.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                                }

                                encoder.releaseOutputBuffer(outIndex, false)

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    sawEncoderEOS = true
                                    encoderOutputAvailable = false
                                }
                            }
                        }
                    }
                }

                Log.d("HybridMediaKit", "Watermarking completed. Output file: ${outputFile.absolutePath}")
                outputFile.absolutePath
            } catch (e: Exception) {
                Log.e("HybridMediaKit", "Error applying watermark", e)
                throw e
            } finally {
                try {
                    extractor?.release()
                    decoder?.stop()
                    decoder?.release()
                    encoder?.stop()
                    encoder?.release()
                    muxer?.stop()
                    muxer?.release()
                    eglHelper?.release()
                } catch (e: Exception) {
                    Log.e("HybridMediaKit", "Error releasing resources", e)
                }
            }
        }
    }

    private fun reencodeVideo(inputPath: String, targetWidth: Int, targetHeight: Int, targetFrameRate: Float): String {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglHelper: EglHelper? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex == -1) throw IllegalArgumentException("No video track in $inputPath")

            extractor.selectTrack(videoTrackIndex)
            val inputFormat = extractor.getTrackFormat(videoTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            // Encoder setup
            val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate.toInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // EGL setup
            eglHelper = EglHelper()
            eglHelper.createEglContext(inputSurface, targetWidth, targetHeight)
            val decoderSurface = eglHelper.getVideoSurface()

            // Decoder setup
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, decoderSurface, null, 0)
            decoder.start()

            // Muxer setup
            val outputFile = File(
                applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "reencoded_${System.currentTimeMillis()}.mp4"
            )
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L
            var sawDecoderEOS = false
            var sawEncoderEOS = false
            var isMuxerStarted = false
            var outputVideoTrackIndex = -1
            val monoPts = MonotonicPts()
            while (!sawEncoderEOS) {
                if (!sawDecoderEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawDecoderEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var decoderOutputAvailable = true
                while (decoderOutputAvailable) {
                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when (outputBufferIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                        else -> if (outputBufferIndex >= 0) {
                            val doRender = shouldRenderDecodedToSurface(bufferInfo)
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                            if (doRender) {
                                eglHelper.drawVideoFrame()
                                val ptsUs = monoPts.nextFrom(bufferInfo.presentationTimeUs) // <- decoder PTS
                                eglHelper.setPresentationTime(ptsUs * 1000L)
                                eglHelper.swapBuffers()
                            }
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoder.signalEndOfInputStream()
                                decoderOutputAvailable = false
                            }
                        }
                    }
                }

                var encoderOutputAvailable = true
                while (encoderOutputAvailable) {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when (outputBufferIndex) {

                    MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                    else -> if (outputBufferIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outputBufferIndex)!!

                        // ✅ Skip codec config
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                            continue
                        }

                        if (bufferInfo.size > 0) {
                            // ✅ Always reset ByteBuffer range before writeSampleData
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawEncoderEOS = true
                            encoderOutputAvailable = false
                        }
                    }
                    }
                }
            }
            return outputFile.absolutePath
        } catch (e: Exception) {
            Log.e("MediaKit", "Re-encoding failed", e)
            throw e
        } finally {
            extractor?.release()
            decoder?.stop()
            decoder?.release()
            encoder?.stop()
            encoder?.release()
            muxer?.stop()
            muxer?.release()
            eglHelper?.release()
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
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // Safer than fixed 1MB. Many files have KEY_MAX_INPUT_SIZE; otherwise pick a bigger fallback.
            val cap = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 2 * 1024 * 1024)
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