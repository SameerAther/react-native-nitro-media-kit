import Darwin
import Foundation

// Compile the real planner/DSP with each revision's host boundary types.
// No codecs, UIKit, simulator, bridge mocks or disk I/O enter timed sections.
enum MediaJob {
  static func error(_ message: String) -> NSError {
    NSError(domain: "HOST_BENCHMARK", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

private struct PlanSnapshot: Encodable {
  let width: Int, height: Int, bitrateKbps: Int, audioBitrateKbps: Int
  let fps: Double, durationMs: Double, estimatedSizeBytes: Double
  let mime: String, timing: String, audio: String, color: String
  let canCopy: Bool
  init(_ p: VideoPlan) {
    width = p.width; height = p.height; bitrateKbps = p.bitrateKbps
    audioBitrateKbps = p.audioBitrateKbps; fps = p.fps; durationMs = p.durationMs
    estimatedSizeBytes = p.estimatedSizeBytes; mime = p.mime; canCopy = p.canCopy
    timing = String(describing: p.timing); audio = String(describing: p.audio)
    color = String(describing: p.color)
  }
}

private struct PlannerMeasurement: Encodable {
  let name: String, iterations: Int, elapsedMs: Double, nsPerOperation: Double
  let checksum: Double, output: PlanSnapshot
}

private func source(hdr: Bool) -> VideoSource {
  VideoSource(
    width: 3840, height: 2160, fps: 60000.0 / 1001, durationMs: 10000,
    sizeBytes: 50_000_000, audioTracks: 1, extensionName: "mp4",
    mime: hdr ? "video/hevc" : "video/avc", hdr: hdr, bitDepth: hdr ? 10 : 8,
    bitrateKbps: 40000, audioChannels: 6, audioSampleRate: 48000,
    colorPrimaries: nil, colorTransfer: nil, colorMatrix: nil)
}

@inline(never)
private func evaluate(_ source: VideoSource, _ options: ExportVideoOptions) throws -> VideoPlan {
  try ExportPlanner.plan(source, options: options)
}

private func plans(iterations: Int) throws -> [PlannerMeasurement] {
  let cases: [(String, VideoSource, ExportVideoOptions)] = [
    ("preserve-4k", source(hdr: false), ExportVideoOptions()),
    ("balanced-4k", source(hdr: false), ExportVideoOptions(preset: .balanced)),
    ("small-4k", source(hdr: false), ExportVideoOptions(preset: .small)),
    (
      "target-size-trim", source(hdr: false),
      ExportVideoOptions(
        trim: VideoSegment(startMs: 2000, endMs: 7000),
        video: CompressVideoOptions(targetSizeBytes: 5_000_000))
    ),
    ("watermark-4k", source(hdr: false), ExportVideoOptions(watermark: "Benchmark watermark")),
    ("hdr-preserve-4k", source(hdr: true), ExportVideoOptions()),
    (
      "constant-24fps", source(hdr: false),
      ExportVideoOptions(
        video: CompressVideoOptions(maxFps: 24, frameRatePolicy: .constant))
    ),
  ]
  return try cases.map { name, input, options in
    var checksum = 0.0
    for _ in 0..<10000 {
      checksum += Double(try evaluate(input, options).width)
    }
    let start = DispatchTime.now().uptimeNanoseconds
    for _ in 0..<iterations {
      let p = try evaluate(input, options)
      checksum += Double(p.width + p.height + p.bitrateKbps) + p.fps + p.durationMs
    }
    let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start)
    return PlannerMeasurement(
      name: name, iterations: iterations, elapsedMs: elapsed / 1e6,
      nsPerOperation: elapsed / Double(iterations), checksum: checksum,
      output: PlanSnapshot(try evaluate(input, options)))
  }
}

#if NMK_AFTER
  private struct LoudnessMeasurement: Encodable {
    let name: String, sampleRate: Int, channels: Int, durationMs: Double, elapsedMs: Double
    let realtimeMultiple: Double, lufs: Double?, samplePeakDb: Double?, truePeakDb: Double?
  }

  private func tone(rate: Int, channels: Int) -> [Float] {
    // 100 ms contains an integer number of tone periods, so repeating chunks is continuous.
    (0..<(rate / 10 * channels)).map { i in
      Float(0.1 * sin(2 * Double.pi * 1000 * Double(i / channels) / Double(rate)))
    }
  }

  private func loudness(rate: Int, channels: Int, seconds: Int) throws -> LoudnessMeasurement {
    let samples = tone(rate: rate, channels: channels)
    let start = DispatchTime.now().uptimeNanoseconds
    let meter = try LoudnessMeter(sampleRate: rate, channels: channels)
    for _ in 0..<(seconds * 10) {
      samples.withUnsafeBufferPointer { meter.add($0) }
    }
    let result = meter.finish()
    let elapsedMs = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6
    precondition(abs(result.durationMs - Double(seconds) * 1000) < 0.001)
    precondition(abs(result.lufs! - (channels == 1 ? -23 : -23 + 10 * log10(2))) < 0.12)
    return LoudnessMeasurement(
      name: "\(rate)hz-\(channels)ch-\(seconds)s", sampleRate: rate, channels: channels,
      durationMs: result.durationMs, elapsedMs: elapsedMs,
      realtimeMultiple: result.durationMs / elapsedMs, lufs: result.lufs,
      samplePeakDb: result.samplePeakDb, truePeakDb: result.truePeakDb)
  }
#endif

@main
private enum HostBenchmark {
  static func main() throws {
    let args = CommandLine.arguments
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    let data: Data
    switch args[1] {
    case "planner":
      data = try encoder.encode(plans(iterations: Int(args[2])!))
    #if NMK_AFTER
      case "loudness":
        // Warm up before measuring a streaming, fixed-size PCM buffer.
        _ = try loudness(rate: 48000, channels: 2, seconds: 1)
        data = try encoder.encode(
          loudness(rate: Int(args[2])!, channels: Int(args[3])!, seconds: Int(args[4])!))
      case "tone":
        let samples = tone(rate: 48000, channels: 2)
        let url = URL(fileURLWithPath: args[2])
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let file = try FileHandle(forWritingTo: url)
        for _ in 0..<30 { try file.write(contentsOf: samples.withUnsafeBytes { Data($0) }) }
        try file.close()
        data = try encoder.encode(["fixture": "3 seconds, 48kHz stereo, 1kHz sine at -20dBFS"])
    #endif
    default: fatalError("Unknown benchmark")
    }
    print(String(decoding: data, as: UTF8.self))
  }
}
