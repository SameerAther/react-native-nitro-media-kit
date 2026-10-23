package com.margelo.nitro.nitromediakit

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.margelo.nitro.JNIOnLoad
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private lateinit var kit: NitroMediaKit
    private val generated = mutableListOf<String>()
    private val inputs = mutableListOf<File>()

    @Before fun initializeNitro() {
        JNIOnLoad.initializeNativeNitro()
        kit = NitroMediaKit(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun fixture(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "fixture-$name.mp4")
        instrumentation.context.assets.open("$name.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        inputs.add(file)
        return file.path
    }

    private fun output(result: MediaInfoResult): String {
        assertTrue(result.error?.message, result.ok)
        return requireNotNull(result.outputUri).also { generated.add(it) }
    }

    @After fun clean() = runBlocking {
        if (!::kit.isInitialized) return@runBlocking
        generated.forEach { kit.deleteGeneratedFile(it).await() }
        inputs.forEach { it.delete() }
    }

    @Test fun watermarkChangesFramesAndPreservesAudioAndRotation() = runBlocking {
        for (name in listOf("landscape", "portrait")) {
            val result = kit.watermarkVideo(fixture(name), "VISIBLE", WatermarkOptions(WatermarkPosition.TOP_LEFT, 20.0, "#FFFFFFFF", 1.0, 8.0)).await()
            val path = output(result)
            assertEquals(1.0, result.media?.audioTracks)
            assertEquals(if (name == "portrait") 180.0 else 320.0, result.media?.width)
            assertEquals(if (name == "portrait") 320.0 else 180.0, result.media?.height)
            assertEquals(10.0, requireNotNull(result.media?.fps), 0.05)
            for (index in 0 until 20) {
                val extracted = kit.extractThumbnail(path, ThumbnailOptions(index * 100.0, null, 1.0, ThumbnailPrecision.PRECISE)).await()
                val thumbnail = output(extracted)
                assertEquals(index * 100.0, requireNotNull(extracted.actualTimeMs), 1.0)
                val bitmap = requireNotNull(BitmapFactory.decodeFile(thumbnail))
                try {
                    var whitePixels = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 && android.graphics.Color.blue(pixel) > 180) whitePixels++
                    }
                    assertTrue("Watermark missing at frame $index", whitePixels > 50)
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun videoTimes(path: String): List<Long> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            buildList { while (extractor.sampleTime >= 0) { add(extractor.sampleTime); extractor.advance() } }.sorted()
        } finally { extractor.release() }
    }

    @Test fun combinedExportHasConstantTimingAndPreservesMonoAudio() = runBlocking {
        val result = kit.exportVideo(fixture("landscape"), ExportVideoOptions(VideoSegment(500.0, 1500.0), "VISIBLE",
            WatermarkOptions(WatermarkPosition.TOP_LEFT, 12.0, "#FFFFFFFF", 1.0, 4.0), CompressionPreset.HIGH,
            CompressVideoOptions(160.0, 20.0, null, null, null, VideoCodec.H264, ColorPolicy.SDR, FrameRatePolicy.CONSTANT, null),
            AudioPolicy.PRESERVE, false, null), "combined-regression", null).await()
        val path = output(result)
        assertEquals(160.0, result.media?.width)
        assertEquals(1.0, result.media?.audioChannels)
        assertEquals(VideoProcessingPath.ENCODED, result.diagnostics?.videoPath)
        val times = videoTimes(path)
        assertEquals(20, times.size)
        times.forEachIndexed { index, time -> assertEquals(index * 50_000.0, (time - times.first()).toDouble(), 100.0) }
    }

    @Test fun watermarkPreservesVariablePresentationTimes() = runBlocking {
        val source = fixture("motion-vfr")
        val result = output(kit.watermarkVideo(source, "VFR", null).await())
        val before = videoTimes(source); val after = videoTimes(result)
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) -> assertEquals((a - before.first()).toDouble(), (b - after.first()).toDouble(), 100.0) }
    }

    @Test fun compressionStripsLocationUnlessExplicitlyPreserved() = runBlocking {
        fun location(path: String): String? {
            val reader = android.media.MediaMetadataRetriever()
            try { reader.setDataSource(path); return reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION) }
            finally { reader.release() }
        }
        val source = fixture("landscape")
        assertNotNull("Fixture must contain GPS metadata", location(source))
        val stripped = output(kit.compressMedia(source, null).await())
        assertNull("Default export must strip GPS metadata", location(stripped))
        val preserved = output(kit.compressMedia(source, CompressMediaOptions(null, true, null, null)).await())
        assertNotNull("Explicit metadata preservation must retain supported GPS metadata", location(preserved))
    }

    @Test fun imageVideoAndMixedOrientationMerge() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = File(instrumentation.targetContext.cacheDir, "fixture-photo.png")
        instrumentation.context.assets.open("photo.png").use { input -> image.outputStream().use { input.copyTo(it) } }
        inputs.add(image)
        val converted = kit.convertImageToVideo(image.path, 0.01).await()
        val path = output(converted)
        assertTrue(requireNotNull(converted.media?.durationMs) > 0)
        assertEquals(0.0, converted.media?.audioTracks)
        val merged = kit.mergeVideos(arrayOf(fixture("landscape"), fixture("portrait"), path)).await()
        output(merged)
        assertEquals(320.0, merged.media?.width)
        assertEquals(180.0, merged.media?.height)
        assertEquals(1.0, merged.media?.audioTracks)
        assertEquals(4033.0, requireNotNull(merged.media?.durationMs), 150.0)
        assertTrue(kit.getMediaInfo(android.net.Uri.fromFile(File(path)).toString()).await().ok)
    }

    @Test fun trimMuteCompressAndInvalidRange() = runBlocking {
        val source = fixture("landscape")
        val trim = kit.trimVideo(source, VideoSegment(250.0, 1250.0)).await()
        output(trim)
        assertEquals(1000.0, requireNotNull(trim.media?.durationMs), 150.0)
        val mute = kit.removeAudio(source).await()
        output(mute)
        assertEquals(0.0, mute.media?.audioTracks)
        val compressed = kit.compressMedia(source, null).await()
        output(compressed)
        assertEquals(1.0, compressed.media?.audioTracks)
        assertFalse(kit.trimVideo(source, VideoSegment(Double.NaN, 1000.0)).await().ok)
        try { kit.deleteGeneratedFile(source).await(); fail("Input must not be deletable") }
        catch (_: IllegalArgumentException) { assertTrue(File(source).exists()) }
    }
}
