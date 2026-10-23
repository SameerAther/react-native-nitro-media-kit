package com.margelo.nitro.nitromediakit

import org.junit.Assert.*
import org.junit.Test

class ExportPlannerTest {
    private val source = VideoSource(3840, 2160, 60000.0 / 1001, 10_000.0, "video/avc", false, 40_000.0, 1, 6, 48000)
    private fun options(preset: CompressionPreset = CompressionPreset.PRESERVE, video: CompressVideoOptions? = null, audio: AudioPolicy? = null) = ExportVideoOptions(null, null, null, preset, video, audio, null, null)
    private fun video(fps: Double? = null, mode: FrameRatePolicy? = null, codec: VideoCodec? = null, color: ColorPolicy? = null, target: Double? = null, bitrate: Double? = null) = CompressVideoOptions(null, fps, bitrate, null, null, codec, color, mode, target)

    @Test fun preserveKeepsResolutionTimingAndCopyPath() {
        val plan = ExportPlanner.plan(source, options())
        assertEquals(3840, plan.width); assertEquals(2160, plan.height)
        assertEquals(source.fps, plan.fps, 0.0); assertTrue(plan.canCopy)
        assertEquals(AudioPolicy.PRESERVE, plan.audio)
    }
    @Test fun highDoesNotDiscard4kOr60fps() {
        val plan = ExportPlanner.plan(source, options(CompressionPreset.HIGH))
        assertEquals(3840, plan.width); assertEquals(source.fps, plan.fps, 0.0)
        assertTrue(plan.bitrateKbps > 12_000); assertFalse(plan.canCopy)
    }
    @Test fun fractionalCapIsNeverRoundedUp() {
        val plan = ExportPlanner.plan(source, options(video = video(23.5, FrameRatePolicy.CAP)))
        assertEquals(23.5, plan.fps, 0.0)
    }
    @Test fun preserveRejectsConflictingCap() { rejects { ExportPlanner.plan(source, options(video = video(24.0, FrameRatePolicy.PRESERVE))) } }
    @Test fun constantRequiresRate() { rejects { ExportPlanner.plan(source, options(video = video(mode = FrameRatePolicy.CONSTANT))) } }
    @Test fun hdrRequiresExplicitCompatiblePolicy() {
        val hdr = source.copy(mime = "video/hevc", hdr = true)
        assertEquals("video/hevc", ExportPlanner.plan(hdr, options()).mime)
        rejects { ExportPlanner.plan(hdr, options(video = video(codec = VideoCodec.H264))) }
        rejects { ExportPlanner.plan(hdr, options(video = video(color = ColorPolicy.REJECT_HDR))) }
        assertEquals(ColorPolicy.SDR, ExportPlanner.plan(hdr, options(video = video(codec = VideoCodec.H264, color = ColorPolicy.SDR))).color)
    }
    @Test fun targetSizeUsesTrimDurationAndAudioBudget() {
        val options = options(video = video(target = 5_000_000.0)).copy(trim = VideoSegment(2000.0, 7000.0))
        val plan = ExportPlanner.plan(source, options)
        assertEquals(5000.0, plan.durationMs, 0.0)
        assertEquals(5_000_000.0, plan.estimatedSizeBytes, 1.0)
        rejects { ExportPlanner.plan(source, options(video = video(target = 5_000_000.0, bitrate = 5000.0))) }
    }
    @Test fun invalidRangesAndNonfiniteValuesAreRejected() {
        rejects { ExportPlanner.plan(source, options().copy(trim = VideoSegment(Double.NaN, 5000.0))) }
        rejects { ExportPlanner.plan(source, options(video = video(Double.POSITIVE_INFINITY, FrameRatePolicy.CAP))) }
        rejects { ExportPlanner.plan(source, options(video = video(target = 1024.0))) }
    }
    @Test fun oversizedImageRequestsFailBeforeAllocating() {
        rejects { ExportPlanner.imageEdge(12000, 12000, 8192, true, 256L * 1024 * 1024) }
        assertTrue(ExportPlanner.imageEdge(12000, 12000, 4096, false, 256L * 1024 * 1024) <= 4096)
    }
    @Test fun explicitSdrForcesEncodingAndTenBitSdrNeedsHevc() {
        assertFalse(ExportPlanner.plan(source, options(video = video(color = ColorPolicy.SDR))).canCopy)
        val tenBit = source.copy(mime = "video/hevc", bitDepth = 10)
        assertTrue(ExportPlanner.plan(tenBit, options()).canCopy)
        rejects { ExportPlanner.plan(tenBit, options(video = video(codec = VideoCodec.H264))) }
    }
    @Test fun olderAndroidImageDecodeRetainsEnoughDetailForFinalResampling() {
        assertEquals(2, ExportPlanner.sampleSize(4032, 3024, 1920))
        assertEquals(4, ExportPlanner.sampleSize(12000, 8000, 2048))
        assertTrue(ExportPlanner.sampleSize(Int.MAX_VALUE, 1, 2) > 0)
    }
    @Test fun mergePlanningForcesEncodingWithoutLosingTheSourceBudget() {
        val plan = ExportPlanner.plan(source, options(), forceEncode = true)
        assertFalse(plan.canCopy); assertTrue(plan.bitrateKbps >= 40_000)
    }
    private fun rejects(body: () -> Unit) {
        try { body(); fail("Expected invalid plan to fail") } catch (_: IllegalArgumentException) {}
    }
}
