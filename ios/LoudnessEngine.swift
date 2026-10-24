@preconcurrency import AVFoundation
import Foundation
import NitroModules

extension NitroMediaKit {
  func analyzeAudio(uri: String, options: AudioAnalysisOptions) -> Promise<LoudnessAnalysis> {
    owned(limits: options.limits) { [self] job in
      let result = try await measureAudio(path: job.resolve(uri), options: options)
      _ = try job.commitPaths([])
      return result
    }
  }
  func measureAudio(path: String, options: AudioAnalysisOptions) async throws -> LoudnessAnalysis {
    let control = try currentControl()
    try await MediaWorkGate.audioAnalysis.acquire(control)
    defer { Task { await MediaWorkGate.audioAnalysis.release() } }
    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
    let tracks = try await asset.loadTracks(withMediaType: .audio)
    let index = options.trackIndex ?? 0
    guard index.isFinite, index >= 0, index.rounded(.down) == index, index < Double(tracks.count)
    else { throw MediaJob.error("Requested audio track does not exist.") }
    let track = tracks[Int(index)]
    let formats = try await track.load(.formatDescriptions)
    guard
      let stream = formats.first.flatMap({
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee
      }), (1...2).contains(Int(stream.mChannelsPerFrame))
    else {
      throw MediaJob.error(
        "Loudness analysis requires mono/stereo; convert layouts explicitly first.")
    }
    let rate = Int(stream.mSampleRate)
    let channels = Int(stream.mChannelsPerFrame)
    let meter = try LoudnessMeter(sampleRate: rate, channels: channels)
    let reader = try AVAssetReader(asset: asset)
    defer { reader.cancelReading() }
    if let trim = options.trim {
      let duration = try await asset.load(.duration).seconds * 1000
      guard trim.startMs.isFinite, trim.endMs.isFinite, trim.startMs >= 0,
        trim.endMs > trim.startMs, trim.endMs <= duration
      else { throw MediaJob.error("Invalid audio analysis trim.") }
      reader.timeRange = segmentRange(trim)
    }
    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate,
        AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsNonInterleaved: false,
      ])
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else { throw MediaJob.error("Cannot configure loudness reader.") }
    reader.add(output)
    let hook = control.onCancel { reader.cancelReading() }
    defer { control.removeHook(hook) }
    guard reader.startReading() else {
      throw reader.error ?? MediaJob.error("Cannot start loudness reader.")
    }
    while let sample = output.copyNextSampleBuffer() {
      try control.check()
      try autoreleasepool {
        guard let data = CMSampleBufferGetDataBuffer(sample) else {
          throw MediaJob.error("Missing PCM samples.")
        }
        let bytes = CMBlockBufferGetDataLength(data)
        var samples = [Float](repeating: 0, count: bytes / MemoryLayout<Float>.size)
        let status = samples.withUnsafeMutableBytes {
          CMBlockBufferCopyDataBytes(
            data, atOffset: 0, dataLength: bytes, destination: $0.baseAddress!)
        }
        guard status == noErr else { throw MediaJob.error("Cannot read PCM samples.") }
        samples.withUnsafeBufferPointer { meter.add($0) }
        guard !meter.exceededDurationLimit else {
          throw MediaJob.error("Audio analysis is limited to 24 hours per request.")
        }
      }
    }
    try control.check()
    guard reader.status == .completed else {
      throw reader.error ?? MediaJob.error("Loudness analysis failed.")
    }
    let result = meter.finish()
    return LoudnessAnalysis(
      integratedLufs: result.lufs, samplePeakDbfs: result.samplePeakDb,
      truePeakDbtp: result.truePeakDb, durationMs: result.durationMs, channels: Double(channels),
      sampleRate: Double(rate))
  }
  func normalizeAudio(uri: String, options: NormalizeAudioOptions) -> Promise<NormalizedAudioResult>
  {
    owned(limits: options.limits) { [self] job in
      let target = try ExportPlanner.finite(
        options.targetLufs ?? -16, min: -36, max: -5, name: "targetLufs")
      let ceiling = try ExportPlanner.finite(
        options.maxTruePeakDbtp ?? -1, min: -12, max: 0, name: "maxTruePeakDbtp")
      let bitrate = try ExportPlanner.finite(
        options.bitrateKbps ?? 192, min: 32, max: 512, name: "bitrateKbps")
      let path = try await job.resolve(uri)
      let before = try await measureAudio(
        path: path,
        options: AudioAnalysisOptions(
          trackIndex: options.trackIndex, trim: options.trim, limits: options.limits))
      guard before.durationMs >= 400 else {
        throw MediaJob.error("Loudness normalization requires at least 400ms of audio.")
      }
      let desired = before.integratedLufs.map { target - $0 } ?? 0
      var gain = min(desired, before.truePeakDbtp.map { ceiling - $0 } ?? desired)
      var result: (URL, LoudnessAnalysis)?
      for _ in 0..<3 {
        let file = try await withMediaEncoder {
          try await self.writeNormalizedAudio(
            path: path, job: job, options: options, rate: before.sampleRate,
            channels: Int(before.channels), gain: pow(10, gain / 20), bitrate: Int(bitrate))
        }
        let after = try await measureAudio(
          path: file.path, options: AudioAnalysisOptions(trackIndex: nil, trim: nil, limits: nil))
        guard after.channels == before.channels else {
          throw MediaJob.error("Encoder changed the normalized audio channel count.")
        }
        if (after.truePeakDbtp ?? -.infinity) <= ceiling + 0.05 {
          result = (file, after)
          break
        }
        try FileManager.default.removeItem(at: file)
        gain -= (after.truePeakDbtp! - ceiling) + 0.5
      }
      guard let (file, after) = result else {
        throw MediaJob.error("Cannot meet the requested encoded peak ceiling.")
      }
      let bytes = fileSizeBytes(atPath: file.path) ?? 0
      let mapping = try job.commitPaths([file.path])
      let audio = AudioExportResult(
        outputUri: mapping[file.path]!, durationMs: after.durationMs, sizeBytes: bytes,
        channels: after.channels, sampleRate: after.sampleRate, codec: "audio/mp4a-latm")
      return NormalizedAudioResult(
        audio: audio, before: before, after: after, appliedGainDb: gain,
        limitedByPeak: gain < desired - 0.01)
    }
  }
  private func writeNormalizedAudio(
    path: String, job: MediaJob, options: NormalizeAudioOptions, rate: Double, channels: Int,
    gain: Double, bitrate: Int
  ) async throws -> URL {
    let source = AVURLAsset(url: URL(fileURLWithPath: path))
    let tracks = try await source.loadTracks(withMediaType: .audio)
    let track = tracks[Int(options.trackIndex ?? 0)]
    let duration = try await source.load(.duration)
    let range =
      options.trim.map { segmentRange($0) } ?? CMTimeRange(start: .zero, duration: duration)
    let composition = AVMutableComposition()
    let audio = composition.addMutableTrack(
      withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
    let selected = CMTimeRangeGetIntersection(try await track.load(.timeRange), otherRange: range)
    guard selected.duration > .zero else {
      throw MediaJob.error("Selected range contains no audio.")
    }
    try await insertTrackRange(
      selected, from: track, into: audio, at: CMTimeSubtract(selected.start, range.start))
    let reader = try AVAssetReader(asset: composition)
    let file = try job.output("m4a")
    let writer = try AVAssetWriter(outputURL: file, fileType: .m4a)
    var finished = false
    defer {
      if !finished {
        reader.cancelReading()
        writer.cancelWriting()
      }
    }
    let output = AVAssetReaderAudioMixOutput(
      audioTracks: [audio],
      audioSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate,
        AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsNonInterleaved: false,
      ])
    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: channels,
      AVEncoderBitRateKey: bitrate * 1000,
    ]
    guard writer.canApply(outputSettings: settings, forMediaType: .audio) else {
      throw MediaJob.error("Requested audio encoding is unsupported.")
    }
    let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
    guard reader.canAdd(output), writer.canAdd(input) else {
      throw MediaJob.error("Cannot configure normalization.")
    }
    reader.add(output)
    writer.add(input)
    guard reader.startReading(), writer.startWriting() else {
      throw reader.error ?? writer.error ?? MediaJob.error("Cannot start normalization.")
    }
    writer.startSession(atSourceTime: .zero)
    try await transferSamples(
      reader: reader, writer: writer, pairs: [(output, input)], duration: range.duration.seconds,
      audioGain: gain)
    finished = true
    return file
  }
}
