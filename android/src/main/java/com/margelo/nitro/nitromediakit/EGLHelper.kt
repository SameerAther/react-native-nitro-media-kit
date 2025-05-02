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
    private var useVideoTextureHandle: Int = 0
    private var stMatrixHandle: Int = 0     
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
        uniform mat4 uSTMatrix;
        void main() {
            gl_Position = vPosition;
            vec4 texCoord = vec4(vTexCoord, 0.0, 1.0);
            outTexCoord = (uSTMatrix * texCoord).xy;
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
        uniform sampler2D        sOverlayTexture;
        uniform bool uUseVideoTexture;
        uniform bool uUseOverlay;
        uniform vec2 uOverlayPosition;   // bottom-left corner in tex-coords
        uniform vec2 uOverlaySize;       // (width,height) in tex-coords
        void main() {
            vec4 base = uUseVideoTexture
                    ? texture2D(sVideoTexture, outTexCoord)
                    : texture2D(sOverlayTexture, outTexCoord);
            if (!uUseOverlay) {
                gl_FragColor = base;
                return;
            }
            vec2 rel = (outTexCoord - uOverlayPosition) / uOverlaySize;
            float inOverlay = step(0.0, rel.x) * step(0.0, rel.y) *
                            step(rel.x, 1.0) * step(rel.y, 1.0);
            rel = clamp(rel, 0.0, 1.0);
            vec4 overlay = texture2D(sOverlayTexture, rel);
            overlay.a *= inOverlay;
            gl_FragColor = mix(base, overlay, overlay.a);
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
        useVideoTextureHandle = GLES20.glGetUniformLocation(program, "uUseVideoTexture")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

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
        val stMatrix = FloatArray(16)
        videoSurfaceTexture?.getTransformMatrix(stMatrix)

        GLES20.glUseProgram(program)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUniform1i(useVideoTextureHandle, 1) // Use video texture
        GLES20.glUniform1i(useOverlayHandle, 0)      // Disable overlay
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, fixOrientation(stMatrix), 0)

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

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return timestamp
    }

    fun drawFrameWithOverlay(posX: Float, posY: Float, videoWidth: Int, videoHeight: Int): Long {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(1000)
                if (!frameAvailable) throw RuntimeException("Frame wait timed out")
            }
            frameAvailable = false
        }
        videoSurfaceTexture?.updateTexImage()
        val timestamp = videoSurfaceTexture?.timestamp ?: 0L
        val stMatrix = FloatArray(16)
        videoSurfaceTexture?.getTransformMatrix(stMatrix)

        GLES20.glUseProgram(program)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUniform1i(useVideoTextureHandle, 1) // Use video texture
        GLES20.glUniform1i(useOverlayHandle, 1)     // Enable overlay
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, fixOrientation(stMatrix), 0)

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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val overlayTextureHandle = GLES20.glGetUniformLocation(program, "sOverlayTexture")
        GLES20.glUniform1i(overlayTextureHandle, 1)

        val normalizedPosY = (videoHeight - posY - overlayBitmapHeight) / videoHeight.toFloat()
        val overlayPositionHandle = GLES20.glGetUniformLocation(program, "uOverlayPosition")
        val overlayPosX = posX / videoWidth.toFloat()
        val overlayPosY = 1f - (posY + overlayBitmapHeight).toFloat() / videoHeight
        GLES20.glUniform2f(overlayPositionHandle, overlayPosX, overlayPosY)

        val overlaySizeHandle = GLES20.glGetUniformLocation(program, "uOverlaySize")
        GLES20.glUniform2f(overlaySizeHandle, overlayBitmapWidth.toFloat() / videoWidth, overlayBitmapHeight.toFloat() / videoHeight)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glFinish();

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return timestamp
    }

    fun drawFrame(bitmap: Bitmap) {
        GLES20.glUseProgram(program)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUniform1i(useVideoTextureHandle, 0) // Use overlay texture for image
        GLES20.glUniform1i(useOverlayHandle, 0)     // Disable overlay
        // Set identity matrix since no transformation is needed for static images
        val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, fixOrientation(identityMatrix), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val overlayTextureHandle = GLES20.glGetUniformLocation(program, "sOverlayTexture")
        GLES20.glUniform1i(overlayTextureHandle, 0)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "vTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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

    private fun fixOrientation(st: FloatArray): FloatArray {
        val flip = FloatArray(16)
        Matrix.setIdentityM(flip, 0)
        Matrix.scaleM(flip, 0, 1f, -1f, 1f)   // flip Y
        Matrix.translateM(flip, 0, 0f, -1f, 0f)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, flip, 0, st, 0)
        return out
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
