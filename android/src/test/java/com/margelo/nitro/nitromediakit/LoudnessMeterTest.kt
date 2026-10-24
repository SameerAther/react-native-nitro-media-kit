package com.margelo.nitro.nitromediakit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LoudnessMeterTest {
  private fun tone(
    channels: Int,
    frequency: Double = 1000.0,
    phase: Double = 0.0,
  ): LoudnessMeter.Measurement {
    val meter = LoudnessMeter(48000, channels)
    for (chunk in 0 until 30) {
      val data =
        FloatArray(4800 * channels) { i ->
          (
            0.1 *
              sin(2 * PI * frequency * (chunk * 4800 + i / channels) / 48000 + phase)
          ).toFloat()
        }
      meter.add(data)
    }
    return meter.finish()
  }

  @Test fun kWeightedToneMatchesEbur128ReferenceAndStereoPower() {
    val mono = tone(1)
    val stereo = tone(2)
    assertEquals(-23.0, mono.lufs!!, 0.12)
    assertEquals(3.0103, stereo.lufs!! - mono.lufs!!, 0.01)
    assertEquals(-20.0, mono.samplePeakDb!!, 0.01)
    assertEquals(3000.0, mono.durationMs, 0.0)
  }

  @Test fun silenceIsRepresentedWithoutInfinityAndOversamplingDetectsIntersamplePeaks() {
    val silent = LoudnessMeter(48000, 1)
    silent.add(FloatArray(48000))
    val result = silent.finish()
    assertNull(result.lufs)
    assertNull(result.truePeakDb)
    val high = tone(1, 12000.0, PI / 4)
    assertTrue(high.truePeakDb!! > high.samplePeakDb!! + 2.0)
  }
}
