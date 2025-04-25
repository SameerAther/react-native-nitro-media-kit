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

    private suspend fun getLocalFilePath(pathOrUrl: String): String {
        return withContext(Dispatchers.IO) {
            if (isRemoteUrl(pathOrUrl)) {
                // Download the remote file and return the local path
                downloadFile(pathOrUrl)
            } else {
                // It's a local file path
                pathOrUrl
            }
        }
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

                val videoFileName = "video_${System.currentTimeMillis()}.mp4"
                val videoFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    videoFileName
                )
                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                eglHelper = EglHelper()
                eglHelper.createEglContext(inputSurface)

                val totalFrames = (duration * frameRate).toInt()
                var videoTrackIndex = -1
                val bufferInfo = MediaCodec.BufferInfo()

                for (frameIndex in 0 until totalFrames) {
                    val presentationTimeUs = (frameIndex * 1_000_000L) / frameRate

                    eglHelper.drawFrame(adjustedBitmap)
                    eglHelper.setPresentationTime(presentationTimeUs * 1000) // In nanoseconds
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

                // Signal end of stream
                eglHelper.setPresentationTime(totalFrames * 1_000_000L / frameRate * 1000)
                eglHelper.swapBuffers()
                encoder.signalEndOfInputStream()

                // Drain any remaining output
                while (true) {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
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

                videoFile.absolutePath
            } catch (e: Exception) {
                Log.e("HybridMediaKit", "Error converting image to video: ${e.message}")
                throw e
            } finally {
                eglHelper?.release()
                muxer?.stop()
                muxer?.release()
                encoder?.stop()
                encoder?.release()
            }
        }
    }

    override fun mergeVideos(videos: Array<String>): Promise<String> {
        return Promise.async {
            var muxer: MediaMuxer? = null
            var outputVideoTrackIndex = -1
            var outputAudioTrackIndex = -1
            var videoPresentationTimeUsOffset = 0L
            var audioPresentationTimeUsOffset = 0L
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

                // Step 3: Set up output file and muxer
                val videoFile = File(
                    applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "merged_${System.currentTimeMillis()}.mp4"
                )
                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // Step 4: Get track formats from the first video
                val firstExtractor = MediaExtractor()
                firstExtractor.setDataSource(videosToMerge[0])
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                for (i in 0 until firstExtractor.trackCount) {
                    val format = firstExtractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && videoFormat == null) videoFormat = format
                    else if (mime.startsWith("audio/") && audioFormat == null) audioFormat = format
                }
                firstExtractor.release()

                if (videoFormat != null) outputVideoTrackIndex = muxer.addTrack(videoFormat)
                if (audioFormat != null) outputAudioTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                // Step 5: Merge videos
                for (videoPath in videosToMerge) {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(videoPath)

                    var srcVideoTrackIndex = -1
                    var srcAudioTrackIndex = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") && srcVideoTrackIndex == -1) srcVideoTrackIndex = i
                        else if (mime.startsWith("audio/") && srcAudioTrackIndex == -1) srcAudioTrackIndex = i
                    }

                    // Video track
                    if (srcVideoTrackIndex != -1 && outputVideoTrackIndex != -1) {
                        extractor.selectTrack(srcVideoTrackIndex)
                        val buffer = ByteBuffer.allocate(1024 * 1024)
                        val bufferInfo = MediaCodec.BufferInfo()
                        var firstVideoSampleTimeUs = -1L
                        var lastVideoSampleTimeUs = 0L
                        while (true) {
                            bufferInfo.size = extractor.readSampleData(buffer, 0)
                            if (bufferInfo.size < 0) break
                            val sampleTimeUs = extractor.sampleTime
                            if (firstVideoSampleTimeUs == -1L) firstVideoSampleTimeUs = sampleTimeUs
                            bufferInfo.presentationTimeUs = sampleTimeUs - firstVideoSampleTimeUs + videoPresentationTimeUsOffset
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(outputVideoTrackIndex, buffer, bufferInfo)
                            extractor.advance()
                            lastVideoSampleTimeUs = sampleTimeUs
                        }
                        val trackDurationUs = lastVideoSampleTimeUs - firstVideoSampleTimeUs
                        videoPresentationTimeUsOffset += trackDurationUs // No additional gap
                        extractor.unselectTrack(srcVideoTrackIndex)
                    }

                    // Audio track
                    if (srcAudioTrackIndex != -1 && outputAudioTrackIndex != -1) {
                        extractor.selectTrack(srcAudioTrackIndex)
                        val buffer = ByteBuffer.allocate(1024 * 1024)
                        val bufferInfo = MediaCodec.BufferInfo()
                        var firstAudioSampleTimeUs = -1L
                        var lastAudioSampleTimeUs = 0L
                        while (true) {
                            bufferInfo.size = extractor.readSampleData(buffer, 0)
                            if (bufferInfo.size < 0) break
                            val sampleTimeUs = extractor.sampleTime
                            if (firstAudioSampleTimeUs == -1L) firstAudioSampleTimeUs = sampleTimeUs
                            bufferInfo.presentationTimeUs = sampleTimeUs - firstAudioSampleTimeUs + audioPresentationTimeUsOffset
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(outputAudioTrackIndex, buffer, bufferInfo)
                            extractor.advance()
                            lastAudioSampleTimeUs = sampleTimeUs
                        }
                        val trackDurationUs = lastAudioSampleTimeUs - firstAudioSampleTimeUs
                        audioPresentationTimeUsOffset += trackDurationUs // No additional gap
                        extractor.unselectTrack(srcAudioTrackIndex)
                    }
                    extractor.release()
                }

                muxer.stop()
                muxer.release()
                muxer = null
                videoFile.absolutePath
            } catch (e: Exception) {
                Log.e("MediaKit", "Merge failed", e)
                throw e
            } finally {
                muxer?.stop()
                muxer?.release()
                for (tempFile in tempFiles) File(tempFile).delete()
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

                // The input surface must be created only after the encoder is properly configured
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()
                Log.d("HybridMediaKit", "Encoder configured and input surface created successfully")

                eglHelper = EglHelper()
                eglHelper.createEglContext(inputSurface)

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
                            val doRender = bufferInfo.size != 0
                            // Release the output buffer first
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                            if (doRender) {
                                // Draw the frame after it's available
                                val timestamp = eglHelper.drawFrameWithOverlay(
                                    posX = posX,
                                    posY = posY,
                                    videoWidth = width,
                                    videoHeight = height
                                )
                                eglHelper.setPresentationTime(timestamp)
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
                        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            encoderOutputAvailable = false
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (isMuxerStarted) {
                                throw RuntimeException("Format changed twice")
                            }
                            val newFormat = encoder.outputFormat
                            outputVideoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            isMuxerStarted = true
                        } else if (outputBufferIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                                ?: throw RuntimeException("Encoder output buffer $outputBufferIndex was null")

                            if (bufferInfo.size > 0) {
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
                setFloat(MediaFormat.KEY_FRAME_RATE, targetFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // EGL setup
            eglHelper = EglHelper()
            eglHelper.createEglContext(inputSurface)
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
                            val doRender = bufferInfo.size != 0
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                            if (doRender) {
                                val timestamp = eglHelper.drawVideoFrame()
                                eglHelper.setPresentationTime(timestamp)
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
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (isMuxerStarted) throw RuntimeException("Format changed twice")
                            outputVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }
                        else -> if (outputBufferIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outputBufferIndex)!!
                            if (bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(outputVideoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
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
        val posX: Float
        val posY: Float
        when (position.toLowerCase()) {
            "top-left" -> {
                posX = 0f
                posY = videoHeight - watermarkBitmap.height.toFloat()
            }
            "top-right" -> {
                posX = videoWidth - watermarkBitmap.width.toFloat()
                posY = videoHeight - watermarkBitmap.height.toFloat()
            }
            "bottom-left" -> {
                posX = 0f
                posY = 0f
            }
            "bottom-right" -> {
                posX = videoWidth - watermarkBitmap.width.toFloat()
                posY = 0f
            }
            else -> {
                posX = 0f
                posY = 0f
            }
        }
        return Pair(posX, posY)
    }

    private fun adjustBitmapToSupportedSize(
    bitmap: Bitmap,
    videoCapabilities: VideoCapabilities
): Bitmap {
    val widthAlignment = videoCapabilities.widthAlignment ?: 2
    val heightAlignment = videoCapabilities.heightAlignment ?: 2

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

    private fun downloadFile(urlString: String): String {
        val url = URL(urlString)
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection

        // Follow redirects
        connection.instanceFollowRedirects = true

        // Generate a unique file name for each download
        val fileName = urlString.substringAfterLast('/', "temp_${System.currentTimeMillis()}")
        val file = File(
            applicationContext.cacheDir,
            fileName
        )

        connection.inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        connection.disconnect()

        // Return the path of the downloaded file
        return file.absolutePath
    }
}
