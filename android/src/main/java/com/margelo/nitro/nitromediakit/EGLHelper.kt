package com.margelo.nitro.nitromediakit

import android.opengl.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import android.view.Surface
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

class EglHelper {
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var surface: Surface? = null
    private var videoTextureId: Int = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private val frameSyncObject = Object()
    @Volatile private var frameAvailable = false
    private var program: Int = 0
    private var textureId: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null
    private var overlayBitmapWidth = 0
    private var overlayBitmapHeight = 0
    private var useOverlayHandle = 0

    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 vTexCoord;
        varying vec2 outTexCoord;
        void main() {
            gl_Position = vPosition;
            outTexCoord = vTexCoord;
        }
    """.trimIndent()

    fun getVideoSurface(): Surface {
        return Surface(videoSurfaceTexture)
    }

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 outTexCoord;
        uniform samplerExternalOES sVideoTexture;
        uniform sampler2D sOverlayTexture;
        uniform vec2 uOverlayPosition;
        uniform vec2 uOverlaySize;
        uniform bool uUseOverlay;
        void main() {
            vec4 videoColor = texture2D(sVideoTexture, outTexCoord);
            if (uUseOverlay) {
                vec2 overlayCoord = (outTexCoord - uOverlayPosition) / uOverlaySize;
                overlayCoord = clamp(overlayCoord, vec2(0.0), vec2(1.0));
                vec4 overlayColor = texture2D(sOverlayTexture, overlayCoord);
                float inOverlay = step(0.0, overlayCoord.x) * step(0.0, overlayCoord.y) *
                                step(overlayCoord.x, 1.0) * step(overlayCoord.y, 1.0);
                overlayColor.a *= inOverlay;
                gl_FragColor = mix(videoColor, overlayColor, overlayColor.a);
            } else {
                gl_FragColor = videoColor;
            }
        }
    """.trimIndent()

    fun createEglContext(surface: Surface) {
        this.surface = surface

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        initGL()
    }

    fun createTextBitmap(text: String, textSize: Float, textColor: Int): Bitmap {
        val textPaint = Paint().apply {
            color = textColor
            this.textSize = textSize
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val bitmap = Bitmap.createBitmap(textBounds.width(), textBounds.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(text, -textBounds.left.toFloat(), -textBounds.top.toFloat(), textPaint)
        return bitmap
    }

    fun setOverlayBitmap(overlayBitmap: Bitmap?) {
        if (overlayBitmap != null) {
            overlayBitmapWidth = overlayBitmap.width
            overlayBitmapHeight = overlayBitmap.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        } else {
            overlayBitmapWidth = 0
            overlayBitmapHeight = 0
        }
    }

    private fun initGL() {
        // Set a clear color
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Enable blending for proper alpha handling on overlay
        GLES20.glEnable(GLES20.GL_BLEND)
        // If your overlay is straight alpha, use this blend func:
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // If it's premultiplied alpha, use:
        // GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Compile and link the shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
        useOverlayHandle = GLES20.glGetUniformLocation(program, "uUseOverlay")

        // Define vertex coordinates for a fullscreen quad
        val vertexCoords = floatArrayOf(
            -1f, -1f, // Bottom Left
            1f, -1f, // Bottom Right
            -1f,  1f, // Top Left
            1f,  1f  // Top Right
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(vertexCoords)
                position(0)
            }

        // Define texture coordinates corresponding to the quad
        val textureCoords = floatArrayOf(
            0f, 1f, // Bottom Left
            1f, 1f, // Bottom Right
            0f, 0f, // Top Left
            1f, 0f  // Top Right
        )

        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(textureCoords)
                position(0)
            }

        // ----- Overlay Texture -----
        val overlayTextures = IntArray(1)
        GLES20.glGenTextures(1, overlayTextures, 0)
        textureId = overlayTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // Use linear filtering for smoother overlay rendering
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Clamp to edge to prevent wrapping artifacts
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // ----- Video Texture (External OES) -----
        val videoTextures = IntArray(1)
        GLES20.glGenTextures(1, videoTextures, 0)
        videoTextureId = videoTextures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        // Linear filtering for smoother video playback
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Clamp edges for the video texture as well
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Create the SurfaceTexture for receiving video frames
        videoSurfaceTexture = SurfaceTexture(videoTextureId)
        videoSurfaceTexture?.setOnFrameAvailableListener {
            synchronized(frameSyncObject) {
                frameAvailable = true
                frameSyncObject.notifyAll()
            }
        }
    }

    fun checkGlError(op: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e("HybridMediaKit", "$op: glError $error")
            throw RuntimeException("$op: glError $error")
        }
    }

