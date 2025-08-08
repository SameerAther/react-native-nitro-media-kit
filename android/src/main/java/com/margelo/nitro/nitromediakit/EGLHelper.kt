package com.margelo.nitro.nitromediakit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix as GLMatrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * EglHelper
 * - Owns EGL display/context/surface bound to an encoder input Surface
 * - Receives decoder frames via SurfaceTexture (OES)
 * - Can draw:
 *    1) video frame (OES) with optional 2D overlay
 *    2) a static 2D bitmap (no OES) — used by image->video
 *
 * Stability choices:
 *  - Separate fragment programs for OES and 2D sampling (avoids Adreno flicker)
 *  - Exactly one updateTexImage() per frame
 *  - Stable viewport per frame
 */
class EglHelper {
    // ----- EGL state -----
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null
    private var windowSurface: Surface? = null

    // ----- Textures -----
    private var videoTextureId: Int = 0           // GL_TEXTURE_EXTERNAL_OES
    private var texture2DId: Int = 0              // GL_TEXTURE_2D (overlay/static)

    // ----- Decoder surface -----
    private var videoSurfaceTexture: SurfaceTexture? = null
    private val frameSync = Object()
    @Volatile private var frameAvailable = false

    // ----- Programs (split to avoid mixed-sampler branching bugs) -----
    private var programVideo: Int = 0             // vertex + fragmentOES
    private var program2D: Int = 0                // vertex + fragment2D

    // Locations for VIDEO program
    private var vPosLocVideo: Int = 0
    private var vUvLocVideo: Int = 0
    private var uStMatrixVideo: Int = 0
    private var uUseOverlayVideo: Int = 0
    private var uOverlayPosVideo: Int = 0
    private var uOverlaySizeVideo: Int = 0
    private var uSamplerVideoOes: Int = 0
    private var uSamplerOverlay2D: Int = 0

    // Locations for 2D program
    private var vPosLoc2D: Int = 0
    private var vUvLoc2D: Int = 0
    private var uStMatrix2D: Int = 0
    private var uSamplerBase2D: Int = 0

    // ----- Geometry -----
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var uvBuffer: FloatBuffer

    // ----- Overlay / viewport -----
    private var overlayBitmapWidth = 0
    private var overlayBitmapHeight = 0
    private var vpWidth = 0
    private var vpHeight = 0

    // ----- Shaders -----
    private val vertexShaderCode = """
        attribute vec4 vPosition;
        attribute vec2 vTexCoord;
        varying vec2 outTexCoord;
        uniform mat4 uSTMatrix;
        void main() {
            gl_Position = vPosition;
            vec4 tc = vec4(vTexCoord, 0.0, 1.0);
            outTexCoord = (uSTMatrix * tc).xy;
        }
    """.trimIndent()

