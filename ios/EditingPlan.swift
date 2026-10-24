import Foundation

struct GeometryPlan {
  let x: Double, y: Double, cropWidth: Double, cropHeight: Double
  let rotation: Int, flipX: Bool, flipY: Bool, cover: Bool
  let width: Double, height: Double
  static func make(width: Double, height: Double, options: VideoGeometry?) throws -> GeometryPlan {
    let crop = options?.crop
    let x = crop?.x ?? 0
    let y = crop?.y ?? 0
    let w = crop?.width ?? width
    let h = crop?.height ?? height
    guard [x, y, w, h].allSatisfy({ $0.isFinite }), x >= 0, y >= 0, w >= 2, h >= 2, x + w <= width,
      y + h <= height
    else { throw MediaJob.error("Crop must fit the oriented source bounds.") }
    let rotation = options?.rotation ?? 0
    guard [0.0, 90, 180, 270].contains(rotation) else {
      throw MediaJob.error("rotation must be 0, 90, 180 or 270 clockwise degrees.")
    }
    let swap = rotation == 90 || rotation == 270
    let outputW = options?.width ?? (swap ? h : w)
    let outputH = options?.height ?? (swap ? w : h)
    guard (options?.width == nil) == (options?.height == nil) else {
      throw MediaJob.error("Canvas width and height must be specified together.")
    }
    if options?.width != nil {
      guard
        [outputW, outputH].allSatisfy({
          $0.isFinite && $0 >= 2 && $0 <= 8192 && $0.truncatingRemainder(dividingBy: 2) == 0
        })
      else { throw MediaJob.error("Canvas dimensions must be even integers in [2, 8192].") }
    }
    return GeometryPlan(
      x: x, y: y, cropWidth: w, cropHeight: h, rotation: Int(rotation),
      flipX: options?.flipHorizontal ?? false, flipY: options?.flipVertical ?? false,
      cover: options?.fit == .cover, width: outputW, height: outputH)
  }
}

