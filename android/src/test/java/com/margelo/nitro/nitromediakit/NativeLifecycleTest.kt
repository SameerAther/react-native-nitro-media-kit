package com.margelo.nitro.nitromediakit

import androidx.media3.common.audio.SpeedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeLifecycleTest {
  @Test fun completedRelayCannotRetainAnotherListener() {
    var calls = 0
    val relay = ProgressRelay { calls++ }
    val event = ExportProgress("job", ExportStage.PROCESSING, 0.5)
    relay.emit(event)
    assertEquals(1, calls)
    relay.finish()
    relay.set { calls++ }
    relay.emit(event)
    assertEquals(1, calls)
  }

  @Test fun defaultSpeedRetainsMedia3sTrueNoOpPath() {
    assertSame(SpeedProvider.DEFAULT, AudioEngine.speed(1.0))
    assertEquals(2f, AudioEngine.speed(2.0).getSpeed(0), 0f)
  }

  @Test fun failuresExposeStableCodesAndDoNotDuplicatePrefixes() {
    assertEquals("INVALID_ARGUMENT", MediaErrors.normalize(IllegalArgumentException("bad crop")).code)
    assertEquals(
      "[CANCELLED] cancelled",
      MediaErrors.normalize(PipelineFailure("CANCELLED", "[CANCELLED] cancelled")).message,
    )
  }
}