    // OES fragment for video + optional overlay
    private val fragmentOesCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 outTexCoord;
        uniform samplerExternalOES sVideoTexture;
        uniform sampler2D          sOverlayTexture;
        uniform bool               uUseOverlay;
        uniform vec2               uOverlayPosition; // bottom-left in tex coords
        uniform vec2               uOverlaySize;     // width/height in tex coords
        void main() {
            vec4 base = texture2D(sVideoTexture, outTexCoord);
            if (!uUseOverlay || uOverlaySize.x == 0.0 || uOverlaySize.y == 0.0) {
                gl_FragColor = base; return;
            }
            vec2 rel = (outTexCoord - uOverlayPosition) / uOverlaySize;
            float inside = step(0.0, rel.x) * step(0.0, rel.y) * step(rel.x, 1.0) * step(rel.y, 1.0);
            rel = clamp(rel, 0.0, 1.0);
            vec4 overlay = texture2D(sOverlayTexture, rel);
            overlay.a *= inside;
            gl_FragColor = mix(base, overlay, overlay.a);
        }
    """.trimIndent()

    // Plain 2D fragment for static bitmap
    private val fragment2DCode = """
        precision mediump float;
        varying vec2 outTexCoord;
        uniform sampler2D sBaseTexture;
        void main() {
            gl_FragColor = texture2D(sBaseTexture, outTexCoord);
        }
    """.trimIndent()

    /** The Surface you hand to the decoder. */
    fun getVideoSurface(): Surface {
        val st = videoSurfaceTexture ?: error("videoSurfaceTexture is null; call createEglContext() first")
        return Surface(st)
    }

    // ----- Public API -----

    fun createEglContext(surface: Surface, videoWidth: Int, videoHeight: Int) {
        require(videoWidth > 0 && videoHeight > 0) {
            "createEglContext requires valid video size; got ${videoWidth}x${videoHeight}"
        }
        windowSurface = surface

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY).also {
            if (it == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
        }
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0] ?: error("eglChooseConfig failed")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // Lock viewport to encoder size every frame
        vpWidth = videoWidth
        vpHeight = videoHeight
        glViewport(0, 0, vpWidth, vpHeight)

        initGL()

        // Ensure decoder buffers match encoder size (critical on many devices)
        videoSurfaceTexture?.setDefaultBufferSize(videoWidth, videoHeight)
    }

    /** Upload (or replace) the content of the 2D texture (used for static & overlay). */
    fun loadStaticBitmapTexture(bitmap: Bitmap) {
        glBindTexture(GL_TEXTURE_2D, texture2DId)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** Convenience: build a text bitmap for overlays/watermarks. */
    fun createTextBitmap(text: String, textSize: Float, textColor: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = textSize
            textAlign = Paint.Align.LEFT
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val bmp = Bitmap.createBitmap(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(bmp).drawText(text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
        return bmp
    }

    /** Set/clear overlay bitmap (uploads into the shared 2D texture). */
    fun setOverlayBitmap(overlay: Bitmap?) {
        if (overlay == null) {
            overlayBitmapWidth = 0
            overlayBitmapHeight = 0
            return
        }
        overlayBitmapWidth = overlay.width
        overlayBitmapHeight = overlay.height
        glBindTexture(GL_TEXTURE_2D, texture2DId)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, overlay, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** Draw one decoded video frame (OES) without overlay. Returns the frame’s timestamp (ns). */
    fun drawVideoFrame(): Long {
        ensureViewport()
        awaitNewFrame()
        videoSurfaceTexture?.updateTexImage()

        val tsNs = videoSurfaceTexture?.timestamp ?: 0L
        val st = FloatArray(16)
        videoSurfaceTexture?.getTransformMatrix(st)

        glUseProgram(programVideo)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_BLEND)

        glUniformMatrix4fv(uStMatrixVideo, 1, false, fixOrientation(st), 0)
        glUniform1i(uUseOverlayVideo, 0)

        glEnableVertexAttribArray(vPosLocVideo)
        glEnableVertexAttribArray(vUvLocVideo)
        glVertexAttribPointer(vPosLocVideo, 2, GL_FLOAT, false, 0, vertexBuffer)
        glVertexAttribPointer(vUvLocVideo, 2, GL_FLOAT, false, 0, uvBuffer)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        glUniform1i(uSamplerVideoOes, 0)

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        glDisableVertexAttribArray(vPosLocVideo)
        glDisableVertexAttribArray(vUvLocVideo)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return tsNs
    }

    /**
     * Draw one decoded video frame with 2D overlay at absolute pixel (posX,posY) in video space.
     * Returns the frame’s timestamp (ns).
     */
    fun drawFrameWithOverlay(posX: Float, posY: Float, videoWidth: Int, videoHeight: Int): Long {
        ensureViewport()
        awaitNewFrame()
        videoSurfaceTexture?.updateTexImage()

        val tsNs = videoSurfaceTexture?.timestamp ?: 0L
        val st = FloatArray(16)
        videoSurfaceTexture?.getTransformMatrix(st)

        glUseProgram(programVideo)
        glDisable(GL_SCISSOR_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        glUniformMatrix4fv(uStMatrixVideo, 1, false, fixOrientation(st), 0)
        glUniform1i(uUseOverlayVideo, 1)

        val overlayPosX = posX / videoWidth.toFloat()
        val overlayPosY = 1f - (posY + overlayBitmapHeight).toFloat() / videoHeight
        val overlaySizeX = overlayBitmapWidth.toFloat() / videoWidth
        val overlaySizeY = overlayBitmapHeight.toFloat() / videoHeight
        glUniform2f(uOverlayPosVideo, overlayPosX, overlayPosY)
        glUniform2f(uOverlaySizeVideo, overlaySizeX, overlaySizeY)

        glEnableVertexAttribArray(vPosLocVideo)
        glEnableVertexAttribArray(vUvLocVideo)
        glVertexAttribPointer(vPosLocVideo, 2, GL_FLOAT, false, 0, vertexBuffer)
        glVertexAttribPointer(vUvLocVideo, 2, GL_FLOAT, false, 0, uvBuffer)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        glUniform1i(uSamplerVideoOes, 0)

        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, texture2DId)
        glUniform1i(uSamplerOverlay2D, 1)

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        glDisable(GL_BLEND)
        glDisableVertexAttribArray(vPosLocVideo)
        glDisableVertexAttribArray(vUvLocVideo)
        glBindTexture(GL_TEXTURE_2D, 0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return tsNs
    }

    /** Draw the currently loaded 2D texture full-frame (used by image->video). */
    fun drawStaticFrame(flipY: Boolean = false) {
        ensureViewport()

        glUseProgram(program2D)
        glDisable(GL_SCISSOR_TEST)
        glDisable(GL_BLEND)

        val m = FloatArray(16).apply { GLMatrix.setIdentityM(this, 0) }
        val finalM = if (flipY) fixOrientation(m) else m
        glUniformMatrix4fv(uStMatrix2D, 1, false, finalM, 0)

        glEnableVertexAttribArray(vPosLoc2D)
        glEnableVertexAttribArray(vUvLoc2D)
        glVertexAttribPointer(vPosLoc2D, 2, GL_FLOAT, false, 0, vertexBuffer)
        glVertexAttribPointer(vUvLoc2D, 2, GL_FLOAT, false, 0, uvBuffer)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, texture2DId)
        glUniform1i(uSamplerBase2D, 0)

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(vPosLoc2D)
        glDisableVertexAttribArray(vUvLoc2D)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun getVideoSurfaceTexture(): SurfaceTexture? = videoSurfaceTexture

    fun setPresentationTime(presentationTimeNs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
    }

    fun swapBuffers() {
        glFinish() // ensure all writes hit the surface before swap
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try {
            if (programVideo != 0) glDeleteProgram(programVideo)
            if (program2D != 0) glDeleteProgram(program2D)
            if (texture2DId != 0) glDeleteTextures(1, intArrayOf(texture2DId), 0)
            if (videoTextureId != 0) glDeleteTextures(1, intArrayOf(videoTextureId), 0)

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            Log.w("HybridMediaKit", "EglHelper.release() ignored: ${t.message}")
        } finally {
            videoSurfaceTexture?.release()
            videoSurfaceTexture = null
            windowSurface?.release()
            windowSurface = null

            eglSurface = null
            eglContext = null
            eglDisplay = null
            eglConfig = null
        }
    }

    // ----- Internals -----

    private fun initGL() {
        glClearColor(0f, 0f, 0f, 1f)

        // Full-screen quad
        val vertexCoords = floatArrayOf(
            -1f, -1f,  1f, -1f,
            -1f,  1f,  1f,  1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertexCoords); position(0)
            }

        val uvCoords = floatArrayOf(
            0f, 1f,  1f, 1f,
            0f, 0f,  1f, 0f
        )
        uvBuffer = ByteBuffer.allocateDirect(uvCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(uvCoords); position(0)
            }

        // Build programs
        val vs = loadShader(GL_VERTEX_SHADER, vertexShaderCode)
        val fsOes = loadShader(GL_FRAGMENT_SHADER, fragmentOesCode)
        val fs2D = loadShader(GL_FRAGMENT_SHADER, fragment2DCode)

        programVideo = buildProgram(vs, fsOes, "programVideo")
        program2D = buildProgram(vs, fs2D, "program2D")

        // VIDEO program locations
        glUseProgram(programVideo)
        vPosLocVideo         = glGetAttribLocation(programVideo, "vPosition")
        vUvLocVideo          = glGetAttribLocation(programVideo, "vTexCoord")
        uStMatrixVideo       = glGetUniformLocation(programVideo, "uSTMatrix")
        uUseOverlayVideo     = glGetUniformLocation(programVideo, "uUseOverlay")
        uOverlayPosVideo     = glGetUniformLocation(programVideo, "uOverlayPosition")
        uOverlaySizeVideo    = glGetUniformLocation(programVideo, "uOverlaySize")
        uSamplerVideoOes     = glGetUniformLocation(programVideo, "sVideoTexture")
        uSamplerOverlay2D    = glGetUniformLocation(programVideo, "sOverlayTexture")

        // 2D program locations
        glUseProgram(program2D)
        vPosLoc2D            = glGetAttribLocation(program2D, "vPosition")
        vUvLoc2D             = glGetAttribLocation(program2D, "vTexCoord")
        uStMatrix2D          = glGetUniformLocation(program2D, "uSTMatrix")
        uSamplerBase2D       = glGetUniformLocation(program2D, "sBaseTexture")

        // Create 2D texture (overlay / static)
        val tex2D = IntArray(1)
        glGenTextures(1, tex2D, 0)
        texture2DId = tex2D[0]
        glBindTexture(GL_TEXTURE_2D, texture2DId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)

        // Create OES texture for incoming video
        val texOes = IntArray(1)
        glGenTextures(1, texOes, 0)
        videoTextureId = texOes[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Create SurfaceTexture to receive decoder frames
        videoSurfaceTexture = SurfaceTexture(videoTextureId).apply {
            setOnFrameAvailableListener {
                synchronized(frameSync) {
                    frameAvailable = true
                    frameSync.notifyAll()
                }
            }
        }
    }

    private fun buildProgram(vs: Int, fs: Int, tag: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, vs)
        glAttachShader(p, fs)
        glLinkProgram(p)
        val linkStatus = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = glGetProgramInfoLog(p)
            glDeleteProgram(p)
            throw RuntimeException("Linking $tag failed: $log")
        }
        return p
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, code)
        glCompileShader(shader)
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $log")
        }
        return shader
    }

    private fun awaitNewFrame(timeoutMs: Long = 1500) {
        synchronized(frameSync) {
            val start = System.nanoTime()
            while (!frameAvailable) {
                val left = timeoutMs - ((System.nanoTime() - start) / 1_000_000L)
                if (left <= 0) throw RuntimeException("Frame wait timed out")
                frameSync.wait(left)
            }
            frameAvailable = false
        }
    }

    private inline fun ensureViewport() {
        glViewport(0, 0, vpWidth, vpHeight)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    private fun fixOrientation(st: FloatArray): FloatArray {
        // Flip Y because SurfaceTexture uses different origin
        val flip = FloatArray(16)
        GLMatrix.setIdentityM(flip, 0)
        GLMatrix.scaleM(flip, 0, 1f, -1f, 1f)
        GLMatrix.translateM(flip, 0, 0f, -1f, 0f)
        val out = FloatArray(16)
        GLMatrix.multiplyMM(out, 0, flip, 0, st, 0)
        return out
    }
}
