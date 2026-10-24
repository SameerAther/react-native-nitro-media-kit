@preconcurrency import AVFoundation
import Foundation
import NitroModules

extension NitroMediaKit {
  func extractAudio(uri: String, options: ExtractAudioOptions) -> Promise<AudioExportResult> {
    owned { [self] job in
      let asset = AVURLAsset(url: URL(fileURLWithPath: try await job.resolve(uri)))
      let tracks = try await asset.loadTracks(withMediaType: .audio)
      let index = options.trackIndex ?? 0
      guard index.isFinite, index >= 0, index.rounded(.down) == index, index < Double(tracks.count)
      else { throw MediaJob.error("Requested audio track does not exist.") }
      let track = tracks[Int(index)]
      let available = try await asset.load(.duration)
      let range =
        options.trim.map { segmentRange($0) } ?? CMTimeRange(start: .zero, duration: available)
      guard range.start >= .zero, range.duration > .zero, CMTimeRangeGetEnd(range) <= available
      else { throw MediaJob.error("Invalid audio trim range.") }
      let kbps = try ExportPlanner.finite(
        options.bitrateKbps ?? 192, min: 32, max: 512, name: "bitrateKbps")
      let composition = AVMutableComposition()
      let audio = composition.addMutableTrack(
        withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
      let selected = CMTimeRangeGetIntersection(try await track.load(.timeRange), otherRange: range)
      guard selected.duration > .zero else {
        throw MediaJob.error("Selected range contains no audio.")
      }
      try await insertTrackRange(
        selected, from: track, into: audio, at: CMTimeSubtract(selected.start, range.start))
      let descriptions = try await track.load(.formatDescriptions)
      let stream = descriptions.first.flatMap {
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee
      }
      let channels = Int(stream?.mChannelsPerFrame ?? 2)
      let rate = stream?.mSampleRate ?? 48_000
      var readSettings: [String: Any] = [
        AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate,
        AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
        AVLinearPCMIsNonInterleaved: false,
      ]
      var writeSettings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: channels,
        AVEncoderBitRateKey: Int(kbps) * 1000,
      ]
      if channels > 2 {
        var size = 0
        guard let description = descriptions.first,
          let layout = CMAudioFormatDescriptionGetChannelLayout(description, sizeOut: &size),
          size > 0
        else { throw MediaJob.error("Audio layout is unavailable.") }
        let data = Data(bytes: layout, count: size)
        readSettings[AVChannelLayoutKey] = data
        writeSettings[AVChannelLayoutKey] = data
      }
      let output = try job.output("m4a")
      try await withMediaEncoder {
        let reader = try AVAssetReader(asset: composition)
        let writer = try AVAssetWriter(outputURL: output, fileType: .m4a)
        var finished = false
        defer {
          if !finished {
            reader.cancelReading()
            writer.cancelWriting()
          }
        }
        guard writer.canApply(outputSettings: writeSettings, forMediaType: .audio) else {
          throw MediaJob.error("Requested audio encoding is unsupported.")
        }
        let samples = AVAssetReaderAudioMixOutput(audioTracks: [audio], audioSettings: readSettings)
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: writeSettings)
        guard reader.canAdd(samples), writer.canAdd(input) else {
          throw MediaJob.error("Cannot configure audio extraction.")
        }
        reader.add(samples)
        writer.add(input)
        guard reader.startReading(), writer.startWriting() else {
          throw reader.error ?? writer.error ?? MediaJob.error("Cannot start audio extraction.")
        }
        writer.startSession(atSourceTime: .zero)
        try await transferSamples(
          reader: reader, writer: writer, pairs: [(samples, input)],
          duration: range.duration.seconds)
        finished = true
      }
      let bytes = fileSizeBytes(atPath: output.path) ?? 0
      let actual = try await AVURLAsset(url: output).load(.duration).seconds * 1000
      let mapping = try job.commitPaths([output.path])
      return AudioExportResult(
        outputUri: mapping[output.path]!, durationMs: actual, sizeBytes: bytes,
        channels: Double(channels), sampleRate: rate, codec: "audio/mp4a-latm")
    }
  }
  func audioMix(composition: AVMutableComposition, options: ExportVideoOptions, durationMs: Double)
    async throws -> AVAudioMix?
  {
    guard let edit = options.audioEdit else { return nil }
    let duration = CMTime(seconds: durationMs / 1000, preferredTimescale: 1_000_000)
    var musicID: CMPersistentTrackID?
    if let uri = edit.musicUri {
      let asset = AVURLAsset(url: URL(fileURLWithPath: try await getLocalFilePath(uri)))
      guard let source = try await asset.loadTracks(withMediaType: .audio).first else {
        throw MediaJob.error("Music contains no audio.")
      }
      let existing = try await composition.loadTracks(withMediaType: .audio).first
      let existingFormats = try await existing?.load(.formatDescriptions) ?? []
      let musicFormats = try await source.load(.formatDescriptions)
      let existingChannels = existingFormats.first.flatMap {
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee.mChannelsPerFrame
      }
      let musicChannels = musicFormats.first.flatMap {
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee.mChannelsPerFrame
      }
      guard options.audio == .stereo || existingChannels == nil || existingChannels == musicChannels
      else {
        throw MediaJob.error("Music and source channel layouts differ; select stereo explicitly.")
      }
      let available = try await asset.load(.duration).seconds * 1000
      let start = edit.musicStartMs ?? 0
      let trim =
        edit.musicTrim ?? VideoSegment(startMs: 0, endMs: min(available, durationMs - start))
      guard trim.startMs.isFinite, trim.endMs.isFinite, trim.startMs >= 0,
        trim.endMs > trim.startMs, trim.endMs <= available
      else { throw MediaJob.error("Invalid music trim range.") }
      let end = min(trim.endMs, trim.startMs + durationMs - start)
      guard end > trim.startMs else { throw MediaJob.error("Music starts after the output ends.") }
      let music = composition.addMutableTrack(
        withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
      let selected = segmentRange(VideoSegment(startMs: trim.startMs, endMs: end))
      let intersection = CMTimeRangeGetIntersection(
        try await source.load(.timeRange), otherRange: selected)
      if intersection.duration > .zero {
        try await insertTrackRange(
          intersection, from: source, into: music,
          at: CMTimeAdd(
            CMTime(seconds: start / 1000, preferredTimescale: 1_000_000),
            CMTimeSubtract(intersection.start, selected.start)))
      }
      musicID = music.trackID
    }
    let tracks = try await composition.loadTracks(withMediaType: .audio)
    let mix = AVMutableAudioMix()
    mix.inputParameters = tracks.map { track in
      let input = AVMutableAudioMixInputParameters(track: track)
      let isMusic = track.trackID == musicID
      let gain = Float(isMusic ? edit.musicVolume ?? 1 : edit.volume ?? 1)
      input.setVolume(gain, at: .zero)
      if !isMusic {
        let fadeIn = edit.fadeInMs ?? 0
        let fadeOut = edit.fadeOutMs ?? 0
        if fadeIn > 0 {
          input.setVolumeRamp(
            fromStartVolume: 0, toEndVolume: gain,
            timeRange: CMTimeRange(
              start: .zero, duration: CMTime(seconds: fadeIn / 1000, preferredTimescale: 1_000_000))
          )
        }
        if fadeOut > 0 {
          input.setVolumeRamp(
            fromStartVolume: gain, toEndVolume: 0,
            timeRange: CMTimeRange(
              start: CMTimeSubtract(
                duration, CMTime(seconds: fadeOut / 1000, preferredTimescale: 1_000_000)),
              duration: CMTime(seconds: fadeOut / 1000, preferredTimescale: 1_000_000)))
        }
      }
      return input
    }
    return mix
  }
}
