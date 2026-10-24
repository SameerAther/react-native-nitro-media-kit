package com.margelo.nitro.nitromediakit

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.FrameCache
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Hold-last-frame resampling. Repeats reuse a texture; input staging uses two bounded GPU textures. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class ConstantFrameRateEffect private constructor(
  private val fps: Double,
  private val durationUs: Long,
) : GlEffect {
  companion object {
    fun create(
      fps: Double,
      durationUs: Long,
    ): List<Effect> =
      listOf(
        // Media3's upstream matrix shader has capacity 1. Lookahead needs two inputs;
        // staging releases that shader immediately and prevents a producer deadlock.
        FrameCache(2),
        ConstantFrameRateEffect(fps, durationUs),
      )
  }

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = Program(fps, durationUs)

  internal class Program(
    val fps: Double,
    val durationUs: Long,
  ) : GlShaderProgram {
    private var input: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var output: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errors: GlShaderProgram.ErrorListener? = null
    private var errorExecutor: Executor? = null
    private var held: GlTextureInfo? = null
    private var next: GlTextureInfo? = null
    private var boundary = 0L
    private var index = 0L
    private var inFlight = false
    private var ending = false
    private var ended = false
    private var draining = false

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
    ) {
      errorExecutor =
        executor
      errors = listener
    }

    override fun queueInputFrame(
      provider: GlObjectsProvider,
      texture: GlTextureInfo,
      presentationTimeUs: Long,
    ) {
      if (held == null) {
        held = texture
        index = ceil(presentationTimeUs.coerceAtLeast(0) * fps / 1_000_000).toLong()
        input.onReadyToAcceptInputFrame()
      } else {
        check(next == null && !ending) { "CFR input backpressure violated." }
        next = texture
        boundary = presentationTimeUs.coerceAtMost(durationUs)
        drain()
      }
    }

    override fun releaseOutputFrame(texture: GlTextureInfo) {
      check(texture.texId == held?.texId && inFlight)
      inFlight =
        false
      drain()
    }

    override fun signalEndOfCurrentInputStream() {
      ending = true
      if (next == null) boundary = durationUs
      drain()
    }

    private fun drain() {
      if (draining) return
      draining = true
      try {
        while (!inFlight && held != null && (next != null || ending)) {
          val time = (index * 1_000_000 / fps).roundToLong()
          if (time < boundary) {
            index++
            inFlight = true
            output.onOutputFrameAvailable(requireNotNull(held), time)
          } else {
            val previous = requireNotNull(held)
            held = next
            next = null
            input.onInputFrameProcessed(previous)
            if (held != null) {
              if (ending) boundary = durationUs else input.onReadyToAcceptInputFrame()
            } else if (ending && !ended) {
              ended = true
              output.onCurrentOutputStreamEnded()
            }
          }
        }
        if (ending && held == null && !ended) {
          ended = true
          output.onCurrentOutputStreamEnded()
        }
      } catch (e: Exception) {
        errorExecutor?.execute { errors?.onError(VideoFrameProcessingException(e)) }
      } finally {
        draining = false
      }
    }

    override fun flush() {
      held = null
      next = null
      inFlight = false
      ending = false
      ended = false
      index = 0
      input.onFlush()
      input.onReadyToAcceptInputFrame()
    }

    override fun release() {
      held = null
      next = null
      inFlight = false
    }
  }
}
