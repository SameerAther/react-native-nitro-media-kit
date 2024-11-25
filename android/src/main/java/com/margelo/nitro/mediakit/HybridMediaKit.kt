package com.margelo.nitro.mediakit

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
import android.media.MediaCodecInfo.VideoCapabilities // Added import
import android.util.Range // Added import for Range

class HybridMediaKit : HybridMediaKitSpec() {
    override val memorySize: Long
        get() = 0L

    override val pi: Double
        get() = PI

    override fun add(a: Double, b: Double): Double {
        return a + b
    }

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
