package com.margelo.nitro.nitromediakit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Streaming K weighting, 400ms/100ms gating and a 4x 48-tap windowed-sinc peak estimate. */
internal class LoudnessMeter(
  val sampleRate: Int,
  val channels: Int,
) {
  private class Filter(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
  ) {
    var z1 = 0.0
    var z2 = 0.0

    fun apply(x: Double): Double {
      val y = b0 * x + z1
      z1 = b1 * x - a1 * y + z2
      z2 = b2 * x - a2 * y
      return y
    }
  }

  private val shelf: Array<Filter>
  private val highpass: Array<Filter>
  private val history = Array(channels) { DoubleArray(12) }
  private val coefficients =
    Array(4) { phase ->
      DoubleArray(12) { tap ->
        val n =
          tap * 4 + phase
        val x = (n - 23.5) / 4
        (if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)) * 0.5 * (1 - cos(2 * PI * n / 47))
      }
    }
  private val energies = DoubleArray(maxOf(1, (sampleRate * 0.4).roundToInt()))
  private val blocks = ArrayList<Double>()
  private val hop = maxOf(1, (sampleRate * 0.1).roundToInt())
  private var frames = 0L
  private var ring = 0
  private var peakRing = 0
  private var sum = 0.0
  private var samplePeak = 0.0
  private var truePeak = 0.0

  init {
    require(sampleRate in 8000..192000 && channels in 1..2) {
      "Loudness analysis requires mono/stereo at 8–192 kHz; convert layouts explicitly first."
    }
    val k = tan(PI * 1681.974450955533 / sampleRate)
    val q = 0.7071752369554196
    val vh = 10.0.pow(3.999843853973347 / 20)
    val vb = vh.pow(0.4996667741545416)
    val d =
      1 + k / q + k * k
    shelf =
      Array(channels) {
        Filter(
          (vh + vb * k / q + k * k) / d,
          2 * (k * k - vh) / d,
          (vh - vb * k / q + k * k) / d,
          2 * (k * k - 1) / d,
          (1 - k / q + k * k) / d,
        )
      }
    val r = tan(PI * 38.13547087602444 / sampleRate)
    val rq = 0.5003270373238773
    val rd = 1 + r / rq + r * r
    highpass = Array(channels) { Filter(1.0, -2.0, 1.0, 2 * (r * r - 1) / rd, (1 - r / rq + r * r) / rd) }
  }

  fun add(
    samples: FloatArray,
    count: Int = samples.size,
  ) {
    var base = 0
    while (base + channels <= count) {
      var energy = 0.0
      for (c in 0 until channels) {
        val raw = samples[base + c].toDouble()
        val x = if (raw.isFinite()) raw else 0.0
        samplePeak = max(samplePeak, abs(x))
        history[c][peakRing] = x
        measurePeak(c)
        val filtered = highpass[c].apply(shelf[c].apply(x))
        energy += filtered * filtered
      }
      sum += energy - energies[ring]
      energies[ring] = energy
      ring = (ring + 1) % energies.size
      peakRing = (peakRing + 1) % 12
      frames++
      require(frames <= sampleRate * 86_400L) { "Audio analysis is limited to 24 hours per request." }
      if (frames >= energies.size && (frames - energies.size) % hop == 0L) blocks.add(max(0.0, sum) / energies.size)
      base += channels
    }
  }

  private fun measurePeak(channel: Int) {
    for (phase in 0..3) {
      var value = 0.0
      for (t in 0..11) {
        value +=
          history[channel][(peakRing - t + 12) % 12] * coefficients[phase][t]
      }
      truePeak = max(truePeak, abs(value))
    }
  }

  data class Measurement(
    val lufs: Double?,
    val samplePeakDb: Double?,
    val truePeakDb: Double?,
    val durationMs: Double,
  )

  fun finish(): Measurement {
    repeat(12) {
      for (c in 0 until channels) {
        history[c][peakRing] = 0.0
        measurePeak(c)
      }
      peakRing = (peakRing + 1) % 12
    }
    val absolute = blocks.filter { it > 10.0.pow((-70 + 0.691) / 10) }
    val average = absolute.average().takeIf { it.isFinite() } ?: 0.0
    val gated = absolute.filter { it >= average * 0.1 }
    val energy = gated.average().takeIf { it.isFinite() } ?: 0.0
    return Measurement(
      if (energy >
        0
      ) {
        -0.691 + 10 * log10(energy)
      } else {
        null
      },
      if (samplePeak >
        0
      ) {
        20 * log10(samplePeak)
      } else {
        null
      },
      if (max(truePeak, samplePeak) >
        0
      ) {
        20 * log10(max(truePeak, samplePeak))
      } else {
        null
      },
      frames * 1000.0 / sampleRate,
    )
  }
}
