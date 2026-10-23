package com.margelo.nitro.nitromediakit

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class VideoSource(
    val width: Int, val height: Int, val fps: Double, val durationMs: Double,
    val mime: String, val hdr: Boolean, val bitrateKbps: Double?,
    val audioTracks: Int, val audioChannels: Int, val audioSampleRate: Int, val bitDepth: Int = 8
)

internal data class VideoPlan(
    val width: Int, val height: Int, val fps: Double, val timing: FrameRatePolicy,
    val mime: String, val color: ColorPolicy, val bitrateKbps: Int,
    val audioBitrateKbps: Int, val audio: AudioPolicy, val durationMs: Double,
    val estimatedSizeBytes: Double, val canCopy: Boolean
)

/** Pure planning: no codec setup, network calls, or JavaScript access. */
internal object ExportPlanner {
    fun plan(source: VideoSource, options: ExportVideoOptions, forceEncode: Boolean = false): VideoPlan {
        require(source.width > 0 && source.height > 0 && source.durationMs.isFinite() && source.durationMs > 0)
        val sourceFps = source.fps.takeIf { it.isFinite() && it > 0 } ?: 30.0
        val video = options.video
        val preset = options.preset ?: CompressionPreset.PRESERVE
        val color = video?.colorPolicy ?: ColorPolicy.PRESERVE
        require(!(source.hdr && color == ColorPolicy.REJECT_HDR)) { "HDR input is rejected by colorPolicy." }
        val mime = when (video?.codec ?: VideoCodec.AUTO) {
            VideoCodec.AUTO -> if ((source.hdr || source.bitDepth > 8) && color != ColorPolicy.SDR || source.mime == "video/hevc") "video/hevc" else "video/avc"
            VideoCodec.H264 -> "video/avc"
            VideoCodec.HEVC -> "video/hevc"
        }
        require(!((source.hdr || source.bitDepth > 8) && color != ColorPolicy.SDR && mime != "video/hevc")) { "HDR preservation requires HEVC output." }
        val sourceEdge = maxOf(source.width, source.height)
        val presetEdge = when (preset) {
            CompressionPreset.PRESERVE, CompressionPreset.HIGH -> sourceEdge
            CompressionPreset.AUTO, CompressionPreset.BALANCED -> min(sourceEdge, 1920)
            CompressionPreset.SMALL -> min(sourceEdge, 1280)
        }
        val edge = video?.maxLongEdge?.also { finite(it, 2.0, 8192.0, "maxLongEdge") } ?: presetEdge.toDouble()
        val scale = min(1.0, edge / sourceEdge)
        val width = ((source.width * scale).roundToInt().coerceAtLeast(2) / 2) * 2
        val height = ((source.height * scale).roundToInt().coerceAtLeast(2) / 2) * 2
        val timing = video?.frameRatePolicy ?: if (video?.maxFps != null || preset in listOf(CompressionPreset.AUTO, CompressionPreset.BALANCED, CompressionPreset.SMALL)) FrameRatePolicy.CAP else FrameRatePolicy.PRESERVE
        require(timing != FrameRatePolicy.PRESERVE || video?.maxFps == null) { "maxFps conflicts with preserve timing." }
        val requestedFps = video?.maxFps?.also { finite(it, 1.0, 240.0, "maxFps") }
        require(timing != FrameRatePolicy.CONSTANT || requestedFps != null) { "Constant timing requires maxFps." }
        val fps = when (timing) {
            FrameRatePolicy.PRESERVE -> sourceFps
            FrameRatePolicy.CAP -> min(sourceFps, requestedFps ?: if (preset == CompressionPreset.SMALL) 24.0 else 30.0)
            FrameRatePolicy.CONSTANT -> requireNotNull(requestedFps)
        }
        val duration = options.trim?.let {
            require(it.startMs.isFinite() && it.endMs.isFinite() && it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= source.durationMs) { "Invalid trim range." }
            it.endMs - it.startMs
        } ?: source.durationMs
        val audio = options.audio ?: if (video?.keepAudio == false) AudioPolicy.MUTE else AudioPolicy.PRESERVE
        require(!(video?.keepAudio == false && options.audio != null && options.audio != AudioPolicy.MUTE)) { "Conflicting audio policies." }
        val audioKbps = video?.audioBitrateKbps?.also { finite(it, 32.0, 512.0, "audioBitrateKbps") } ?: if (source.audioChannels > 2 && audio == AudioPolicy.PRESERVE) 384.0 else 192.0
        val bpp = when (preset) {
            CompressionPreset.PRESERVE -> 0.14
            CompressionPreset.HIGH -> 0.12
            CompressionPreset.AUTO, CompressionPreset.BALANCED -> 0.085
            CompressionPreset.SMALL -> 0.065
        } * if (mime == "video/hevc") 0.65 else 1.0
        val estimatedRate = (width.toDouble() * height * fps * bpp / 1000).coerceIn(300.0, 160_000.0)
        var bitrate = video?.videoBitrateKbps?.also { finite(it, 300.0, 160_000.0, "videoBitrateKbps") }
            ?: if (preset == CompressionPreset.PRESERVE) maxOf(estimatedRate, source.bitrateKbps ?: 0.0).coerceAtMost(160_000.0) else estimatedRate
        val audioRate = if (source.audioTracks > 0 && audio != AudioPolicy.MUTE) audioKbps else 0.0
        video?.targetSizeBytes?.let { bytes ->
            finite(bytes, 1024.0, 68_719_476_736.0, "targetSizeBytes")
            require(video.videoBitrateKbps == null) { "Specify targetSizeBytes or videoBitrateKbps, not both." }
            bitrate = bytes * 8 * 0.97 / duration - audioRate
            require(bitrate in 300.0..160_000.0) { "Target size is incompatible with duration and audio budget." }
        }
        val copy = !forceEncode && options.trim == null && options.watermark == null && width == source.width && height == source.height && timing == FrameRatePolicy.PRESERVE && mime == source.mime && color != ColorPolicy.SDR && video?.videoBitrateKbps == null && video?.targetSizeBytes == null && preset == CompressionPreset.PRESERVE
        return VideoPlan(width, height, fps, timing, mime, color, bitrate.roundToInt(), audioKbps.roundToInt(), audio, duration,
            (bitrate + audioRate) * duration / 8 / 0.97, copy)
    }

    fun finite(value: Double, min: Double, max: Double, name: String) {
        require(value.isFinite() && value in min..max) { "$name must be finite and in [$min, $max]." }
    }

    // Decode at or above the final size, then resample once; avoid discarding detail first.
    fun sampleSize(width: Int, height: Int, targetLongEdge: Int): Int {
        val sourceLong = maxOf(width, height)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 && sourceLong / (sample * 2) >= targetLongEdge.coerceAtLeast(1)) sample *= 2
        return sample
    }

    fun imageEdge(width: Int, height: Int, requested: Int, explicit: Boolean, heapBytes: Long): Int {
        val pixelBudget = min(16_777_216L, heapBytes / 32).coerceAtLeast(1_048_576L)
        val ratio = width.toDouble() / height
        val budgetEdge = sqrt(pixelBudget * maxOf(ratio, 1 / ratio)).toInt()
        require(!explicit || requested <= budgetEdge) { "Requested image dimensions exceed the memory budget." }
        return min(requested, budgetEdge).coerceAtLeast(2)
    }
}