    fun drawVideoFrame(): Long {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(1000)
                if (!frameAvailable) throw RuntimeException("Frame wait timed out")
            }
            frameAvailable = false
        }
        videoSurfaceTexture?.updateTexImage()
        val timestamp = videoSurfaceTexture?.timestamp ?: 0L

        GLES20.glUseProgram(program)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUniform1i(useOverlayHandle, 0) // Disable overlay

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        val videoTextureHandle = GLES20.glGetUniformLocation(program, "sVideoTexture")
        GLES20.glUniform1i(videoTextureHandle, 0)

        // Bind overlay texture (unused but bound for safety)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val overlayTextureHandle = GLES20.glGetUniformLocation(program, "sOverlayTexture")
        GLES20.glUniform1i(overlayTextureHandle, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return timestamp
    }

    fun drawFrameWithOverlay(
        posX: Float,
        posY: Float,
        videoWidth: Int,
        videoHeight: Int
    ): Long {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(1000)
                if (!frameAvailable) {
                    throw RuntimeException("Frame wait timed out")
                }
            }
            frameAvailable = false
        }
        videoSurfaceTexture?.updateTexImage()
        val timestamp = videoSurfaceTexture?.timestamp ?: 0L

        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Prepare position data
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        checkGlError("glVertexAttribPointer vPosition")

        // Prepare texture coordinate data
        val texCoordHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        checkGlError("glVertexAttribPointer vTexCoord")

        // Bind video texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        val videoTextureHandle = GLES20.glGetUniformLocation(program, "sVideoTexture")
        GLES20.glUniform1i(videoTextureHandle, 0)
        checkGlError("glBindTexture sVideoTexture")

        // Bind overlay texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val overlayTextureHandle = GLES20.glGetUniformLocation(program, "sOverlayTexture")
        GLES20.glUniform1i(overlayTextureHandle, 1)
        checkGlError("glBindTexture sOverlayTexture")

        // Adjust overlay position if necessary
        val normalizedPosY = (videoHeight - posY - overlayBitmapHeight) / videoHeight.toFloat()
        val overlayPositionHandle = GLES20.glGetUniformLocation(program, "uOverlayPosition")
        val useOverlayHandle = GLES20.glGetUniformLocation(program, "uUseOverlay")
        GLES20.glUniform1i(useOverlayHandle, 1)
        GLES20.glUniform2f(
            overlayPositionHandle,
            posX / videoWidth.toFloat(),
            normalizedPosY
        )

        // Set overlay size
        val overlaySizeHandle = GLES20.glGetUniformLocation(program, "uOverlaySize")
        GLES20.glUniform2f(
            overlaySizeHandle,
            overlayBitmapWidth.toFloat() / videoWidth,
            overlayBitmapHeight.toFloat() / videoHeight
        )
        checkGlError("glUniform uOverlayPosition and uOverlaySize")

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        // Disable attribute arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        // Unbind textures
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return timestamp
    }

    fun drawFrame(bitmap: Bitmap) {
        GLES20.glUseProgram(program)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Bind texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // Prepare position data
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Prepare texture coordinate data
        val texCoordHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // Set the sampler texture unit to 0
        val samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        GLES20.glUniform1i(samplerHandle, 0)

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable attribute arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        // Unbind texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Swap buffers
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("HybridMediaKit", "Could not compile shader $type:")
            Log.e("HybridMediaKit", GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type")
        }
        return shader
    }

    fun getVideoSurfaceTexture(): SurfaceTexture? {
        return videoSurfaceTexture
    }

    fun setPresentationTime(presentationTimeNs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        surface?.release()
    }
}
