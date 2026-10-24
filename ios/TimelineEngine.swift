@preconcurrency import AVFoundation
import CoreImage
import Foundation

extension NitroMediaKit {
  func timeline(clips: [TimelineClip], options: ExportVideoOptions) async throws -> MediaInfoResult
  {
    guard !clips.isEmpty, clips.count <= 64 else {
      throw MediaJob.error("A timeline requires 1–64 clips.")
    }
    guard options.trim == nil, options.speed == nil, options.copyPolicy != .requireCopy else {
      throw MediaJob.error(
        "Timeline trim/speed belong on individual clips; timelines require encoding.")
    }
    guard options.geometry?.crop == nil, (options.geometry?.rotation ?? 0) == 0,
      options.geometry?.flipHorizontal != true, options.geometry?.flipVertical != true
    else { throw MediaJob.error("Use per-clip geometry for crop, rotation and flips.") }
    let control = try currentControl()
    var paths: [String] = []
    var sources: [VideoSource] = []
    var durations: [Double] = []
    for clip in clips {
      let path = try await getLocalFilePath(clip.uri)
      let source = try await detectVideoInfo(atPath: path)
      paths.append(path)
      sources.append(source)
      durations.append(
        try ExportPlanner.plan(
          source,
          options: ExportVideoOptions().editing(
            trim: clip.trim, speed: clip.speed, geometry: clip.geometry)
        ).durationMs)
    }
    let transitions = clips.map { $0.transitionMs ?? 0 }
    let layout = try TimelineLayout.make(durations: durations, transitions: transitions)
    let starts = layout.starts
    let total = layout.durationMs
    let first = sources[0]
    let hdr = sources.first { $0.hdr }
    let audioSource = sources.first { $0.audioTracks > 0 }
    guard
      options.audio == .stereo
        || sources.filter({ $0.audioTracks > 0 }).allSatisfy({
          $0.audioChannels == audioSource?.audioChannels
        })
    else { throw MediaJob.error("Timeline audio layouts differ; select stereo explicitly.") }
    let aggregate = VideoSource(
      width: first.width, height: first.height,
      fps: zip(sources, clips).map { $0.fps * ($1.speed ?? 1) }.max()!, durationMs: total,
      sizeBytes: sources.reduce(0) { $0 + $1.sizeBytes }, audioTracks: audioSource == nil ? 0 : 1,
      extensionName: "mp4", mime: hdr == nil ? first.mime : "video/hevc", hdr: hdr != nil,
      bitDepth: sources.map { $0.bitDepth }.max()!,
      bitrateKbps: sources.compactMap { $0.bitrateKbps }.max(),
      audioChannels: audioSource?.audioChannels ?? 0,
      audioSampleRate: audioSource?.audioSampleRate ?? 48_000,
      colorPrimaries: hdr?.colorPrimaries ?? first.colorPrimaries,
      colorTransfer: hdr?.colorTransfer ?? first.colorTransfer,
      colorMatrix: hdr?.colorMatrix ?? first.colorMatrix)
    let plan = try ExportPlanner.plan(aggregate, options: options, forceEncode: true)
    try DeviceCapabilities.check(plan)
    let composition = AVMutableComposition()
    let lanes = transitions.contains(where: { $0 > 0 }) ? 2 : 1
    let videos = (0..<lanes).map { _ in
      composition.addMutableTrack(
        withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
    }
    let audios =
      plan.audio == .mute
      ? []
      : (0..<lanes).map { _ in
        composition.addMutableTrack(
          withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
      }
    var layers: [TimelineLayer] = []
    for i in clips.indices {
      try control.check()
      let clip = clips[i]
      let asset = AVURLAsset(url: URL(fileURLWithPath: paths[i]))
      let duration = try await asset.load(.duration)
      let range =
        clip.trim.map { segmentRange($0) } ?? CMTimeRange(start: .zero, duration: duration)
      let start = CMTime(seconds: starts[i] / 1000, preferredTimescale: 1_000_000)
      let outputDuration = CMTime(seconds: durations[i] / 1000, preferredTimescale: 1_000_000)
      let video = videos[i % lanes]
      guard let source = try await asset.loadTracks(withMediaType: .video).first else {
        throw MediaJob.error("No video track.")
      }
      let videoRange = CMTimeRangeGetIntersection(
        try await source.load(.timeRange), otherRange: range)
      guard videoRange.duration > .zero else { throw MediaJob.error("Clip contains no video.") }
      try await insertTrackRange(
        videoRange, from: source, into: video,
        at: CMTimeAdd(start, CMTimeSubtract(videoRange.start, range.start)))
      if CMTimeRangeGetEnd(try await video.load(.timeRange)) < CMTimeAdd(start, range.duration) {
        video.insertEmptyTimeRange(
          CMTimeRange(
            start: CMTimeRangeGetEnd(try await video.load(.timeRange)),
            end: CMTimeAdd(start, range.duration)))
      }
      if (clip.speed ?? 1) != 1 {
        video.scaleTimeRange(
          CMTimeRange(start: start, duration: range.duration), toDuration: outputDuration)
      }
      let sourceAudios = try await asset.loadTracks(withMediaType: .audio)
      let index = options.audioEdit?.trackIndex ?? 0
      guard index.isFinite, index >= 0, index.rounded(.down) == index,
        sourceAudios.isEmpty
          || index < Double(sourceAudios.count)
      else { throw MediaJob.error("Requested audio track does not exist.") }
      if !audios.isEmpty, !sourceAudios.isEmpty {
        let source = sourceAudios[Int(index)]
        let audio = audios[i % lanes]
        let selected = CMTimeRangeGetIntersection(
          try await source.load(.timeRange), otherRange: range)
        if selected.duration > .zero {
          try await insertTrackRange(
            selected, from: source, into: audio,
            at: CMTimeAdd(start, CMTimeSubtract(selected.start, range.start)))
        }
        let end = CMTimeRangeGetEnd(try await audio.load(.timeRange))
        let desired = CMTimeAdd(start, range.duration)
        if end < desired { audio.insertEmptyTimeRange(CMTimeRange(start: end, end: desired)) }
        if (clip.speed ?? 1) != 1 {
          audio.scaleTimeRange(
            CMTimeRange(start: start, duration: range.duration), toDuration: outputDuration)
        }
      }
      layers.append(
        TimelineLayer(
          trackID: video.trackID, transform: try await source.load(.preferredTransform),
          geometry: try GeometryPlan.make(
            width: sources[i].width, height: sources[i].height, options: clip.geometry),
          startMs: starts[i], durationMs: durations[i], fadeInMs: i > 0 ? transitions[i - 1] : 0))
    }
    let mix =
      try await audioMix(composition: composition, options: options, durationMs: total)
      as? AVMutableAudioMix ?? AVMutableAudioMix()
    var params = mix.inputParameters.filter { parameter in
      !audios.contains { $0.trackID == parameter.trackID }
    }
    for lane in 0..<audios.count {
      let parameter = AVMutableAudioMixInputParameters(track: audios[lane])
      parameter.setVolume(0, at: .zero)
      for i in clips.indices where i % lanes == lane {
        let incoming = i > 0 ? transitions[i - 1] : 0
        let outgoing = transitions[i]
        func gain(_ time: Double) -> Float {
          let local = time - starts[i]
          let incomingGain: Double = incoming > 0 ? max(0, local / incoming) : 1
          let outgoingGain: Double = outgoing > 0 ? max(0, (durations[i] - local) / outgoing) : 1
          let globalIn = options.audioEdit?.fadeInMs ?? 0
          let globalOut = options.audioEdit?.fadeOutMs ?? 0
          let globalInGain: Double = globalIn > 0 ? max(0, time / globalIn) : 1
          let globalOutGain: Double = globalOut > 0 ? max(0, (total - time) / globalOut) : 1
          let clipGain = min(1, incomingGain, outgoingGain)
          let globalGain = min(1, globalInGain, globalOutGain)
          return Float((options.audioEdit?.volume ?? 1) * clipGain * globalGain)
        }
        let end = starts[i] + durations[i]
        let knots = Set(
          [
            starts[i], starts[i] + incoming, end - outgoing, end, options.audioEdit?.fadeInMs ?? 0,
            total - (options.audioEdit?.fadeOutMs ?? 0),
          ].filter { $0 >= starts[i] && $0 <= end }
        ).sorted()
        for (a, b) in zip(knots, knots.dropFirst()) where b > a {
          let steps = min(128, max(1, Int(ceil((b - a) / 50))))
          for step in 0..<steps {
            let t0 = a + (b - a) * Double(step) / Double(steps)
            let t1 = a + (b - a) * Double(step + 1) / Double(steps)
            parameter.setVolumeRamp(
              fromStartVolume: gain(t0), toEndVolume: gain(t1),
              timeRange: CMTimeRange(
                start: CMTime(seconds: t0 / 1000, preferredTimescale: 1_000_000),
                end: CMTime(seconds: t1 / 1000, preferredTimescale: 1_000_000)))
          }
        }
      }
      params.append(parameter)
    }
    mix.inputParameters = params
    return try await withMediaEncoder { [self] in
      let overlays = try await prepareOverlays(options: options, plan: plan)
      let size = CGSize(width: plan.width, height: plan.height)
      let boundaries = Set(starts + zip(starts, durations).map(+)).sorted()
      let instructions = zip(boundaries, boundaries.dropFirst()).compactMap {
        a, b -> TimelineInstruction? in
        guard b > a else { return nil }
        let active = layers.filter {
          $0.startMs <= a + 0.0001 && $0.startMs + $0.durationMs > a + 0.0001
        }
        return TimelineInstruction(
          range: CMTimeRange(
            start: CMTime(seconds: a / 1000, preferredTimescale: 1_000_000),
            end: CMTime(seconds: b / 1000, preferredTimescale: 1_000_000)), layers: active,
          size: size, overlays: overlays, control: control)
      }
      let videoComposition = AVMutableVideoComposition()
      videoComposition.customVideoCompositorClass = TimelineCompositor.self
      videoComposition.renderSize = size
      videoComposition.instructions = instructions
      videoComposition.frameDuration = CMTime(seconds: 1 / plan.fps, preferredTimescale: 600_000)
      let output = try outputURL(extension: "mp4")
      _ = try await self.encodeVideo(
        asset: composition, output: output, source: aggregate, plan: plan, options: options,
        metadata: [], videoComposition: videoComposition, audioMix: mix)
      let actual = try await detectVideoInfo(atPath: output.path)
      guard plan.color != .preserve || !aggregate.hdr || actual.hdr else {
        throw MediaJob.error("Timeline failed to preserve HDR.")
      }
      guard plan.color == .sdr || aggregate.bitDepth <= actual.bitDepth else {
        throw MediaJob.error("Timeline reduced the source bit depth; choose SDR explicitly.")
      }
      guard
        plan.audio == .mute || audioSource == nil && options.audioEdit?.musicUri == nil
          || actual.audioTracks > 0
      else { throw MediaJob.error("Timeline unexpectedly lost audio.") }
      return makeResult(
        ok: true, operation: .exportvideo, type: .video, inputUri: clips.first?.uri,
        outputUri: output.path, media: videoMedia(actual, path: output.path),
        diagnostics: ExportDiagnostics(
          elapsedMs: control.elapsedMs, outputSizeBytes: fileSizeBytes(atPath: output.path) ?? 0,
          peakProcessMemoryBytes: control.peakMemoryBytes, videoPath: .encoded,
          audioPath: actual.audioTracks > 0 ? .encoded : .absent, codec: actual.mime,
          estimatedSizeBytes: plan.estimatedSizeBytes, frameCount: Double(control.frameCount)))
    }
  }
}