struct SubtitleCue { let startMs: Double, endMs: Double, text: String }
enum EditingValidation {
  static func validate(_ options: ExportVideoOptions, duration: Double, width: Int, height: Int)
    throws
  {
    _ = try ExportPlanner.finite(options.speed ?? 1, min: 0.25, max: 4, name: "speed")
    guard (options.overlays?.count ?? 0) <= 32 else {
      throw MediaJob.error("At most 32 overlays are supported.")
    }
    let edge = Double(min(width, height))
    func color(_ value: String?) throws {
      if let value {
        guard
          value.range(of: "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$", options: .regularExpression) != nil
        else { throw MediaJob.error("color must be #RRGGBB or #RRGGBBAA.") }
      }
    }
    if let text = options.watermark {
      guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= 4096
      else { throw MediaJob.error("Watermark must contain 1–4096 characters.") }
      let margin = options.watermarkOptions?.margin ?? edge * 0.025
      guard margin.isFinite, margin >= 0, margin * 2 < edge else {
        throw MediaJob.error("Invalid watermark margin.")
      }
      _ = try ExportPlanner.finite(
        options.watermarkOptions?.fontSize ?? edge * 0.05, min: 1, max: 512, name: "fontSize")
      _ = try ExportPlanner.finite(
        options.watermarkOptions?.opacity ?? 1, min: 0, max: 1, name: "opacity")
      try color(options.watermarkOptions?.color)
    }
    for overlay in options.overlays ?? [] {
      guard [overlay.x, overlay.y, overlay.width, overlay.height].allSatisfy({ $0.isFinite }),
        overlay.x >= 0, overlay.y >= 0, overlay.width >= 1, overlay.height >= 1,
        overlay.width <= 8192, overlay.height <= 8192
      else { throw MediaJob.error("Invalid overlay bounds.") }
      guard overlay.x + overlay.width <= Double(width), overlay.y + overlay.height <= Double(height)
      else { throw MediaJob.error("Overlay must fit the output canvas.") }
      if overlay.kind == .text {
        _ = try ExportPlanner.finite(
          overlay.fontSize ?? min(512, max(1, min(overlay.width, overlay.height) * 0.4)), min: 1,
          max: 512, name: "fontSize")
        try color(overlay.color)
      }
      let start = overlay.startMs ?? 0
      let end = overlay.endMs ?? duration
      guard start.isFinite, end.isFinite, start >= 0, end > start, end <= duration + 0.001 else {
        throw MediaJob.error("Overlay time range must fit the output timeline.")
      }
      for (name, value) in [
        ("fadeInMs", overlay.fadeInMs ?? 0), ("fadeOutMs", overlay.fadeOutMs ?? 0),
      ] { _ = try ExportPlanner.finite(value, min: 0, max: end - start, name: name) }
      guard (overlay.fadeInMs ?? 0) + (overlay.fadeOutMs ?? 0) <= end - start else {
        throw MediaJob.error("Overlay fades overlap.")
      }
      _ = try ExportPlanner.finite(overlay.opacity ?? 1, min: 0, max: 1, name: "opacity")
      if overlay.kind == .image {
        guard let uri = overlay.uri, !uri.isEmpty, overlay.text == nil else {
          throw MediaJob.error("Image overlays require uri and no text.")
        }
      } else {
        guard let text = overlay.text, !text.isEmpty, text.utf16.count <= 4096, overlay.uri == nil
        else { throw MediaJob.error("Text overlays require 1–4096 characters and no uri.") }
      }
    }
    if let audio = options.audioEdit {
      let gains: [(String, Double)] = [
        ("volume", audio.volume ?? 1), ("musicVolume", audio.musicVolume ?? 1),
      ]
      for (name, value) in gains { _ = try ExportPlanner.finite(value, min: 0, max: 1, name: name) }
      let timings: [(String, Double)] = [
        ("fadeInMs", audio.fadeInMs ?? 0), ("fadeOutMs", audio.fadeOutMs ?? 0),
        ("musicStartMs", audio.musicStartMs ?? 0),
      ]
      for (name, value) in timings {
        _ = try ExportPlanner.finite(value, min: 0, max: duration, name: name)
      }
      guard (audio.fadeInMs ?? 0) + (audio.fadeOutMs ?? 0) <= duration else {
        throw MediaJob.error("Audio fades overlap.")
      }
      if let index = audio.trackIndex {
        guard index.isFinite, index >= 0, index.rounded(.down) == index else {
          throw MediaJob.error("trackIndex must be a nonnegative integer.")
        }
      }
      guard options.audio != .mute, options.video?.keepAudio != false else {
        throw MediaJob.error("audioEdit conflicts with muted audio.")
      }
    }
    if let subtitles = options.subtitles {
      let margin = subtitles.margin ?? edge * 0.05
      guard margin.isFinite, margin >= 0, margin * 2 < edge else {
        throw MediaJob.error("Invalid subtitle margin.")
      }
      _ = try ExportPlanner.finite(
        subtitles.fontSize ?? edge * 0.045, min: 1, max: 512, name: "fontSize")
      try color(subtitles.color)
      _ = try cues(subtitles, duration: duration)
    }
    guard options.copyPolicy != .requireCopy || options.trim == nil || options.trimMode == .keyframe
    else { throw MediaJob.error("Stream-copy trimming requires keyframe mode.") }
  }
  static func cues(_ options: SubtitleOptions, duration: Double) throws -> [SubtitleCue] {
    guard options.content.utf8.count <= 1_048_576 else {
      throw MediaJob.error("Subtitles exceed 1 MiB.")
    }
    let text = options.content.replacingOccurrences(of: "\u{FEFF}", with: "").replacingOccurrences(
      of: "\r\n", with: "\n"
    ).replacingOccurrences(
      of: "\r", with: "\n"
    ).replacingOccurrences(of: "\n[ \t]+\n", with: "\n\n", options: .regularExpression)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    if options.format == .vtt && !text.hasPrefix("WEBVTT") {
      throw MediaJob.error("Missing WEBVTT header.")
    }
    var result: [SubtitleCue] = []
    for block in text.components(separatedBy: "\n\n") {
      let header = block.trimmingCharacters(in: .whitespacesAndNewlines)
      if header.hasPrefix("NOTE") || header.hasPrefix("STYLE") || header.hasPrefix("REGION") {
        continue
      }
      let lines = block.components(separatedBy: "\n")
      guard let timing = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
      let parts = lines[timing].components(separatedBy: "-->")
      guard parts.count == 2 else { throw MediaJob.error("Invalid subtitle timing.") }
      let start = try timestamp(parts[0].trimmingCharacters(in: .whitespaces))
      let end = try timestamp(
        parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: .whitespaces)[0])
      guard start >= 0, end > start else { throw MediaJob.error("Invalid subtitle range.") }
      let body = plainText(lines.dropFirst(timing + 1).joined(separator: "\n"))
      guard !body.isEmpty, body.utf16.count <= 4096 else {
        throw MediaJob.error("Invalid subtitle text.")
      }
      if start < duration {
        result.append(SubtitleCue(startMs: start, endMs: min(end, duration), text: body))
      }
      guard result.count <= 2000 else {
        throw MediaJob.error("At most 2000 subtitle cues are supported.")
      }
    }
    guard !result.isEmpty else {
      throw MediaJob.error("No subtitle cues intersect the output timeline.")
    }
    result.sort { $0.startMs < $1.startMs }
    var ends: [Double] = []
    for cue in result {
      ends.removeAll { $0 <= cue.startMs }
      ends.append(cue.endMs)
      guard ends.count <= 4 else {
        throw MediaJob.error("At most four simultaneous subtitle cues are supported.")
      }
    }
    return result
  }
  private static func plainText(_ value: String) -> String {
    var result = ""
    var inTag = false
    for char in value {
      if char == "<" {
        inTag = true
      } else if char == ">", inTag {
        inTag = false
      } else if !inTag {
        result.append(char)
      }
    }
    return result.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(
      of: "&lt;", with: "<"
    ).replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&quot;", with: "\"")
      .replacingOccurrences(of: "&nbsp;", with: " ")
  }
  private static func timestamp(_ text: String) throws -> Double {
    guard
      text.range(
        of: "^(?:[0-9]{2,}:)?[0-5][0-9]:[0-5][0-9][.,][0-9]{3}$", options: .regularExpression)
        != nil
    else { throw MediaJob.error("Invalid subtitle timestamp.") }
    let pieces = text.replacingOccurrences(of: ",", with: ".").split(separator: ":")
    guard pieces.count == 2 || pieces.count == 3, let seconds = Double(pieces.last!),
      let minutes = Double(pieces[pieces.count - 2]),
      let hours = pieces.count == 3 ? Double(pieces[0]) : 0,
      seconds.isFinite, minutes.isFinite, hours.isFinite, seconds >= 0, seconds < 60, minutes >= 0,
      minutes < 60, hours >= 0
    else { throw MediaJob.error("Invalid subtitle timestamp.") }
    return (hours * 3600 + minutes * 60 + seconds) * 1000
  }
}

struct TimelineLayout {
  let starts: [Double], durationMs: Double
  static func make(durations: [Double], transitions: [Double]) throws -> TimelineLayout {
    guard !durations.isEmpty, durations.count <= 64, transitions.count == durations.count,
      durations.allSatisfy({ $0.isFinite && $0 > 0 })
    else { throw MediaJob.error("A timeline requires 1–64 positive finite clip durations.") }
    for i in durations.indices {
      let t = transitions[i]
      guard t.isFinite, t >= 0, t <= durations[i],
        i < durations.count - 1 ? t <= durations[i + 1] : t == 0,
        i == 0 || transitions[i - 1] + t <= durations[i]
      else { throw MediaJob.error("Invalid dissolve duration or three overlapping clips.") }
    }
    var starts = [0.0]
    for i in durations.indices.dropFirst() {
      starts.append(starts[i - 1] + durations[i - 1] - transitions[i - 1])
    }
    return TimelineLayout(starts: starts, durationMs: starts.last! + durations.last!)
  }
}
