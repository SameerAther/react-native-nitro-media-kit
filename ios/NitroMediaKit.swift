import Foundation
@preconcurrency import AVFoundation
import UIKit
import ImageIO
import UniformTypeIdentifiers
import CoreImage
import VideoToolbox
import NitroModules  // Import Promise from NitroModules core

final class NitroMediaKit: HybridNitroMediaKitSpec, @unchecked Sendable {
  func resolveFps(for track: AVAssetTrack) async throws -> Double? {
    let nominal = Double(try await track.load(.nominalFrameRate))
    if nominal.isFinite && nominal > 0 {
      return nominal
    }
    let minFrameDuration = try await track.load(.minFrameDuration)
    if minFrameDuration.isNumeric && minFrameDuration.seconds > 0 {
      return 1.0 / minFrameDuration.seconds
    }
    return nil
  }

  func buildMediaInfo(
    durationMs: Double? = nil,
    width: Double? = nil,
    height: Double? = nil,
    fps: Double? = nil,
    format: String? = nil,
    sizeBytes: Double? = nil,
    audioTracks: Double? = nil,
    videoTracks: Double? = nil,
    codec: String? = nil, hdr: Bool? = nil, bitDepth: Double? = nil,
    bitrateKbps: Double? = nil, audioChannels: Double? = nil, audioSampleRate: Double? = nil
  ) -> MediaInfoMedia? {
    if durationMs == nil && width == nil && height == nil && fps == nil && format == nil
      && sizeBytes == nil && audioTracks == nil && videoTracks == nil
    {
      return nil
    }
    return MediaInfoMedia(
      durationMs: durationMs,
      width: width,
      height: height,
      fps: fps,
      format: format,
      sizeBytes: sizeBytes,
      audioTracks: audioTracks,
      videoTracks: videoTracks, codec: codec, hdr: hdr, bitDepth: bitDepth,
      bitrateKbps: bitrateKbps, audioChannels: audioChannels, audioSampleRate: audioSampleRate
    )
  }

  func fileSizeBytes(atPath path: String) -> Double? {
    guard let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber
    else {
      return nil
    }
    return size.doubleValue
  }

  func makeResult(
    ok: Bool,
    operation: OperationType,
    type: MediaType,
    inputUri: String? = nil,
    outputUri: String? = nil,
    segments: [String]? = nil,
    media: MediaInfoMedia? = nil,
    warnings: [MediaInfoWarning]? = nil,
    error: MediaInfoError? = nil, actualTimeMs: Double? = nil, actualTrim: VideoSegment? = nil,
    diagnostics: ExportDiagnostics? = nil
  ) -> MediaInfoResult {
    return MediaInfoResult(
      ok: ok,
      operation: operation,
      type: type,
      inputUri: inputUri,
      outputUri: outputUri,
      segments: segments,
      media: media,
      warnings: warnings,
      error: error, actualTimeMs: actualTimeMs, actualTrim: actualTrim, diagnostics: diagnostics
    )
  }

  func makeErrorResult(
    operation: OperationType,
    type: MediaType,
    inputUri: String? = nil,
    outputUri: String? = nil,
    error: Error
  ) -> MediaInfoResult {
    let nsError = MediaErrors.normalize(error)
    let errorInfo = MediaInfoError(
      code: nsError.domain,
      message: nsError.localizedDescription
    )
    return makeResult(
      ok: false,
      operation: operation,
      type: type,
      inputUri: inputUri,
      outputUri: outputUri,
      error: errorInfo
    )
  }

  struct ResolvedCompressOptions {
    let preset: CompressionPreset
    let preserveMetadata: Bool
    let image: CompressImageOptions?
    let video: CompressVideoOptions?
  }

  struct DetectedImageInfo {
    let width: Int
    let height: Int
    let hasAlpha: Bool
    let sizeBytes: Double
    let extensionName: String?
    let sourceMetadata: [CFString: Any]?
  }

  typealias DetectedVideoInfo = VideoSource

  struct ImageCompressionPlan {
    let format: CompressImageFormat
    let quality: Double?
    let maxLongEdge: Double
  }

  func warning(code: String, message: String) -> MediaInfoWarning {
    return MediaInfoWarning(code: code, message: message)
  }

