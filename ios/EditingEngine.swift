@preconcurrency import AVFoundation
import CoreImage
import Foundation
import ImageIO
import NitroModules
import UIKit

extension NitroMediaKit {
  func openMedia(uri: String, limits: ProcessingLimits?) -> Promise<any HybridMediaAssetSpec> {
    Promise.async { [self] in
      let control = try PipelineControl(limits: limits)
      var owner: MediaJob?
      do {
        try PipelineControl.admit(control)
        let job = try MediaJob(control: control)
        owner = job
        let path = try await job.resolve(uri)
        let (type, media, source) = try await probeMedia(path: path)
        try control.check()
        control.resourceCheck = nil
        control.close()
        PipelineControl.remove(control)
        return try NativeMediaAsset(
          kit: self, uri: uri, type: type, info: media,
          storage: AssetStorage(path: path, onClose: { job.finish(success: true) }), source: source)
      } catch {
        owner?.finish(success: false)
        control.close()
        PipelineControl.remove(control)
        throw MediaErrors.normalize(error)
      }
    }
  }
  func probeMedia(path: String) async throws -> (MediaType, MediaInfoMedia, VideoSource?) {
    if let image = detectImageInfo(atPath: path) {
      return (
        .image,
        buildMediaInfo(
          width: Double(image.width), height: Double(image.height), format: image.extensionName,
          sizeBytes: fileSizeBytes(atPath: path), audioTracks: 0, videoTracks: 0)!, nil
      )
    }
    do {
      let video = try await detectVideoInfo(atPath: path)
      return (.video, videoMedia(video, path: path)!, video)
    } catch {
      try MediaJob.current?.control.check()
      let asset = AVURLAsset(url: URL(fileURLWithPath: path))
      guard try await asset.loadTracks(withMediaType: .video).isEmpty else { throw error }
      let tracks = try await asset.loadTracks(withMediaType: .audio)
      guard let track = tracks.first else { throw error }
      let duration = try await asset.load(.duration).seconds * 1000
      guard duration.isFinite, duration > 0 else { throw MediaJob.error("Invalid audio duration.") }
      let formats = try await track.load(.formatDescriptions)
      let stream = formats.first.flatMap {
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee
      }
      let media = buildMediaInfo(
        durationMs: duration, format: URL(fileURLWithPath: path).pathExtension,
        sizeBytes: fileSizeBytes(atPath: path), audioTracks: Double(tracks.count), videoTracks: 0,
        codec: "audio/\(stream?.mFormatID ?? 0)",
        audioChannels: Double(stream?.mChannelsPerFrame ?? 0), audioSampleRate: stream?.mSampleRate)!
      return (.audio, media, nil)
    }
  }
  func getCapabilities() -> Promise<MediaCapabilities> {
    Promise.async { DeviceCapabilities.get() }
  }
  func planExport(uri: String, options: ExportVideoOptions) -> Promise<ExportPlan> {
    owned(limits: options.limits) { [self] job in
      let plan = try await planLocal(path: job.resolve(uri), options: options)
      _ = try job.commitPaths([])
      return plan
    }
  }
  func planLocal(path: String, options: ExportVideoOptions, source: VideoSource? = nil) async throws
    -> ExportPlan
  {
    let info: VideoSource
    if let source { info = source } else { info = try await detectVideoInfo(atPath: path) }
    var effective = options
    if let trim = options.trim, options.trimMode == .keyframe {
      effective = options.editing(
        trim: try await keyframeRange(path: path, range: trim, duration: info.durationMs))
    }
    let p = try ExportPlanner.plan(info, options: effective)
    try DeviceCapabilities.check(p)
    var reasons: [String] = []
    if options.geometry != nil { reasons.append("Geometry edits require video encoding.") }
    if options.overlays?.isEmpty == false || options.watermark != nil || options.subtitles != nil {
      reasons.append("Overlays require video encoding.")
    }
    if options.trim != nil {
      reasons.append(
        options.trimMode == .keyframe
          ? "Trim expands to surrounding keyframes." : "Accurate trim requires video encoding.")
    }
    if (options.speed ?? 1) != 1 { reasons.append("Playback speed changes require encoding.") }
    if options.audioEdit != nil { reasons.append("Audio edits require audio encoding.") }
    if p.width != Int(info.width) || p.height != Int(info.height) {
      reasons.append("Output dimensions differ from the source.")
    }
    if p.timing != .preserve { reasons.append("Frame-rate policy requires encoding.") }
    if p.mime != info.mime { reasons.append("Output codec differs from the source.") }
    if p.color == .sdr { reasons.append("Explicit SDR conversion requires encoding.") }
    if options.copyPolicy == .encode { reasons.append("Encoding was explicitly requested.") }
    if options.video?.videoBitrateKbps != nil || options.video?.targetSizeBytes != nil
      || options.preset != nil && options.preset != .preserve
    {
      reasons.append("Compression settings require encoding.")
    }
    if reasons.isEmpty {
      reasons.append(
        p.canCopy
          ? "Compatible video can be stream-copied."
          : "Requested codec, size, bitrate or timing requires encoding.")
    }
    var warnings =
      options.video?.targetSizeBytes == nil
      ? []
      : [
        MediaInfoWarning(
          code: "SIZE_ESTIMATE", message: "Target size is an estimate, not a hard output guarantee."
        )
      ]
    if let edit = options.audioEdit, edit.musicUri != nil,
      (edit.volume ?? 1) + (edit.musicVolume ?? 1) > 1
    {
      warnings.append(
        MediaInfoWarning(
          code: "AUDIO_MIX_HEADROOM",
          message: "Combined gains exceed unity; lower levels to avoid clipping."))
    }
    return ExportPlan(
      width: Double(p.width), height: Double(p.height), fps: p.fps, durationMs: p.durationMs,
      codec: p.mime == "video/hevc" ? .hevc : .h264, colorPolicy: p.color,
      videoPath: p.canCopy ? .copied : .encoded, estimatedSizeBytes: p.estimatedSizeBytes,
      reasons: reasons, warnings: warnings)
  }
  func createExport(
    uri: String, options: ExportVideoOptions, onProgress: ((ExportProgress) -> Void)?
  ) throws -> any HybridExportJobSpec {
    try startExport(uri: uri, options: options, onProgress: onProgress)
  }
  func startExport(
    uri: String, options: ExportVideoOptions, onProgress: ((ExportProgress) -> Void)?,
    source: VideoSource? = nil, lease: AssetLease? = nil
  ) throws -> any HybridExportJobSpec {
    let relay = ProgressRelay(onProgress)
    let control = try PipelineControl(limits: options.limits, progress: relay.emit)
    do { try PipelineControl.admit(control) } catch {
      control.close()
      throw error
    }
    control.emit(.queued)
    return NativeExportJob(control: control, relay: relay) { [self, lease] in
      defer { withExtendedLifetime(lease) {} }
      let result = try await performOperation(control: control, operation: .exportvideo, input: uri)
      {
        do {
          try lease?.validate()
          return try await self.exportResolved(
            inputUri: uri, path: self.getLocalFilePath(uri), options: options,
            operation: .exportvideo, sourceInfo: source)
        } catch {
          return self.makeErrorResult(
            operation: .exportvideo, type: .video, inputUri: uri, error: error)
        }
      }.await()
      return try typedExport(result)
    }
  }
  func createTimeline(
    clips: [TimelineClip], options: ExportVideoOptions, onProgress: ((ExportProgress) -> Void)?
  ) throws -> any HybridExportJobSpec {
    let relay = ProgressRelay(onProgress)
    let control = try PipelineControl(limits: options.limits, progress: relay.emit)
    do { try PipelineControl.admit(control) } catch {
      control.close()
      throw error
    }
    control.emit(.queued)
    return NativeExportJob(control: control, relay: relay) { [self] in
      let result = try await runControlled(control: control, input: clips.first?.uri) {
        try await self.timeline(clips: clips, options: options)
      }.await()
      return try typedExport(result)
    }
  }
  private func runControlled(
    control: PipelineControl, input: String?, body: @escaping () async throws -> MediaInfoResult
  ) -> Promise<MediaInfoResult> {
    performOperation(control: control, operation: .exportvideo, input: input) {
      do { return try await body() } catch {
        return self.makeErrorResult(
          operation: .exportvideo, type: .video, inputUri: input, error: error)
      }
    }
  }
  func typedExport(_ result: MediaInfoResult) throws -> VideoExportResult {
    guard result.ok, let output = result.outputUri, let media = result.media else {
      throw NSError(
        domain: result.error?.code ?? "EXPORT_FAILED", code: 1,
        userInfo: [NSLocalizedDescriptionKey: result.error?.message ?? "Export failed."])
    }
    return VideoExportResult(
      outputUri: output, media: media, warnings: result.warnings ?? [],
      diagnostics: result.diagnostics, actualTrim: result.actualTrim)
  }
  func owned<T>(limits: ProcessingLimits? = nil, body: @escaping (MediaJob) async throws -> T)
    -> Promise<T>
  {
    Promise.async {
      let control = try PipelineControl(limits: limits)
      var job: MediaJob?
      var success = false
      defer {
        job?.finish(success: success)
        control.close()
        PipelineControl.remove(control)
      }
      try PipelineControl.admit(control)
      let active = try MediaJob(control: control)
      job = active
      do {
        let result = try await MediaJob.$current.withValue(active) { try await body(active) }
        success = true
        return result
      } catch { throw MediaErrors.normalize(error) }
    }
  }
  func extractThumbnails(uri: String, options: ThumbnailBatchOptions) -> Promise<
    ThumbnailBatchResult
  > {
    thumbnailBatch(uri: uri, options: options)
  }
  func thumbnailBatch(uri: String, options: ThumbnailBatchOptions, source: VideoSource? = nil)
    -> Promise<ThumbnailBatchResult>
  {
    owned { [self] job in
      guard !options.timesMs.isEmpty, options.timesMs.count <= 256 else {
        throw MediaJob.error("Request 1–256 thumbnails.")
      }
      let path = try await job.resolve(uri)
      let info: VideoSource
      if let source { info = source } else { info = try await detectVideoInfo(atPath: path) }
      guard options.timesMs.allSatisfy({ $0.isFinite && $0 >= 0 && $0 < info.durationMs }) else {
        throw MediaJob.error("Thumbnail timestamps must fit the video.")
      }
      let edge = try ExportPlanner.finite(
        options.maxLongEdge ?? 320, min: 2, max: 4096, name: "maxLongEdge")
      let quality = try ExportPlanner.finite(
        options.quality ?? 0.9, min: 0, max: 1, name: "quality")
      let scale = min(1, edge / max(info.width, info.height))
      let w = max(1, Int((info.width * scale).rounded()))
      let h = max(1, Int((info.height * scale).rounded()))
      var columns: Int?
      if let value = options.storyboardColumns {
        guard value.isFinite, value >= 1, value <= 32, value.rounded(.down) == value else {
          throw MediaJob.error("storyboardColumns must be an integer in [1,32].")
        }
        columns = Int(value)
      }
      let rows = columns.map { (options.timesMs.count + $0 - 1) / $0 }
      if let columns, let rows {
        guard w * columns <= 8192, h * rows <= 8192,
          Double(w * columns * h * rows)
            <= min(16_777_216, Double(ProcessInfo.processInfo.physicalMemory) / 64)
        else {
          throw MediaJob.error("Storyboard exceeds the image memory budget; reduce cell size.")
        }
      }
      try await MediaWorkGate.images.acquire(job.control)
      defer { Task { await MediaWorkGate.images.release() } }
      let generator = AVAssetImageGenerator(asset: AVURLAsset(url: URL(fileURLWithPath: path)))
      generator.appliesPreferredTrackTransform = true
      generator.maximumSize = CGSize(width: w, height: h)
      generator.requestedTimeToleranceBefore =
        options.precision == .fast ? .positiveInfinity : .zero
      generator.requestedTimeToleranceAfter = options.precision == .fast ? .positiveInfinity : .zero
      let hook = job.control.onCancel { generator.cancelAllCGImageGeneration() }
      defer { job.control.removeHook(hook) }
      let board: CGContext? = columns.flatMap { c in
        CGContext(
          data: nil, width: w * c, height: h * rows!, bitsPerComponent: 8, bytesPerRow: 0,
          space: CGColorSpace(name: CGColorSpace.sRGB)!,
          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
      }
      if columns != nil && board == nil { throw MediaJob.error("Cannot allocate storyboard.") }
      board?.setFillColor(UIColor.black.cgColor)
      if let board { board.fill(CGRect(x: 0, y: 0, width: board.width, height: board.height)) }
      var frames: [Int: ThumbnailResult] = [:]
      var cells: [Int: StoryboardCell] = [:]
      for index in options.timesMs.indices.sorted(by: { options.timesMs[$0] < options.timesMs[$1] })
      {
        try job.control.check()
        let requested = options.timesMs[index]
        let (image, actual) = try await generator.image(
          at: CMTime(seconds: requested / 1000, preferredTimescale: 1_000_000))
        try autoreleasepool {
          let output = try job.output("jpg")
          guard let data = UIImage(cgImage: image).jpegData(compressionQuality: quality) else {
            throw MediaJob.error("Cannot encode thumbnail.")
          }
          try data.write(to: output)
          frames[index] = ThumbnailResult(
            outputUri: output.path, requestedTimeMs: requested, actualTimeMs: actual.seconds * 1000,
            width: Double(image.width), height: Double(image.height))
          if let columns, let board {
            let x = index % columns * w
            let y = index / columns * h
            board.draw(image, in: CGRect(x: x, y: board.height - y - h, width: w, height: h))
            cells[index] = StoryboardCell(
              index: Double(index), x: Double(x), y: Double(y), width: Double(w), height: Double(h),
              timeMs: actual.seconds * 1000)
          }
        }
        job.control.emit(
          .processing, fraction: Double(frames.count) / Double(options.timesMs.count))
      }
      var sprite: StoryboardResult?
      if let board, let image = board.makeImage() {
        let output = try job.output("jpg")
        guard let data = UIImage(cgImage: image).jpegData(compressionQuality: quality) else {
          throw MediaJob.error("Cannot encode storyboard.")
        }
        try data.write(to: output)
        sprite = StoryboardResult(
          outputUri: output.path, width: Double(board.width), height: Double(board.height),
          cells: options.timesMs.indices.map { cells[$0]! })
      }
      let list = options.timesMs.indices.map { frames[$0]! }
      let mapping = try job.commitPaths(
        list.map { $0.outputUri } + (sprite.map { [$0.outputUri] } ?? []))
      return ThumbnailBatchResult(
        thumbnails: list.map {
          ThumbnailResult(
            outputUri: mapping[$0.outputUri]!, requestedTimeMs: $0.requestedTimeMs,
            actualTimeMs: $0.actualTimeMs, width: $0.width, height: $0.height)
        },
        storyboard: sprite.map {
          StoryboardResult(
            outputUri: mapping[$0.outputUri]!, width: $0.width, height: $0.height, cells: $0.cells)
        })
    }
  }
  func keyframeRange(path: String, range: VideoSegment, duration: Double) async throws
    -> VideoSegment
  {
    guard range.startMs.isFinite, range.endMs.isFinite, range.startMs >= 0,
      range.endMs > range.startMs, range.endMs <= duration
    else { throw MediaJob.error("Invalid trim range.") }
    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw MediaJob.error("No video track.")
    }
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    output.alwaysCopiesSampleData = false
    reader.add(output)
    guard reader.startReading() else {
      throw reader.error ?? MediaJob.error("Cannot read keyframes.")
    }
    defer { reader.cancelReading() }
    var start = 0.0
    var end = duration
    while let sample = output.copyNextSampleBuffer() {
      try MediaJob.current?.control.check()
      let attachments =
        CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
        as? [[CFString: Any]]
      let sync = !(attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
      if sync {
        let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds * 1000
        if time <= range.startMs { start = max(0, time) }
        if time >= range.endMs {
          end = min(duration, time)
          break
        }
      }
    }
    if reader.status == .failed {
      throw reader.error ?? MediaJob.error("Cannot inspect keyframes.")
    }
    return VideoSegment(startMs: start, endMs: end)
  }
}
