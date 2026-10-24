package com.margelo.nitro.nitromediakit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EditingPlanTest {
  private val source = VideoSource(1920, 1080, 30.0, 10_000.0, "video/avc", false, 8000.0, 1, 2, 48000)

  @Test fun cropAndClockwiseRotationDetermineCanvasBeforeResizing() {
    val geometry =
      VideoGeometry(CropRect(200.0, 0.0, 1080.0, 1080.0), 90.0, true, false, 720.0, 1280.0, GeometryFit.COVER)
    val plan = ExportPlanner.plan(source, basicOptions().copy(geometry = geometry))
    assertEquals(720, plan.width)
    assertEquals(1280, plan.height)
    assertFalse(plan.canCopy)
    rejects { GeometryPlan.make(1920, 1080, geometry.copy(crop = CropRect(1000.0, 0.0, 1080.0, 1080.0))) }
    rejects { GeometryPlan.make(1920, 1080, geometry.copy(rotation = 45.0)) }
    rejects { GeometryPlan.make(1920, 1080, geometry.copy(height = null)) }
    rejects { GeometryPlan.make(1920, 1080, geometry.copy(width = Double.NaN)) }
  }

  @Test fun speedChangesBothDurationAndSourceFrameCadence() {
    val plan = ExportPlanner.plan(source, basicOptions().copy(speed = 2.0, trim = VideoSegment(2000.0, 8000.0)))
    assertEquals(3000.0, plan.durationMs, 0.0)
    assertEquals(60.0, plan.fps, 0.0)
    assertFalse(plan.canCopy)
    rejects { ExportPlanner.plan(source, basicOptions().copy(speed = 0.0)) }
  }

  @Test fun requireCopyRejectsLossyEditsButAcceptsKeyframeTrim() {
    val copy =
      basicOptions().copy(
        copyPolicy = CopyPolicy.REQUIRE_COPY,
        trimMode = TrimMode.KEYFRAME,
        trim = VideoSegment(1000.0, 8000.0),
      )
    assertTrue(ExportPlanner.plan(source, copy).canCopy)
    rejects { ExportPlanner.plan(source, copy.copy(trimMode = TrimMode.ACCURATE)) }
    rejects { ExportPlanner.plan(source, copy.copy(speed = 2.0)) }
    assertFalse(ExportPlanner.plan(source, basicOptions().copy(copyPolicy = CopyPolicy.ENCODE)).canCopy)
  }

  @Test fun subtitleParserHandlesVttSettingsCrLfAndOverlaps() {
    val vtt =
      SubtitleOptions(
        "WEBVTT\r\n\r\ncue-1\r\n00:01.000 --> 00:03.000 align:center\r\n<b>Hello</b>\r\n\r\n00:02.000 --> 00:04.000\r\nWorld",
        SubtitleFormat.VTT,
        null,
        null,
        null,
      )
    val cues = EditingValidation.cues(vtt, 3500.0)
    assertEquals(2, cues.size)
    assertEquals("Hello", cues[0].text)
    assertEquals(3500.0, cues[1].endMs, 0.0)
    rejects { EditingValidation.cues(vtt.copy(content = "WEBVTT\n\n00:61.000 --> 00:62.000\nBad"), 10_000.0) }
    rejects { EditingValidation.cues(vtt.copy(content = "WEBVTT\n\n00:01.000 --> 00:00.000\nBad"), 10_000.0) }
    val excessive = "WEBVTT\n\n" + (0..4).joinToString("\n\n") { "00:00.000 --> 00:05.000\n$it" }
    rejects { EditingValidation.cues(vtt.copy(content = excessive), 10_000.0) }
  }

  @Test fun overlayTimingUsesFinalSpeedAdjustedTimeline() {
    val overlay =
      VideoOverlay(OverlayKind.TEXT, null, "Brand", 0.0, 0.0, 200.0, 100.0, null, 0.0, 6000.0, null, null, null, null)
    rejects { ExportPlanner.plan(source, basicOptions().copy(speed = 2.0, overlays = arrayOf(overlay))) }
    val valid = overlay.copy(endMs = 5000.0, fadeInMs = 1000.0, fadeOutMs = 1000.0)
    ExportPlanner.plan(source, basicOptions().copy(speed = 2.0, overlays = arrayOf(valid)))
    rejects { ExportPlanner.plan(source, basicOptions().copy(overlays = arrayOf(valid.copy(fadeOutMs = 5000.0)))) }
  }

  @Test fun timelineOverlapsHaveOneClockAndRejectTripleOverlap() {
    val layout = TimelineLayout.make(listOf(5000.0, 4000.0, 3000.0), listOf(500.0, 1000.0, 0.0))
    assertEquals(listOf(0.0, 4500.0, 7500.0), layout.starts)
    assertEquals(10500.0, layout.durationMs, 0.0)
    rejects { TimelineLayout.make(listOf(5000.0, 1000.0, 5000.0), listOf(750.0, 750.0, 0.0)) }
    rejects { TimelineLayout.make(listOf(1000.0), listOf(1.0)) }
    rejects { TimelineLayout.make(listOf(Double.NaN), listOf(0.0)) }
  }

  private fun rejects(body: () -> Unit) {
    try {
      body()
      fail("Expected validation failure")
    } catch (_: IllegalArgumentException) {
    }
  }
}
