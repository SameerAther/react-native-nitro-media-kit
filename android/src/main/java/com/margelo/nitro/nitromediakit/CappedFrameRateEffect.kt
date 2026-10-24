package com.margelo.nitro.nitromediakit

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.floor

/** Select at most one source frame per target time bucket, retaining its original timestamp. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class CappedFrameRateEffect(
  private val fps: Double,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = Program(fps)

  internal class Program(
    private val fps: Double,
  ) : GlShaderProgram {
    private var input: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var output: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var firstTime: Long? = null
    private var nextIndex = 0.0
    private var pending: GlTextureInfo? = null
    private var ending = false

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
      input = listener
      input.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(listener: GlShaderProgram.OutputListener) {
      output = listener
    }

    override fun setErrorListener(
      executor: Executor,
      listener: GlShaderProgram.ErrorListener,
    ) {}

    override fun queueInputFrame(
      provider: GlObjectsProvider,
      texture: GlTextureInfo,
      presentationTimeUs: Long,
    ) {
      check(pending == null && !ending) { "Frame cap backpressure violated." }
      if (firstTime == null) firstTime = presentationTimeUs
      val position = (presentationTimeUs - requireNotNull(firstTime)) * fps / 1_000_000
      if (position + 0.000001 < nextIndex) {
        input.onInputFrameProcessed(texture)
        input.onReadyToAcceptInputFrame()
      } else {
        nextIndex = floor(position + 0.000001) + 1
        pending = texture
        output.onOutputFrameAvailable(texture, presentationTimeUs)
      }
    }

    override fun releaseOutputFrame(texture: GlTextureInfo) {
      check(texture.texId == pending?.texId)
      pending = null
      input.onInputFrameProcessed(texture)
      if (ending) output.onCurrentOutputStreamEnded() else input.onReadyToAcceptInputFrame()
    }

    override fun signalEndOfCurrentInputStream() {
      ending = true
      if (pending ==
        null
      ) {
        output.onCurrentOutputStreamEnded()
      }
    }

    override fun flush() {
      firstTime = null
      nextIndex = 0.0
      pending = null
      ending = false
      input.onFlush()
      input.onReadyToAcceptInputFrame()
    }

    override fun release() {
      pending = null
    }
  }
}