  func validationError(_ message: String) -> NSError {
    NSError(
      domain: "ValidationError",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: message]
    )
  }

  func unsupportedMediaError(_ message: String) -> NSError {
    NSError(
      domain: "UnsupportedMediaError",
      code: 1,
      userInfo: [NSLocalizedDescriptionKey: message]
    )
  }

  public func getMediaInfo(inputUri: String) -> Promise<MediaInfoResult> {
    run(.getmediainfo, input: inputUri) {
      let path = try await self.getLocalFilePath(inputUri)
      let (type, media, _) = try await self.probeMedia(path: path)
      return self.makeResult(
        ok: true, operation: .getmediainfo, type: type, inputUri: inputUri, media: media)
    }
  }

  public func compressMedia(inputUri: String, options: CompressMediaOptions?) -> Promise<
    MediaInfoResult
  > {
    return self.performOperation(operation: .compressmedia, input: inputUri) {
      var typeGuess: MediaType = .video
      do {
        let localPath = try await self.getLocalFilePath(inputUri)
        let resolvedOptions = self.resolveCompressOptions(options)

        if let imageInfo = self.detectImageInfo(atPath: localPath) {
          typeGuess = .image
          try await MediaWorkGate.images.acquire(try self.currentControl())
          defer { Task { await MediaWorkGate.images.release() } }
          return try self.compressImage(
            inputUri: inputUri,
            localPath: localPath,
            imageInfo: imageInfo,
            options: resolvedOptions
          )
        }

        typeGuess = .video
        return try await self.exportResolved(
          inputUri: inputUri, path: localPath,
          options: ExportVideoOptions(
            trim: nil, watermark: nil, watermarkOptions: nil,
            preset: resolvedOptions.preset, video: resolvedOptions.video, audio: nil,
            preserveMetadata: resolvedOptions.preserveMetadata, limits: nil),
          operation: .compressmedia)
      } catch {
        return self.makeErrorResult(
          operation: .compressmedia,
          type: typeGuess,
          inputUri: inputUri,
          error: error
        )
      }
    }
  }

  func resolveCompressOptions(_ options: CompressMediaOptions?) -> ResolvedCompressOptions {
    return ResolvedCompressOptions(
      preset: options?.preset ?? .auto,
      preserveMetadata: options?.preserveMetadata ?? false,
      image: options?.image,
      video: options?.video
    )
  }

  func detectImageInfo(atPath path: String) -> DetectedImageInfo? {
    let url = URL(fileURLWithPath: path)
    guard
      let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
      let widthNumber = properties[kCGImagePropertyPixelWidth] as? NSNumber,
      let heightNumber = properties[kCGImagePropertyPixelHeight] as? NSNumber
    else {
      return nil
    }

    let exifOrientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    let swapsDimensions = [5, 6, 7, 8].contains(exifOrientation)
    let rawWidth = widthNumber.intValue
    let rawHeight = heightNumber.intValue
    let displayWidth = swapsDimensions ? rawHeight : rawWidth
    let displayHeight = swapsDimensions ? rawWidth : rawHeight
    let cgImage = CGImageSourceCreateImageAtIndex(
      source, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
    let hasAlpha = cgImage.map { self.alphaInfoHasAlpha($0.alphaInfo) } ?? false

    return DetectedImageInfo(
      width: displayWidth,
      height: displayHeight,
      hasAlpha: hasAlpha,
      sizeBytes: self.fileSizeBytes(atPath: path) ?? 0,
      extensionName: CGImageSourceGetType(source).flatMap {
        UTType($0 as String)?.preferredFilenameExtension
      },
      sourceMetadata: properties
    )
  }

  func detectVideoInfo(atPath path: String) async throws -> DetectedVideoInfo {
    let url = URL(fileURLWithPath: path)
    let asset = AVURLAsset(url: url)
    let control = MediaJob.current?.control
    let hook = control?.onCancel { asset.cancelLoading() }
    defer { if let hook { control?.removeHook(hook) } }
    try control?.check()
    let duration = try await asset.load(.duration)
    guard duration.seconds.isFinite, duration.seconds > 0 else {
      throw unsupportedMediaError("Input video has invalid duration.")
    }

    let videoTracks = try await asset.loadTracks(withMediaType: .video)
    guard let videoTrack = videoTracks.first else {
      throw unsupportedMediaError("No video track found in input.")
    }
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)
    let naturalSize = try await videoTrack.load(.naturalSize)
    let preferredTransform = try await videoTrack.load(.preferredTransform)
    let transformedRect = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
    let transformedWidth = abs(transformedRect.width)
    let transformedHeight = abs(transformedRect.height)
    let naturalWidth = abs(naturalSize.width)
    let naturalHeight = abs(naturalSize.height)
    let width = Double(transformedWidth > 0 ? transformedWidth : naturalWidth)
    let height = Double(transformedHeight > 0 ? transformedHeight : naturalHeight)

    guard width.isFinite, height.isFinite, width > 0, height > 0 else {
      throw unsupportedMediaError("Invalid displayed video dimensions.")
    }
    let descriptions = try await videoTrack.load(.formatDescriptions)
    let description = descriptions.first
    func color(_ key: CFString) -> String? {
      description.flatMap { CMFormatDescriptionGetExtension($0, extensionKey: key) as? String }
    }
    let transfer = color(kCMFormatDescriptionExtension_TransferFunction)
    let hdr =
      transfer == (kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String)
      || transfer == (kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String)
    let explicitDepth = description.flatMap {
      CMFormatDescriptionGetExtension(
        $0, extensionKey: kCMFormatDescriptionExtension_BitsPerComponent) as? NSNumber
    }?.intValue
    // Main10 SDR often omits BitsPerComponent; hvcC retains its coded precision.
    let atoms = description.flatMap {
      CMFormatDescriptionGetExtension(
        $0, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms)
        as? [String: Any]
    }
    let configuration = atoms?["hvcC"] as? Data
    let codedDepth = configuration.flatMap {
      $0.count >= 19 ? 8 + Int($0[$0.startIndex + 17] & 7) : nil
    }
    let depth = explicitDepth ?? codedDepth
    let subtype = description.map { CMFormatDescriptionGetMediaSubType($0) }
    let mime =
      subtype == kCMVideoCodecType_HEVC
      ? "video/hevc" : subtype == kCMVideoCodecType_H264 ? "video/avc" : "video/other"
    let audioDescriptions = try await audioTracks.first?.load(.formatDescriptions)
    let stream = audioDescriptions?.first.flatMap {
      CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee
    }
    let rate = try await videoTrack.load(.estimatedDataRate)
    return DetectedVideoInfo(
      width: width,
      height: height,
      fps: try await self.resolveFps(for: videoTrack) ?? 0,
      durationMs: duration.seconds * 1000.0,
      sizeBytes: self.fileSizeBytes(atPath: path) ?? 0,
      audioTracks: audioTracks.count,
      extensionName: url.pathExtension.isEmpty ? nil : url.pathExtension.lowercased(),
      mime: mime, hdr: hdr, bitDepth: depth ?? (hdr ? 10 : 8),
      bitrateKbps: rate > 0 ? Double(rate) / 1000 : nil,
      audioChannels: Int(stream?.mChannelsPerFrame ?? 0), audioSampleRate: stream?.mSampleRate ?? 0,
      colorPrimaries: color(kCMFormatDescriptionExtension_ColorPrimaries), colorTransfer: transfer,
      colorMatrix: color(kCMFormatDescriptionExtension_YCbCrMatrix)
    )
  }

  func compressImage(
    inputUri: String,
    localPath: String,
    imageInfo: DetectedImageInfo,
    options: ResolvedCompressOptions
  ) throws -> MediaInfoResult {
    var warnings: [MediaInfoWarning] = []
    let plan = try resolveImageCompressionPlan(info: imageInfo, options: options)

    if options.image?.maxLongEdge == nil, options.preset == .preserve,
      plan.maxLongEdge < Double(max(imageInfo.width, imageInfo.height))
    {
      warnings.append(
        warning(
          code: "IMAGE_SIZE_LIMIT",
          message: "Image dimensions were reduced to fit the memory budget."))
    }
    if plan.format == .png, options.image?.quality != nil {
      warnings.append(
        warning(
          code: "IMAGE_QUALITY_IGNORED", message: "`image.quality` is ignored for PNG output."))
    }
    if imageInfo.sizeBytes > 0, imageInfo.sizeBytes <= 400 * 1024 {
      warnings.append(
        warning(
          code: "ALREADY_SMALL",
          message: "Input image is already small; compression savings may be limited."))
    }

    let resized = try downsampleImage(path: localPath, maxLongEdge: plan.maxLongEdge)
    let targetSize = resized.size

    guard
      let encodedData = encodeImage(
        image: resized,
        format: plan.format,
        quality: plan.quality ?? 0.8,
        preserveMetadata: options.preserveMetadata,
        sourceMetadata: imageInfo.sourceMetadata
      )
    else {
      throw unsupportedMediaError("Failed to encode compressed image.")
    }

    let outputExt = plan.format == .png ? "png" : "jpg"
    let outputURL = try outputURL(extension: outputExt)
    try encodedData.write(to: outputURL, options: .atomic)

    let outputSize = self.fileSizeBytes(atPath: outputURL.path)
    if imageInfo.sizeBytes > 0, let outputSize, outputSize >= imageInfo.sizeBytes * 0.95 {
      warnings.append(
        warning(code: "LIMITED_SAVINGS", message: "Compressed output is close to source size."))
    }

    let media = buildMediaInfo(
      width: Double(targetSize.width),
      height: Double(targetSize.height),
      format: outputExt,
      sizeBytes: outputSize,
      audioTracks: 0,
      videoTracks: 0
    )

    return makeResult(
      ok: true,
      operation: .compressmedia,
      type: .image,
      inputUri: inputUri,
      outputUri: outputURL.path,
      media: media,
      warnings: warnings.isEmpty ? nil : warnings
    )
  }

  func resolveImageCompressionPlan(
    info: DetectedImageInfo,
    options: ResolvedCompressOptions
  ) throws -> ImageCompressionPlan {
    let sourceLongEdge = max(Double(info.width), Double(info.height))
    let presetLongEdge: Double
    switch options.preset {
    case .auto:
      if info.sizeBytes <= 1_500_000 {
        presetLongEdge = min(sourceLongEdge, 4096)
      } else if info.sizeBytes <= 5_000_000 {
        presetLongEdge = min(sourceLongEdge, 2048)
      } else {
        presetLongEdge = min(sourceLongEdge, 1600)
      }
    case .preserve:
      presetLongEdge = sourceLongEdge
    case .high:
      presetLongEdge = min(sourceLongEdge, 4096)
    case .balanced:
      presetLongEdge = min(sourceLongEdge, 1920)
    case .small:
      presetLongEdge = min(sourceLongEdge, 1280)
    }

    if let maxLongEdge = options.image?.maxLongEdge {
      try requirePositiveFinite(maxLongEdge, field: "image.maxLongEdge")
      guard maxLongEdge <= 8192 else {
        throw validationError("image.maxLongEdge must not exceed 8192.")
      }
    }
    var finalLongEdge = min(
      sourceLongEdge,
      max(2, options.image?.maxLongEdge ?? presetLongEdge)
    )

    finalLongEdge = try boundedImageEdge(
      width: Double(info.width), height: Double(info.height), requested: finalLongEdge,
      explicit: options.image?.maxLongEdge != nil)
    let selectedFormat: CompressImageFormat
    switch options.image?.format ?? .auto {
    case .auto:
      selectedFormat = info.hasAlpha ? .png : .jpeg
    case .jpeg:
      selectedFormat = .jpeg
    case .png:
      selectedFormat = .png
    }

    if let quality = options.image?.quality {
      try requireFinite(quality, field: "image.quality")
      guard quality >= 0, quality <= 1 else {
        throw validationError("`image.quality` must be between 0 and 1.")
      }
    }

    let megapixels = (Double(info.width) * Double(info.height)) / 1_000_000.0
    let presetQuality: Double
    switch options.preset {
    case .auto:
      if megapixels <= 2 {
        presetQuality = 0.86
      } else if megapixels <= 8 {
        presetQuality = 0.80
      } else {
        presetQuality = 0.74
      }
    case .preserve:
      presetQuality = 1
    case .high:
      presetQuality = 0.94
    case .balanced:
      presetQuality = 0.82
    case .small:
      presetQuality = 0.72
    }

    return ImageCompressionPlan(
      format: selectedFormat,
      quality: selectedFormat == .jpeg ? (options.image?.quality ?? presetQuality) : nil,
      maxLongEdge: finalLongEdge
    )
  }

  func insertTrackRange(
    _ range: CMTimeRange, from source: AVAssetTrack, into target: AVMutableCompositionTrack,
    at time: CMTime
  ) async throws {
    let currentEnd = CMTimeRangeGetEnd(try await target.load(.timeRange))
    if time > currentEnd { target.insertEmptyTimeRange(CMTimeRange(start: currentEnd, end: time)) }
    try target.insertTimeRange(range, of: source, at: time)
  }

  func exportResolved(
    inputUri: String, path: String, options suppliedOptions: ExportVideoOptions,
    operation: OperationType, sourceInfo: VideoSource? = nil
  ) async throws -> MediaInfoResult {
    let info: VideoSource
    if let sourceInfo { info = sourceInfo } else { info = try await detectVideoInfo(atPath: path) }
    var options = suppliedOptions
    if let trim = options.trim, options.trimMode == .keyframe {
      options = options.editing(
        trim: try await keyframeRange(path: path, range: trim, duration: info.durationMs))
    }
    let plan = try ExportPlanner.plan(info, options: options)
    try DeviceCapabilities.check(plan)
    let control = try currentControl()
    try control.check()
    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
    let loadingHook = control.onCancel { asset.cancelLoading() }
    defer { control.removeHook(loadingHook) }
    // A normalized composition preserves source track offsets while making trim output start at zero.
    let composition = AVMutableComposition()
    guard let sourceVideo = try await asset.loadTracks(withMediaType: .video).first,
      let video = composition.addMutableTrack(
        withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
    else { throw unsupportedMediaError("No video track.") }
    let assetDuration = try await asset.load(.duration)
    let selected =
      options.trim.map { segmentRange($0) } ?? CMTimeRange(start: .zero, duration: assetDuration)
    let videoRange = CMTimeRangeGetIntersection(
      try await sourceVideo.load(.timeRange), otherRange: selected)
    guard videoRange.duration > .zero else {
      throw validationError("Selected range contains no video.")
    }
    try await insertTrackRange(
      videoRange, from: sourceVideo, into: video,
      at: CMTimeSubtract(videoRange.start, selected.start))
    video.preferredTransform = try await sourceVideo.load(.preferredTransform)
    let sourceAudios = try await asset.loadTracks(withMediaType: .audio)
    let selectedIndex = options.audioEdit?.trackIndex ?? 0
    guard
      sourceAudios.isEmpty && options.audioEdit?.trackIndex == nil
        || selectedIndex < Double(sourceAudios.count)
    else { throw validationError("Requested audio track does not exist.") }
    if plan.audio != .mute, !sourceAudios.isEmpty {
      let sourceAudio = sourceAudios[Int(selectedIndex)]
      let audioRange = CMTimeRangeGetIntersection(
        try await sourceAudio.load(.timeRange), otherRange: selected)
      if audioRange.duration > .zero {
        guard
          let audio = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        else { throw validationError("Cannot create audio track.") }
        try await insertTrackRange(
          audioRange, from: sourceAudio, into: audio,
          at: CMTimeSubtract(audioRange.start, selected.start))
      }
    }
    if (options.speed ?? 1) != 1 {
      composition.scaleTimeRange(
        CMTimeRange(start: .zero, duration: selected.duration),
        toDuration: CMTime(seconds: plan.durationMs / 1000, preferredTimescale: 1_000_000))
    }
    let mix = try await audioMix(
      composition: composition, options: options, durationMs: plan.durationMs)
    let output = try outputURL(extension: "mp4")
    var copiedVideo = false
    var copiedAudio = false
    if plan.canCopy && options.audioEdit == nil && plan.audio != .stereo
      && options.video?.audioBitrateKbps == nil
    {
      try await export(
        asset: composition, output: output,
        range: CMTimeRange(start: .zero, duration: selected.duration), passthrough: true,
        metadata: options.preserveMetadata == true ? try await asset.load(.metadata) : [])
      copiedVideo = true; copiedAudio = plan.audio != .mute && info.audioTracks > 0
    } else {
      let copied = try await withMediaEncoder {
        try await self.encodeVideo(
          asset: composition, output: output, source: info, plan: plan, options: options,
          metadata: options.preserveMetadata == true ? try await asset.load(.metadata) : [],
          audioMix: mix)
      }
      copiedVideo = copied.0; copiedAudio = copied.1
    }
    control.emit(.validating)
    let actual = try await detectVideoInfo(atPath: output.path)
    guard !(info.hdr && plan.color == .preserve && !actual.hdr) else {
      throw validationError("Encoder did not preserve HDR; choose SDR conversion explicitly.")
    }
    let expectedAudio = try await composition.loadTracks(withMediaType: .audio).count
    guard expectedAudio == 0 || actual.audioTracks > 0 else {
      throw validationError("Export unexpectedly lost the primary audio track.")
    }
    if actual.audioTracks > 0 {
      let expectedChannels = plan.audio == .stereo ? 2 : info.audioChannels
      let selectedFormats =
        try await composition.loadTracks(withMediaType: .audio).first?.load(.formatDescriptions)
        ?? []
      let selectedChannels =
        selectedFormats.first.flatMap {
          CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee.mChannelsPerFrame
        }.map(Int.init) ?? expectedChannels
      guard
        expectedChannels == 0
          || actual.audioChannels == (plan.audio == .stereo ? 2 : selectedChannels)
      else { throw validationError("Encoder changed the requested audio channel layout.") }
    }
    guard plan.color != .sdr || !actual.hdr else {
      throw validationError("Export did not produce the requested SDR output.")
    }
    guard plan.color == .sdr || info.bitDepth <= actual.bitDepth else {
      throw validationError(
        "Export reduced the source bit depth; choose SDR conversion explicitly.")
    }
    var warnings: [MediaInfoWarning] = []
    if let edit = options.audioEdit, edit.musicUri != nil,
      (edit.volume ?? 1) + (edit.musicVolume ?? 1) > 1
    {
      warnings.append(
        warning(
          code: "AUDIO_MIX_HEADROOM",
          message: "Combined gains exceed unity; lower levels to avoid clipping."))
    }
    if actual.width != Double(plan.width) || actual.height != Double(plan.height) {
      warnings.append(
        warning(
          code: "ENCODER_SIZE_FALLBACK", message: "Encoder changed the requested output dimensions."
        ))
    }
    if actual.mime != plan.mime {
      warnings.append(
        warning(
          code: "ENCODER_CODEC_FALLBACK", message: "Encoder changed the requested output codec."))
    }
    if let target = options.video?.targetSizeBytes,
      (fileSizeBytes(atPath: output.path) ?? 0) > target
    {
      warnings.append(
        warning(
          code: "TARGET_SIZE_EXCEEDED",
          message: "Target size is an estimate; actual output is larger."))
    }
    if operation == .compressmedia, (fileSizeBytes(atPath: output.path) ?? 0) >= info.sizeBytes {
      warnings.append(
        warning(code: "LIMITED_SAVINGS", message: "Output is not smaller than the input."))
    }
    if options.preserveMetadata == true {
      warnings.append(
        warning(
          code: "METADATA_PRESERVE_PARTIAL",
          message: "Only supported container metadata is retained."))
    }
    return makeResult(
      ok: true, operation: operation, type: .video, inputUri: inputUri, outputUri: output.path,
      media: videoMedia(actual, path: output.path), warnings: warnings.isEmpty ? nil : warnings,
      actualTrim: options.trim,
      diagnostics: ExportDiagnostics(
        elapsedMs: control.elapsedMs, outputSizeBytes: fileSizeBytes(atPath: output.path) ?? 0,
        peakProcessMemoryBytes: control.peakMemoryBytes,
        videoPath: copiedVideo ? .copied : .encoded,
        audioPath: actual.audioTracks == 0 ? .absent : copiedAudio ? .copied : .encoded,
        codec: actual.mime, estimatedSizeBytes: plan.estimatedSizeBytes,
        frameCount: copiedVideo ? nil : Double(control.frameCount)))
  }

  func encodeVideo(
    asset: AVAsset, output: URL, source: VideoSource, plan: VideoPlan, options: ExportVideoOptions,
    metadata: [AVMetadataItem], videoComposition: AVMutableVideoComposition? = nil,
    audioMix: AVAudioMix? = nil
  ) async throws -> (Bool, Bool) {
    let control = try currentControl()
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw validationError("No video track.")
    }
    let reader = try AVAssetReader(asset: asset)
    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true; writer.metadata = metadata
    var completed = false
    defer { if !completed { reader.cancelReading(); writer.cancelWriting() } }
    let duration = CMTime(seconds: plan.durationMs / 1000, preferredTimescale: 1_000_000)
    reader.timeRange = CMTimeRange(start: .zero, duration: duration)
    let descriptions = try await track.load(.formatDescriptions)
    let videoOutput: AVAssetReaderOutput
    let settings: [String: Any]?
    let keepHDR = source.hdr && plan.color == .preserve
    let preserveColor = plan.color != .sdr
    let keepHighDepth = preserveColor && source.bitDepth > 8
    guard plan.canCopy || !preserveColor || source.bitDepth <= 10 else {
      throw unsupportedMediaError(
        "Re-encoding this bit depth is unsupported; choose SDR conversion explicitly.")
    }
    let color: [String: Any] = [
      AVVideoColorPrimariesKey: preserveColor
        ? (source.colorPrimaries
          ?? (keepHDR ? AVVideoColorPrimaries_ITU_R_2020 : AVVideoColorPrimaries_ITU_R_709_2))
        : AVVideoColorPrimaries_ITU_R_709_2,
      AVVideoTransferFunctionKey: preserveColor
        ? (source.colorTransfer
          ?? (keepHDR
            ? AVVideoTransferFunction_ITU_R_2100_HLG : AVVideoTransferFunction_ITU_R_709_2))
        : AVVideoTransferFunction_ITU_R_709_2,
      AVVideoYCbCrMatrixKey: preserveColor
        ? (source.colorMatrix
          ?? (keepHDR ? AVVideoYCbCrMatrix_ITU_R_2020 : AVVideoYCbCrMatrix_ITU_R_709_2))
        : AVVideoYCbCrMatrix_ITU_R_709_2,
    ]
    if plan.canCopy {
      videoOutput = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
      settings = nil
    } else {
      let target = CGSize(width: plan.width, height: plan.height)
      let overlay =
        videoComposition == nil ? try await prepareOverlays(options: options, plan: plan) : nil
      let geometry = try GeometryPlan.make(
        width: source.width, height: source.height, options: options.geometry)
      let context = Self.imageContext
      let filters: AVMutableVideoComposition
      if let videoComposition {
        filters = videoComposition
      } else {
        filters = AVMutableVideoComposition(asset: asset) { request in
          do {
            try control.check()
            let canvas = CGRect(origin: .zero, size: target)
            let spatial = Self.spatial(request.sourceImage, geometry: geometry, target: target)
            let background = spatial.composited(over: CIImage(color: .black).cropped(to: canvas))
            let frame =
              try overlay?.composite(
                over: background, timeMs: request.compositionTime.seconds * 1000) ?? background
            request.finish(with: frame.cropped(to: canvas), context: context)
          } catch { request.finish(with: error) }
        }
      }
      filters.renderSize = target
      filters.sourceTrackIDForFrameTiming =
        (videoComposition != nil || plan.timing == .constant)
        ? kCMPersistentTrackID_Invalid : track.trackID
      filters.frameDuration = CMTime(seconds: 1 / max(1, plan.fps), preferredTimescale: 600_000)
      filters.colorPrimaries = color[AVVideoColorPrimariesKey] as? String
      filters.colorTransferFunction = color[AVVideoTransferFunctionKey] as? String
      filters.colorYCbCrMatrix = color[AVVideoYCbCrMatrixKey] as? String
      if keepHDR {
        if #available(iOS 17, *) { filters.perFrameHDRDisplayMetadataPolicy = .generate }
      }
      let composed = AVAssetReaderVideoCompositionOutput(
        videoTracks: try await asset.loadTracks(withMediaType: .video),
        videoSettings: [
          kCVPixelBufferPixelFormatTypeKey as String: keepHighDepth
            ? kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
          AVVideoColorPropertiesKey: color,
        ])
      composed.videoComposition = filters
      composed.alwaysCopiesSampleData = false
      videoOutput = composed
      var compression: [String: Any] = [
        AVVideoAverageBitRateKey: plan.bitrateKbps * 1000,
        AVVideoExpectedSourceFrameRateKey: plan.fps, AVVideoMaxKeyFrameIntervalDurationKey: 2,
      ]
      if plan.mime == "video/hevc" {
        compression[AVVideoProfileLevelKey] =
          keepHighDepth
          ? kVTProfileLevel_HEVC_Main10_AutoLevel : kVTProfileLevel_HEVC_Main_AutoLevel
        if keepHDR {
          compression[kVTCompressionPropertyKey_HDRMetadataInsertionMode as String] =
            kVTHDRMetadataInsertionMode_Auto
        }
      } else {
        compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
      }
      settings = [
        AVVideoCodecKey: plan.mime == "video/hevc" ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
        AVVideoWidthKey: plan.width, AVVideoHeightKey: plan.height,
        AVVideoColorPropertiesKey: color, AVVideoCompressionPropertiesKey: compression,
      ]
      guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
        throw unsupportedMediaError("Requested codec, dimensions, or HDR profile is not supported.")
      }
    }
    guard reader.canAdd(videoOutput) else {
      throw validationError("Cannot configure video reader.")
    }
    reader.add(videoOutput)
    let videoInput = AVAssetWriterInput(
      mediaType: .video, outputSettings: settings,
      sourceFormatHint: plan.canCopy ? descriptions.first : nil)
    if plan.canCopy { videoInput.transform = try await track.load(.preferredTransform) }
    videoInput.expectsMediaDataInRealTime = false
    guard writer.canAdd(videoInput) else { throw validationError("Cannot configure video writer.") }
    writer.add(videoInput)
    var pairs: [(AVAssetReaderOutput, AVAssetWriterInput)] = [(videoOutput, videoInput)]
    var copiedAudio = false
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)
    if plan.audio != .mute, let audioTrack = audioTracks.first {
      let formats = try await audioTrack.load(.formatDescriptions)
      let stream = formats.first.flatMap {
        CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee
      }
      let formatID = stream?.mFormatID ?? 0
      let audioSegments = try await audioTrack.load(.segments)
      let hasGaps = audioSegments.contains { $0.isEmpty }
      let uniformFormats =
        formats.first.map { initial in formats.allSatisfy { CFEqual(initial, $0) } } ?? false
      copiedAudio =
        audioMix == nil && (options.speed ?? 1) == 1 && !hasGaps && uniformFormats
        && plan.audio == .preserve && options.video?.audioBitrateKbps == nil
        && [
          kAudioFormatMPEG4AAC, kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_HE_V2,
          kAudioFormatAC3, kAudioFormatEnhancedAC3,
        ].contains(formatID)
      let channels = plan.audio == .stereo ? 2 : max(1, Int(stream?.mChannelsPerFrame ?? 2))
      let rate = stream?.mSampleRate ?? 48_000
      var readSettings: [String: Any]? =
        copiedAudio
        ? nil
        : [
          AVFormatIDKey: kAudioFormatLinearPCM,
          AVSampleRateKey: rate, AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 16,
          AVLinearPCMIsFloatKey: false, AVLinearPCMIsNonInterleaved: false,
        ]
      var writeSettings: [String: Any]? =
        copiedAudio
        ? nil
        : [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVSampleRateKey: rate, AVNumberOfChannelsKey: channels,
          AVEncoderBitRateKey: plan.audioBitrateKbps * 1000,
        ]
      if !copiedAudio && channels > 2 {
        var layoutSize = 0
        guard let format = formats.first,
          let layout = CMAudioFormatDescriptionGetChannelLayout(format, sizeOut: &layoutSize),
          layoutSize > 0
        else {
          throw unsupportedMediaError(
            "Audio channel layout is unavailable; choose stereo explicitly.")
        }
        let data = Data(bytes: layout, count: layoutSize)
        readSettings?[AVChannelLayoutKey] = data; writeSettings?[AVChannelLayoutKey] = data
      }
      if let writeSettings, !writer.canApply(outputSettings: writeSettings, forMediaType: .audio) {
        throw unsupportedMediaError(
          "Cannot preserve the requested audio channel layout; choose stereo explicitly.")
      }
      let audioOutput: AVAssetReaderOutput =
        (hasGaps || audioMix != nil || (options.speed ?? 1) != 1)
        ? AVAssetReaderAudioMixOutput(audioTracks: audioTracks, audioSettings: readSettings)
        : AVAssetReaderTrackOutput(track: audioTrack, outputSettings: readSettings)
      if let mixed = audioOutput as? AVAssetReaderAudioMixOutput {
        mixed.audioMix = audioMix; mixed.audioTimePitchAlgorithm = .spectral
      }
      audioOutput.alwaysCopiesSampleData = false
      let audioInput = AVAssetWriterInput(
        mediaType: .audio, outputSettings: writeSettings, sourceFormatHint: formats.first)
      audioInput.expectsMediaDataInRealTime = false
      guard reader.canAdd(audioOutput), writer.canAdd(audioInput) else {
        throw unsupportedMediaError("Requested audio configuration is unsupported.")
      }
      reader.add(audioOutput); writer.add(audioInput); pairs.append((audioOutput, audioInput))
    }
    try control.check()
    guard reader.startReading() else {
      throw reader.error ?? validationError("Cannot start video reader.")
    }
    guard writer.startWriting() else {
      throw writer.error ?? validationError("Cannot start video writer.")
    }
    writer.startSession(atSourceTime: .zero)
    try await transferSamples(
      reader: reader, writer: writer, pairs: pairs, duration: plan.durationMs / 1000,
      maxFps: plan.timing == .cap ? plan.fps : nil)
    completed = true
    return (plan.canCopy, copiedAudio)
  }

  static let imageContext = CIContext(options: [
    .cacheIntermediates: false,
    .workingFormat: CIFormat.RGBAh.rawValue,
    .workingColorSpace: CGColorSpace(name: CGColorSpace.extendedLinearITUR_2020)!,
  ])

  func makeOverlay(options: ExportVideoOptions, size: CGSize) throws -> CIImage? {
    guard let text = options.watermark else { return nil }
    guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf16.count <= 4096
    else { throw validationError("Watermark must contain 1 to 4096 characters.") }
    let opt = options.watermarkOptions
    let font: Double = opt?.fontSize ?? Double(min(size.width, size.height)) * 0.05
    let margin: Double = opt?.margin ?? Double(min(size.width, size.height)) * 0.025
    let opacity: Double = opt?.opacity ?? 1
    _ = try ExportPlanner.finite(font, min: 1, max: 512, name: "fontSize")
    _ = try ExportPlanner.finite(opacity, min: 0, max: 1, name: "opacity")
    guard margin.isFinite, margin >= 0, margin * 2 < min(size.width, size.height) else {
      throw validationError("Invalid watermark margin.")
    }
    let color = try watermarkColor(opt?.color ?? "#FFFFFFFF", opacity: opacity)
    let image = try watermarkImage(
      text, fontSize: font, color: color,
      maxSize: CGSize(width: size.width - 2 * margin, height: size.height - 2 * margin))
    let x: CGFloat, y: CGFloat
    switch opt?.position ?? .bottomRight {
    case .topLeft: x = margin; y = size.height - margin - image.extent.height
    case .topRight:
      x = size.width - margin - image.extent.width; y = size.height - margin - image.extent.height
    case .bottomLeft: x = margin; y = margin
    case .bottomRight: x = size.width - margin - image.extent.width; y = margin
    case .center:
      x = (size.width - image.extent.width) / 2; y = (size.height - image.extent.height) / 2
    }
    return image.transformed(by: CGAffineTransform(translationX: x, y: y))
  }

  func alphaInfoHasAlpha(_ alphaInfo: CGImageAlphaInfo) -> Bool {
    switch alphaInfo {
    case .first, .last, .premultipliedFirst, .premultipliedLast:
      return true
    default:
      return false
    }
  }

  func encodeImage(
    image: UIImage,
    format: CompressImageFormat,
    quality: Double,
    preserveMetadata: Bool,
    sourceMetadata: [CFString: Any]?
  ) -> Data? {
    let clampedQuality = clamp(quality, min: 0, max: 1)
    if !preserveMetadata {
      switch format {
      case .png:
        return image.pngData()
      default:
        return image.jpegData(compressionQuality: clampedQuality)
      }
    }

    guard let cgImage = image.cgImage else {
      return nil
    }
    let outData = NSMutableData()
    let uti = (format == .png ? UTType.png.identifier : UTType.jpeg.identifier) as CFString
    guard let destination = CGImageDestinationCreateWithData(outData, uti, 1, nil) else {
      return nil
    }

    var metadata = sourceMetadata ?? [:]
    metadata[kCGImagePropertyOrientation] = 1
    if var tiff = metadata[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
      tiff[kCGImagePropertyTIFFOrientation] = 1
      metadata[kCGImagePropertyTIFFDictionary] = tiff
    }
    metadata[kCGImagePropertyPixelWidth] = cgImage.width
    metadata[kCGImagePropertyPixelHeight] = cgImage.height
    if var exif = metadata[kCGImagePropertyExifDictionary] as? [CFString: Any] {
      exif[kCGImagePropertyExifPixelXDimension] = cgImage.width
      exif[kCGImagePropertyExifPixelYDimension] = cgImage.height
      metadata[kCGImagePropertyExifDictionary] = exif
    }
    if format == .jpeg {
      metadata[kCGImageDestinationLossyCompressionQuality] = clampedQuality
    }
    CGImageDestinationAddImage(destination, cgImage, metadata as CFDictionary)
    guard CGImageDestinationFinalize(destination) else {
      return nil
    }
    return outData as Data
  }

  func scaledSize(width: CGFloat, height: CGFloat, maxLongEdge: CGFloat) -> CGSize {
    let safeWidth = max(1, width)
    let safeHeight = max(1, height)
    let sourceLongEdge = max(safeWidth, safeHeight)
    guard sourceLongEdge > maxLongEdge else {
      return CGSize(width: safeWidth, height: safeHeight)
    }
    let scale = maxLongEdge / sourceLongEdge
    return CGSize(
      width: max(1, round(safeWidth * scale)),
      height: max(1, round(safeHeight * scale))
    )
  }

  func evenSize(_ size: CGSize) -> CGSize {
    let evenWidth = max(2, Int(round(size.width))) / 2 * 2
    let evenHeight = max(2, Int(round(size.height))) / 2 * 2
    return CGSize(width: evenWidth, height: evenHeight)
  }

  func clamp(_ value: Double, min minValue: Double, max maxValue: Double) -> Double {
    return Swift.max(minValue, Swift.min(maxValue, value))
  }

  func requireFinite(_ value: Double, field: String) throws {
    guard value.isFinite else {
      throw validationError("`\(field)` must be a finite number.")
    }
  }

  func requirePositiveFinite(_ value: Double, field: String) throws {
    try requireFinite(value, field: field)
    guard value > 0 else {
      throw validationError("`\(field)` must be greater than 0.")
    }
  }

  func currentControl() throws -> PipelineControl {
    guard let control = MediaJob.current?.control else {
      throw validationError("Missing media job context.")
    }
    return control
  }

  func performOperation(
    control supplied: PipelineControl? = nil, operation: OperationType = .getmediainfo,
    input: String? = nil, _ block: @escaping () async -> MediaInfoResult
  ) -> Promise<MediaInfoResult> {
    let promise = Promise<MediaInfoResult>()
    Task.detached(priority: .userInitiated) {
      var job: MediaJob?
      var success = false
      var control: PipelineControl?
      do {
        let active = try supplied ?? PipelineControl()
        control = active
        try PipelineControl.admit(active)
        try active.check()
        let owned = try MediaJob(control: active); job = owned
        let result = await MediaJob.$current.withValue(owned) { await block() }
        try active.check()
        active.emit(.validating)
        let committed = try owned.commit(result)
        success = committed.ok
        if success { active.emit(.completed, fraction: 1) }
        owned.finish(success: success)
        PipelineControl.remove(active)
        promise.resolve(withResult: committed)
      } catch {
        job?.finish(success: false)
        control?.close()
        if let control { PipelineControl.remove(control) }
        promise.resolve(
          withResult: self.makeErrorResult(
            operation: operation, type: .video, inputUri: input, error: error))
      }
    }
    return promise
  }

  public func exportVideo(
    video: String, options: ExportVideoOptions, jobId: String,
    onProgress: ((ExportProgress) -> Void)?
  ) throws -> Promise<MediaInfoResult> {
    let control = try PipelineControl.registered(
      id: jobId, limits: options.limits, progress: onProgress)
    control.emit(.queued)
    return performOperation(control: control, operation: .exportvideo, input: video) {
      do {
        return try await self.exportResolved(
          inputUri: video, path: try await self.getLocalFilePath(video), options: options,
          operation: .exportvideo)
      } catch {
        return self.makeErrorResult(
          operation: .exportvideo, type: .video, inputUri: video, error: error)
      }
    }
  }
  public func cancelJob(jobId: String) -> Promise<Bool> {
    let promise = Promise<Bool>(); promise.resolve(withResult: PipelineControl.cancelJob(jobId));
    return promise
  }

  func run(
    _ operation: OperationType, input: String?, type: MediaType = .video,
    _ body: @escaping () async throws -> MediaInfoResult
  ) -> Promise<MediaInfoResult> {
    performOperation(operation: operation, input: input) {
      do { return try await body() } catch {
        return self.makeErrorResult(operation: operation, type: type, inputUri: input, error: error)
      }
    }
  }

  func outputURL(extension ext: String) throws -> URL {
    guard let job = MediaJob.current else {
      throw validationError("Missing media operation context.")
    }
    return try job.output(ext)
  }

  func getLocalFilePath(_ source: String) async throws -> String {
    guard let job = MediaJob.current else {
      throw validationError("Missing media operation context.")
    }
    return try await job.resolve(source)
  }

  func boundedImageEdge(width: Double, height: Double, requested: Double, explicit: Bool) throws
    -> Double
  {
    guard width.isFinite, height.isFinite, width > 0, height > 0 else {
      throw validationError("Invalid image dimensions.")
    }
    let pixels = min(16_777_216.0, Double(ProcessInfo.processInfo.physicalMemory) / 64)
    let aspect = width / height
    let edge = sqrt(pixels * max(aspect, 1 / aspect))
    guard !explicit || requested <= edge else {
      throw validationError("Requested image dimensions exceed the memory budget.")
    }
    return min(requested, edge)
  }

  func downsampleImage(path: String, maxLongEdge: Double) throws -> UIImage {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
      let cgImage = CGImageSourceCreateThumbnailAtIndex(
        source, 0,
        [
          kCGImageSourceCreateThumbnailFromImageAlways: true,
          kCGImageSourceCreateThumbnailWithTransform: true,
          kCGImageSourceShouldCacheImmediately: true,
          kCGImageSourceThumbnailMaxPixelSize: max(1, maxLongEdge),
        ] as CFDictionary)
    else { throw unsupportedMediaError("Unable to decode image.") }
    return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
  }

  func outputResult(_ operation: OperationType, input: String?, output: URL) async throws
    -> MediaInfoResult
  {
    let info = try await detectVideoInfo(atPath: output.path)
    return makeResult(
      ok: true, operation: operation, type: .video, inputUri: input, outputUri: output.path,
      media: videoMedia(info, path: output.path))
  }

  func videoMedia(_ info: VideoSource, path: String) -> MediaInfoMedia? {
    buildMediaInfo(
      durationMs: info.durationMs, width: info.width, height: info.height,
      fps: info.fps > 0 ? info.fps : nil,
      format: URL(fileURLWithPath: path).pathExtension, sizeBytes: fileSizeBytes(atPath: path),
      audioTracks: Double(info.audioTracks), videoTracks: 1,
      codec: info.mime, hdr: info.hdr, bitDepth: Double(info.bitDepth),
      bitrateKbps: info.bitrateKbps,
      audioChannels: Double(info.audioChannels), audioSampleRate: info.audioSampleRate)
  }

  public func convertImageToVideo(image: String, duration: Double) -> Promise<MediaInfoResult> {
    run(.convertimagetovideo, input: image) {
      guard duration.isFinite, duration > 0, duration <= 86_400 else {
        throw self.validationError("Duration must be in (0, 86400] seconds.")
      }
      let path = try await self.getLocalFilePath(image)
      return try await withMediaEncoder {
        let source = try self.downsampleImage(path: path, maxLongEdge: 1920)
        let size = self.evenSize(source.size)
        let width = Int(size.width), height = Int(size.height)
        let output = try self.outputURL(extension: "mp4")
        let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        let input = AVAssetWriterInput(
          mediaType: .video,
          outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
              AVVideoAverageBitRateKey: max(2_000_000, width * height * 4)
            ],
          ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
          assetWriterInput: input,
          sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
          ])
        guard writer.canAdd(input) else {
          throw self.unsupportedMediaError("Cannot add image video writer input.")
        }
        writer.add(input)
        guard writer.startWriting() else {
          throw writer.error ?? self.validationError("Cannot start video writer.")
        }
        writer.startSession(atSourceTime: .zero)
        guard let pool = adaptor.pixelBufferPool,
          let buffer = self.createPixelBuffer(
            from: source, pixelBufferPool: pool, width: width, height: height)
        else {
          writer.cancelWriting()
          throw self.validationError("Cannot create image video frame.")
        }
        try await self.writeFrames(
          adaptor: adaptor, writerInput: input, pixelBuffer: buffer,
          frameDuration: CMTime(value: 1, timescale: 30),
          totalFrames: max(1, Int(ceil(duration * 30))), writer: writer)
        return try await self.outputResult(.convertimagetovideo, input: image, output: output)
      }
    }
  }

  public func mergeVideos(videos: [String]) -> Promise<MediaInfoResult> {
    run(.mergevideos, input: videos.first) {
      guard !videos.isEmpty else { throw self.validationError("At least one video is required.") }
      var paths: [String] = []
      for uri in videos { paths.append(try await self.getLocalFilePath(uri)) }
      var infos: [VideoSource] = []
      for path in paths { infos.append(try await self.detectVideoInfo(atPath: path)) }
      let firstInfo = infos[0]
      let audioSource = infos.first { $0.audioTracks > 0 }
      guard
        infos.filter({ $0.audioTracks > 0 }).allSatisfy({
          $0.audioChannels == audioSource?.audioChannels
        })
      else {
        throw self.validationError(
          "Merge audio layouts differ; normalize inputs with an explicit stereo policy first.")
      }
      guard infos.allSatisfy({ $0.bitDepth <= 10 }) else {
        throw self.unsupportedMediaError(
          "Merge cannot preserve this bit depth; convert inputs with an explicit SDR policy first.")
      }
      let renderSize = self.evenSize(
        self.scaledSize(
          width: firstInfo.width, height: firstInfo.height,
          maxLongEdge: max(firstInfo.width, firstInfo.height)))
      let composition = AVMutableComposition()
      guard
        let video = composition.addMutableTrack(
          withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
      else {
        throw self.validationError("Cannot create composition track.")
      }
      var audio: AVMutableCompositionTrack?
      var cursor = CMTime.zero
      var instructions: [AVMutableVideoCompositionInstruction] = []
      var hdrSource: VideoSource?
      for (path, info) in zip(paths, infos) {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        if info.hdr && hdrSource == nil { hdrSource = info }
        let duration = CMTime(seconds: info.durationMs / 1000, preferredTimescale: 600_000)
        guard let sourceVideo = try await asset.loadTracks(withMediaType: .video).first else {
          throw self.unsupportedMediaError("No video track.")
        }
        let range = CMTimeRange(start: .zero, duration: duration)
        let videoRange = CMTimeRangeGetIntersection(
          try await sourceVideo.load(.timeRange), otherRange: range)
        guard videoRange.duration > .zero else {
          throw self.unsupportedMediaError("Clip contains no video samples.")
        }
        try await self.insertTrackRange(
          videoRange, from: sourceVideo, into: video, at: CMTimeAdd(cursor, videoRange.start))
        let transform = try await sourceVideo.load(.preferredTransform)
        let size = try await sourceVideo.load(.naturalSize)
        let rect = CGRect(origin: .zero, size: size).applying(transform)
        guard rect.width.isFinite, rect.height.isFinite, abs(rect.width) > 0, abs(rect.height) > 0
        else { throw self.unsupportedMediaError("Invalid clip display transform.") }
        let scale = min(renderSize.width / abs(rect.width), renderSize.height / abs(rect.height))
        let fitted = transform.concatenating(
          CGAffineTransform(translationX: -rect.minX, y: -rect.minY)
        )
        .concatenating(CGAffineTransform(scaleX: scale, y: scale))
        .concatenating(
          CGAffineTransform(
            translationX: (renderSize.width - abs(rect.width) * scale) / 2,
            y: (renderSize.height - abs(rect.height) * scale) / 2))
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
        layer.setTransform(fitted, at: cursor)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: cursor, duration: duration)
        instruction.backgroundColor = UIColor.black.cgColor
        instruction.layerInstructions = [layer]
        instructions.append(instruction)
        if let sourceAudio = try await asset.loadTracks(withMediaType: .audio).first {
          if audio == nil {
            audio = composition.addMutableTrack(
              withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
          }
          let audioRange = CMTimeRangeGetIntersection(
            try await sourceAudio.load(.timeRange), otherRange: range)
          if audioRange.duration > .zero {
            guard let audio else { throw self.validationError("Cannot create merge audio track.") }
            try await self.insertTrackRange(
              audioRange, from: sourceAudio, into: audio, at: CMTimeAdd(cursor, audioRange.start))
          }
        }
        cursor = CMTimeAdd(cursor, duration)
      }
      let videoComposition = AVMutableVideoComposition()
      videoComposition.renderSize = renderSize
      videoComposition.frameDuration = CMTime(
        seconds: 1 / min(240, firstInfo.fps > 0 ? firstInfo.fps : 30), preferredTimescale: 600_000)
      videoComposition.sourceTrackIDForFrameTiming = video.trackID
      videoComposition.instructions = instructions
      if let hdrSource {
        videoComposition.colorPrimaries =
          hdrSource.colorPrimaries ?? AVVideoColorPrimaries_ITU_R_2020
        videoComposition.colorTransferFunction =
          hdrSource.colorTransfer ?? AVVideoTransferFunction_ITU_R_2100_HLG
        videoComposition.colorYCbCrMatrix = hdrSource.colorMatrix ?? AVVideoYCbCrMatrix_ITU_R_2020
        if #available(iOS 17, *) { videoComposition.perFrameHDRDisplayMetadataPolicy = .generate }
      }
      let output = try self.outputURL(extension: "mp4")
      let color = hdrSource ?? firstInfo
      let source = VideoSource(
        width: renderSize.width, height: renderSize.height, fps: infos.map(\.fps).max() ?? 30,
        durationMs: cursor.seconds * 1000, sizeBytes: infos.reduce(0) { $0 + $1.sizeBytes },
        audioTracks: audioSource == nil ? 0 : 1,
        extensionName: "mp4", mime: color.mime, hdr: hdrSource != nil,
        bitDepth: infos.map(\.bitDepth).max() ?? 8,
        bitrateKbps: infos.compactMap(\.bitrateKbps).max(),
        audioChannels: audioSource?.audioChannels ?? 0,
        audioSampleRate: audioSource?.audioSampleRate ?? 0, colorPrimaries: color.colorPrimaries,
        colorTransfer: color.colorTransfer, colorMatrix: color.colorMatrix)
      let options = ExportVideoOptions(
        trim: nil, watermark: nil, watermarkOptions: nil, preset: .preserve, video: nil,
        audio: .preserve, preserveMetadata: false, limits: nil)
      let plan = try ExportPlanner.plan(source, options: options, forceEncode: true)
      let copied = try await withMediaEncoder {
        try await self.encodeVideo(
          asset: composition, output: output, source: source, plan: plan, options: options,
          metadata: [], videoComposition: videoComposition)
      }
      let actual = try await self.detectVideoInfo(atPath: output.path)
      guard hdrSource == nil || actual.hdr else {
        throw self.validationError("Encoder could not preserve merged HDR output.")
      }
      guard actual.bitDepth >= source.bitDepth else {
        throw self.validationError("Encoder reduced the merged video bit depth.")
      }
      guard audioSource == nil || actual.audioChannels == audioSource?.audioChannels else {
        throw self.validationError("Encoder changed the merged audio channel layout.")
      }
      let control = try self.currentControl()
      return self.makeResult(
        ok: true, operation: .mergevideos, type: .video, inputUri: videos.first,
        outputUri: output.path, media: self.videoMedia(actual, path: output.path),
        diagnostics: ExportDiagnostics(
          elapsedMs: control.elapsedMs,
          outputSizeBytes: self.fileSizeBytes(atPath: output.path) ?? 0,
          peakProcessMemoryBytes: control.peakMemoryBytes,
          videoPath: .encoded,
          audioPath: actual.audioTracks == 0 ? .absent : copied.1 ? .copied : .encoded,
          codec: actual.mime,
          estimatedSizeBytes: plan.estimatedSizeBytes, frameCount: Double(control.frameCount)))
    }
  }

  func validateSegment(_ segment: VideoSegment, durationMs: Double) throws {
    guard segment.startMs.isFinite, segment.endMs.isFinite, segment.startMs >= 0,
      segment.endMs > segment.startMs, segment.endMs <= durationMs
    else {
      throw validationError("Segments must satisfy 0 <= startMs < endMs <= video duration.")
    }
  }

  func segmentRange(_ segment: VideoSegment) -> CMTimeRange {
    CMTimeRange(
      start: CMTime(seconds: segment.startMs / 1000, preferredTimescale: 1_000_000),
      end: CMTime(seconds: segment.endMs / 1000, preferredTimescale: 1_000_000))
  }

  public func splitVideo(video: String, segments: [VideoSegment]) -> Promise<MediaInfoResult> {
    run(.splitvideo, input: video) {
      guard !segments.isEmpty else {
        throw self.validationError("At least one segment is required.")
      }
      let path = try await self.getLocalFilePath(video)
      let info = try await self.detectVideoInfo(atPath: path)
      for segment in segments { try self.validateSegment(segment, durationMs: info.durationMs) }
      var outputs: [String] = []
      for segment in segments {
        let result = try await self.exportResolved(
          inputUri: video, path: path,
          options: ExportVideoOptions(
            trim: segment,
            watermark: nil, watermarkOptions: nil, preset: .preserve, video: nil, audio: nil,
            preserveMetadata: false, limits: nil), operation: .trimvideo, sourceInfo: info)
        guard let output = result.outputUri else {
          throw self.validationError("Missing split output.")
        }
        outputs.append(output)
      }
      return self.makeResult(
        ok: true, operation: .splitvideo, type: .video, inputUri: video, segments: outputs)
    }
  }

  public func trimVideo(video: String, segment: VideoSegment) -> Promise<MediaInfoResult> {
    run(.trimvideo, input: video) {
      try await self.exportResolved(
        inputUri: video, path: try await self.getLocalFilePath(video),
        options: ExportVideoOptions(
          trim: segment,
          watermark: nil, watermarkOptions: nil, preset: .preserve, video: nil, audio: nil,
          preserveMetadata: false, limits: nil), operation: .trimvideo)
    }
  }

  public func removeAudio(video: String) -> Promise<MediaInfoResult> {
    run(.removeaudio, input: video) {
      try await self.exportResolved(
        inputUri: video, path: try await self.getLocalFilePath(video),
        options: ExportVideoOptions(
          trim: nil,
          watermark: nil, watermarkOptions: nil, preset: .preserve, video: nil, audio: .mute,
          preserveMetadata: false, limits: nil), operation: .removeaudio)
    }
  }

  public func extractThumbnail(video: String, options: ThumbnailOptions?) -> Promise<
    MediaInfoResult
  > {
    run(.extractthumbnail, input: video, type: .image) {
      let timeMs = options?.timeMs ?? 0
      let edge = options?.maxLongEdge ?? 1280
      let quality = options?.quality ?? 0.9
      guard timeMs.isFinite, timeMs >= 0, edge.isFinite, edge >= 1, edge <= 8192,
        quality.isFinite, (0...1).contains(quality)
      else { throw self.validationError("Invalid thumbnail time, dimensions or quality.") }
      let path = try await self.getLocalFilePath(video)
      let info = try await self.detectVideoInfo(atPath: path)
      guard timeMs < info.durationMs else {
        throw self.validationError("timeMs must be less than video duration.")
      }
      let control = try self.currentControl()
      try await MediaWorkGate.images.acquire(control)
      defer { Task { await MediaWorkGate.images.release() } }
      let boundedEdge = try self.boundedImageEdge(
        width: info.width, height: info.height, requested: min(edge, max(info.width, info.height)),
        explicit: options?.maxLongEdge != nil)
      let generator = AVAssetImageGenerator(asset: AVURLAsset(url: URL(fileURLWithPath: path)))
      generator.appliesPreferredTrackTransform = true
      generator.maximumSize = CGSize(width: boundedEdge, height: boundedEdge)
      if (options?.precision ?? .precise) == .precise {
        generator.requestedTimeToleranceBefore = .zero;
        generator.requestedTimeToleranceAfter = .zero
      }
      let hook = control.onCancel { generator.cancelAllCGImageGeneration() }
      defer { control.removeHook(hook) }
      let time = CMTime(seconds: timeMs / 1000, preferredTimescale: 1_000_000)
      let extracted: (CGImage, CMTime) = try await withCheckedThrowingContinuation { continuation in
        generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) {
          _, image, actualTime, result, error in
          if result == .succeeded, let image {
            continuation.resume(returning: (image, actualTime))
          } else {
            continuation.resume(
              throwing: error ?? self.validationError("Cannot extract thumbnail."))
          }
        }
      }
      try control.check()
      let (image, actualTime) = extracted
      guard let data = UIImage(cgImage: image).jpegData(compressionQuality: quality) else {
        throw self.validationError("Cannot encode thumbnail.")
      }
      let output = try self.outputURL(extension: "jpg")
      try data.write(to: output, options: .atomic)
      return self.makeResult(
        ok: true, operation: .extractthumbnail, type: .image, inputUri: video,
        outputUri: output.path,
        media: self.buildMediaInfo(
          width: Double(image.width), height: Double(image.height), format: "jpg",
          sizeBytes: Double(data.count), audioTracks: 0, videoTracks: 0),
        actualTimeMs: actualTime.seconds * 1000)
    }
  }

  public func deleteGeneratedFile(uri: String) -> Promise<Bool> {
    let promise = Promise<Bool>()
    Task.detached {
      do { promise.resolve(withResult: try MediaJob.deleteOutput(uri)) } catch {
        promise.reject(withError: error)
      }
    }
    return promise
  }

  public func watermarkVideo(video: String, watermark: String, options: WatermarkOptions?)
    -> Promise<MediaInfoResult>
  {
    run(.watermarkvideo, input: video) {
      try await self.exportResolved(
        inputUri: video, path: try await self.getLocalFilePath(video),
        options: ExportVideoOptions(
          trim: nil, watermark: watermark,
          watermarkOptions: options, preset: .preserve, video: nil, audio: nil,
          preserveMetadata: false, limits: nil), operation: .watermarkvideo)
    }
  }

  func watermarkColor(_ value: String, opacity: Double) throws -> UIColor {
    guard value.range(of: "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$", options: .regularExpression) != nil,
      let hex = UInt64(value.dropFirst(), radix: 16)
    else { throw validationError("color must be #RRGGBB or #RRGGBBAA.") }
    let rgb = value.count == 9 ? hex >> 8 : hex
    let alpha = value.count == 9 ? Double(hex & 255) / 255 : 1
    return UIColor(
      red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255,
      blue: Double(rgb & 255) / 255, alpha: alpha * opacity)
  }

  func watermarkImage(_ text: String, fontSize: Double, color: UIColor, maxSize: CGSize) throws
    -> CIImage
  {
    var fontSize = CGFloat(fontSize)
    let paragraph = NSMutableParagraphStyle()
    paragraph.lineBreakMode = .byWordWrapping
    var attributes: [NSAttributedString.Key: Any]
    var rect: CGRect
    repeat {
      attributes = [
        .font: UIFont.systemFont(ofSize: fontSize, weight: .bold), .foregroundColor: color,
        .paragraphStyle: paragraph,
      ]
      rect =
        (text as NSString).boundingRect(
          with: CGSize(width: maxSize.width, height: .greatestFiniteMagnitude),
          options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil
        ).integral
      if rect.height <= maxSize.height || fontSize <= 1 { break }
      fontSize = max(1, fontSize * 0.85)
    } while true
    guard rect.height <= maxSize.height else {
      throw validationError("Watermark cannot fit inside video.")
    }
    let size = CGSize(width: max(1, min(maxSize.width, rect.width)), height: max(1, rect.height))
    guard
      size.width * size.height
        <= min(16_777_216.0, Double(ProcessInfo.processInfo.physicalMemory) / 64)
    else { throw validationError("Watermark exceeds the image memory budget.") }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = false
    let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
      (text as NSString).draw(
        with: CGRect(origin: .zero, size: size),
        options: [.usesLineFragmentOrigin, .usesFontLeading],
        attributes: attributes, context: nil)
    }
    guard let cgImage = rendered.cgImage else {
      throw validationError("Cannot rasterize watermark.")
    }
    return CIImage(cgImage: cgImage)
  }

  func export(
    asset: AVAsset, output: URL, videoComposition: AVVideoComposition? = nil,
    range: CMTimeRange? = nil, passthrough: Bool = false, metadata: [AVMetadataItem] = [],
    hdr: Bool = false
  ) async throws {
    let control = try currentControl()
    try await withMediaEncoder {
      guard
        let session = AVAssetExportSession(
          asset: asset,
          presetName: passthrough
            ? AVAssetExportPresetPassthrough
            : hdr ? AVAssetExportPresetHEVCHighestQuality : AVAssetExportPresetHighestQuality)
      else { throw self.unsupportedMediaError("Cannot create video exporter.") }
      session.outputURL = output; session.outputFileType = .mp4
      session.videoComposition = videoComposition; session.metadata = metadata
      session.shouldOptimizeForNetworkUse = true
      if let range { session.timeRange = range }
      try await runAssetExport(session, control: control)
    }
  }
  func writeFrames(
    adaptor: AVAssetWriterInputPixelBufferAdaptor,
    writerInput: AVAssetWriterInput,
    pixelBuffer: CVPixelBuffer,
    frameDuration: CMTime,
    totalFrames: Int,
    writer: AVAssetWriter
  ) async throws {
    try await transferImageFrames(
      adaptor: adaptor, input: writerInput, writer: writer, buffer: pixelBuffer,
      frameDuration: frameDuration, totalFrames: totalFrames, control: try currentControl())
  }

  // Helper function to create a pixel buffer from a UIImage
  func createPixelBuffer(
    from image: UIImage, pixelBufferPool: CVPixelBufferPool, width: Int, height: Int
  ) -> CVPixelBuffer? {
    guard let cgImage = image.cgImage else { return nil }
    var pixelBufferOut: CVPixelBuffer?

    let status = CVPixelBufferPoolCreatePixelBuffer(nil, pixelBufferPool, &pixelBufferOut)
    guard status == kCVReturnSuccess, let pixelBuffer = pixelBufferOut else {
      return nil
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, [])

    // Create a context
    let rgbColorSpace = CGColorSpaceCreateDeviceRGB()
    guard
      let context = CGContext(
        data: CVPixelBufferGetBaseAddress(pixelBuffer),
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
        space: rgbColorSpace,
        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
          | CGBitmapInfo.byteOrder32Little.rawValue
      )
    else {
      CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
      return nil
    }

    // Draw the image into the context
    context.clear(CGRect(x: 0, y: 0, width: width, height: height))
    context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

    CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

    return pixelBuffer
  }
}
