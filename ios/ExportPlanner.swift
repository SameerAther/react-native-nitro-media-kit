import Foundation

struct VideoSource {
    let width: Double, height: Double, fps: Double, durationMs: Double, sizeBytes: Double
    let audioTracks: Int
    let extensionName: String?
    let mime: String, hdr: Bool, bitDepth: Int
    let bitrateKbps: Double?
    let audioChannels: Int, audioSampleRate: Double
    let colorPrimaries: String?, colorTransfer: String?, colorMatrix: String?
}
struct VideoPlan {
    let width: Int, height: Int
    let fps: Double, timing: FrameRatePolicy
    let mime: String, color: ColorPolicy
    let bitrateKbps: Int, audioBitrateKbps: Int
    let audio: AudioPolicy
    let durationMs: Double, estimatedSizeBytes: Double
    let canCopy: Bool
}

enum ExportPlanner {
    static func plan(_ source: VideoSource, options: ExportVideoOptions, forceEncode: Bool = false) throws -> VideoPlan {
        guard source.width.isFinite, source.height.isFinite, source.width > 0, source.height > 0,
              source.width <= Double(Int32.max), source.height <= Double(Int32.max), source.durationMs.isFinite, source.durationMs > 0 else { throw MediaJob.error("Invalid source dimensions or duration.") }
        let sourceFps = source.fps.isFinite && source.fps > 0 ? source.fps : 30
        let video = options.video
        let preset = options.preset ?? .preserve
        let color = video?.colorPolicy ?? .preserve
        guard !(source.hdr && color == .rejectHdr) else { throw MediaJob.error("HDR input is rejected by colorPolicy.") }
        let mime: String
        switch video?.codec ?? .auto {
        case .auto: mime = ((source.hdr || source.bitDepth > 8) && color != .sdr) || source.mime == "video/hevc" ? "video/hevc" : "video/avc"
        case .h264: mime = "video/avc"
        case .hevc: mime = "video/hevc"
        }
        guard !((source.hdr || source.bitDepth > 8) && color != .sdr && mime != "video/hevc") else { throw MediaJob.error("HDR preservation requires HEVC output.") }
        let sourceEdge = max(source.width, source.height)
        let presetEdge: Double
        switch preset {
        case .preserve, .high: presetEdge = sourceEdge
        case .auto, .balanced: presetEdge = min(sourceEdge, 1920)
        case .small: presetEdge = min(sourceEdge, 1280)
        }
        let edge = try video?.maxLongEdge.map { try finite($0, min: 2, max: 8192, name: "maxLongEdge") } ?? presetEdge
        let scale = min(1, edge / sourceEdge)
        let width = max(2, Int((source.width * scale).rounded())) / 2 * 2
        let height = max(2, Int((source.height * scale).rounded())) / 2 * 2
        let timing = video?.frameRatePolicy ?? ((video?.maxFps != nil || [.auto, .balanced, .small].contains(preset)) ? .cap : .preserve)
        guard !(timing == .preserve && video?.maxFps != nil) else { throw MediaJob.error("maxFps conflicts with preserve timing.") }
        let requestedFps = try video?.maxFps.map { try finite($0, min: 1, max: 240, name: "maxFps") }
        guard timing != .constant || requestedFps != nil else { throw MediaJob.error("Constant timing requires maxFps.") }
        let fps: Double
        switch timing {
        case .preserve: fps = sourceFps
        case .cap: fps = min(sourceFps, requestedFps ?? (preset == .small ? 24 : 30))
        case .constant: fps = requestedFps!
        }
        var duration = source.durationMs
        if let trim = options.trim {
            guard trim.startMs.isFinite, trim.endMs.isFinite, trim.startMs >= 0, trim.endMs > trim.startMs, trim.endMs <= source.durationMs else { throw MediaJob.error("Invalid trim range.") }
            duration = trim.endMs - trim.startMs
        }
        let audio = options.audio ?? (video?.keepAudio == false ? .mute : .preserve)
        guard !(video?.keepAudio == false && options.audio != nil && options.audio != .mute) else { throw MediaJob.error("Conflicting audio policies.") }
        let audioKbps = try video?.audioBitrateKbps.map { try finite($0, min: 32, max: 512, name: "audioBitrateKbps") } ?? (source.audioChannels > 2 && audio == .preserve ? 384 : 192)
        let bpp: Double
        switch preset { case .preserve: bpp = 0.14; case .high: bpp = 0.12; case .auto, .balanced: bpp = 0.085; case .small: bpp = 0.065 }
        let estimate = min(160_000, max(300, Double(width * height) * fps * bpp * (mime == "video/hevc" ? 0.65 : 1) / 1000))
        var bitrate = try video?.videoBitrateKbps.map { try finite($0, min: 300, max: 160_000, name: "videoBitrateKbps") } ?? (preset == .preserve ? min(160_000, max(estimate, source.bitrateKbps ?? 0)) : estimate)
        let audioRate = source.audioTracks > 0 && audio != .mute ? audioKbps : 0
        if let bytes = video?.targetSizeBytes {
            _ = try finite(bytes, min: 1024, max: 68_719_476_736, name: "targetSizeBytes")
            guard video?.videoBitrateKbps == nil else { throw MediaJob.error("Specify targetSizeBytes or videoBitrateKbps, not both.") }
            bitrate = bytes * 8 * 0.97 / duration - audioRate
            _ = try finite(bitrate, min: 300, max: 160_000, name: "target video bitrate")
        }
        let copy = !forceEncode && options.trim == nil && options.watermark == nil && Double(width) == source.width && Double(height) == source.height && timing == .preserve && mime == source.mime && color != .sdr && video?.videoBitrateKbps == nil && video?.targetSizeBytes == nil && preset == .preserve
        return VideoPlan(width: width, height: height, fps: fps, timing: timing, mime: mime, color: color,
            bitrateKbps: Int(bitrate.rounded()), audioBitrateKbps: Int(audioKbps.rounded()), audio: audio, durationMs: duration,
            estimatedSizeBytes: (bitrate + audioRate) * duration / 8 / 0.97, canCopy: copy)
    }
    @discardableResult static func finite(_ value: Double, min lower: Double, max upper: Double, name: String) throws -> Double {
        guard value.isFinite, value >= lower, value <= upper else { throw MediaJob.error("\(name) must be finite and in [\(lower), \(upper)].") }
        return value
    }
}
