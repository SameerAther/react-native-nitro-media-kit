import Foundation
@preconcurrency import AVFoundation
import UIKit
import ImageIO
import UniformTypeIdentifiers
import CoreImage
import NitroModules // Import Promise from NitroModules core

final class NitroMediaKit: HybridNitroMediaKitSpec {
    private func resolveFps(for track: AVAssetTrack) async throws -> Double? {
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

    private func buildMediaInfo(
        durationMs: Double? = nil,
        width: Double? = nil,
        height: Double? = nil,
        fps: Double? = nil,
        format: String? = nil,
        sizeBytes: Double? = nil,
        audioTracks: Double? = nil,
        videoTracks: Double? = nil
    ) -> MediaInfoMedia? {
        if durationMs == nil &&
            width == nil &&
            height == nil &&
            fps == nil &&
            format == nil &&
            sizeBytes == nil &&
            audioTracks == nil &&
            videoTracks == nil {
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
            videoTracks: videoTracks
        )
    }

    private func fileSizeBytes(atPath path: String) -> Double? {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber else {
            return nil
        }
        return size.doubleValue
    }

    private func makeResult(
        ok: Bool,
        operation: OperationType,
        type: MediaType,
        inputUri: String? = nil,
        outputUri: String? = nil,
        segments: [String]? = nil,
        media: MediaInfoMedia? = nil,
        warnings: [MediaInfoWarning]? = nil,
        error: MediaInfoError? = nil
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
            error: error
        )
    }

    private func makeErrorResult(
        operation: OperationType,
        type: MediaType,
        inputUri: String? = nil,
        outputUri: String? = nil,
        error: Error
    ) -> MediaInfoResult {
        let nsError = error as NSError
        let errorInfo = MediaInfoError(
            code: "\(nsError.domain):\(nsError.code)",
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

    private struct ResolvedCompressOptions {
        let preset: CompressionPreset
        let preserveMetadata: Bool
        let image: CompressImageOptions?
        let video: CompressVideoOptions?
    }

    private struct DetectedImageInfo {
        let width: Int
        let height: Int
        let hasAlpha: Bool
        let sizeBytes: Double
        let extensionName: String?
        let sourceMetadata: [CFString: Any]?
    }

    private struct DetectedVideoInfo {
        let width: Double
        let height: Double
        let fps: Double
        let durationMs: Double
        let sizeBytes: Double
        let audioTracks: Int
        let extensionName: String?
    }

    private struct ImageCompressionPlan {
        let format: CompressImageFormat
        let quality: Double?
        let maxLongEdge: Double
    }

    private struct VideoCompressionPlan {
        let maxLongEdge: Double
        let maxFps: Double
        let videoBitrateKbps: Double
        let audioBitrateKbps: Double
        let keepAudio: Bool
    }

    private func warning(code: String, message: String) -> MediaInfoWarning {
        return MediaInfoWarning(code: code, message: message)
    }

    private func validationError(_ message: String) -> NSError {
        NSError(
            domain: "ValidationError",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    private func unsupportedMediaError(_ message: String) -> NSError {
        NSError(
            domain: "UnsupportedMediaError",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }

    public func getMediaInfo(inputUri: String) -> Promise<MediaInfoResult> {
        return self.performOperation {
            do {
                let localPath = try await self.getLocalFilePath(inputUri)
                let url = URL(fileURLWithPath: localPath)
                let ext = url.pathExtension.lowercased()
                let sizeBytes = self.fileSizeBytes(atPath: localPath)

                if let image = self.detectImageInfo(atPath: localPath) {
                    let media = self.buildMediaInfo(
                        width: Double(image.width), height: Double(image.height),
                        format: image.extensionName, sizeBytes: sizeBytes,
                        audioTracks: 0, videoTracks: 0
                    )
                    return self.makeResult(
                        ok: true,
                        operation: .getmediainfo,
                        type: .image,
                        inputUri: inputUri,
                        media: media
                    )
                }

                let asset = AVAsset(url: url)
                let duration = try await asset.load(.duration)
                let videoTracks = try await asset.loadTracks(withMediaType: .video)
                let audioTracks = try await asset.loadTracks(withMediaType: .audio)

                guard !videoTracks.isEmpty else { throw self.unsupportedMediaError("Input has no video track.") }
                let primaryVideoTrack = videoTracks.first
                var width: Double?
                var height: Double?
                var fps: Double?
                if let primaryVideoTrack = primaryVideoTrack {
                    let naturalSize = try await primaryVideoTrack.load(.naturalSize)
                    let preferredTransform = try await primaryVideoTrack.load(.preferredTransform)
                    let transformedRect = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
                    let transformedWidth = abs(transformedRect.size.width)
                    let transformedHeight = abs(transformedRect.size.height)
                    let naturalWidth = abs(naturalSize.width)
                    let naturalHeight = abs(naturalSize.height)
                    if transformedWidth > 0 && transformedHeight > 0 {
                        width = Double(transformedWidth)
                        height = Double(transformedHeight)
                    } else if naturalWidth > 0 && naturalHeight > 0 {
                        width = Double(naturalWidth)
                        height = Double(naturalHeight)
                    }
                    fps = try await self.resolveFps(for: primaryVideoTrack) ?? 30
                }
                let media = self.buildMediaInfo(
                    durationMs: duration.seconds.isFinite ? duration.seconds * 1000.0 : nil,
                    width: width,
                    height: height,
                    fps: fps,
                    format: ext.isEmpty ? nil : ext,
                    sizeBytes: sizeBytes,
                    audioTracks: Double(audioTracks.count),
                    videoTracks: Double(videoTracks.count)
                )

                return self.makeResult(
                    ok: true,
                    operation: .getmediainfo,
                    type: .video,
                    inputUri: inputUri,
                    media: media
                )
            } catch {
                return self.makeErrorResult(
                    operation: .getmediainfo,
                    type: .video,
                    inputUri: inputUri,
                    error: error
                )
            }
        }
    }

    public func compressMedia(inputUri: String, options: CompressMediaOptions?) -> Promise<MediaInfoResult> {
        return self.performOperation {
            var typeGuess: MediaType = .video
            do {
                let localPath = try await self.getLocalFilePath(inputUri)
                let resolvedOptions = self.resolveCompressOptions(options)

                if let imageInfo = self.detectImageInfo(atPath: localPath) {
                    typeGuess = .image
                    return try self.compressImage(
                        inputUri: inputUri,
                        localPath: localPath,
                        imageInfo: imageInfo,
                        options: resolvedOptions
                    )
                }

                typeGuess = .video
                let videoInfo = try await self.detectVideoInfo(atPath: localPath)
                return try await self.compressVideo(
                    inputUri: inputUri,
                    localPath: localPath,
                    videoInfo: videoInfo,
                    options: resolvedOptions
                )
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

    private func resolveCompressOptions(_ options: CompressMediaOptions?) -> ResolvedCompressOptions {
        return ResolvedCompressOptions(
            preset: options?.preset ?? .auto,
            preserveMetadata: options?.preserveMetadata ?? false,
            image: options?.image,
            video: options?.video
        )
    }

    private func detectImageInfo(atPath path: String) -> DetectedImageInfo? {
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
        let cgImage = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
        let hasAlpha = cgImage.map { self.alphaInfoHasAlpha($0.alphaInfo) } ?? false

        return DetectedImageInfo(
            width: displayWidth,
            height: displayHeight,
            hasAlpha: hasAlpha,
            sizeBytes: self.fileSizeBytes(atPath: path) ?? 0,
            extensionName: CGImageSourceGetType(source).flatMap { UTType($0 as String)?.preferredFilenameExtension },
            sourceMetadata: properties
        )
    }

    private func detectVideoInfo(atPath path: String) async throws -> DetectedVideoInfo {
        let url = URL(fileURLWithPath: path)
        let asset = AVAsset(url: url)
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

        return DetectedVideoInfo(
            width: width,
            height: height,
            fps: try await self.resolveFps(for: videoTrack) ?? 30,
            durationMs: duration.seconds * 1000.0,
            sizeBytes: self.fileSizeBytes(atPath: path) ?? 0,
            audioTracks: audioTracks.count,
            extensionName: url.pathExtension.isEmpty ? nil : url.pathExtension.lowercased()
        )
    }

    private func compressImage(
        inputUri: String,
        localPath: String,
        imageInfo: DetectedImageInfo,
        options: ResolvedCompressOptions
    ) throws -> MediaInfoResult {
        var warnings: [MediaInfoWarning] = []
        let plan = try resolveImageCompressionPlan(info: imageInfo, options: options)

        if plan.format == .png, options.image?.quality != nil {
            warnings.append(warning(code: "IMAGE_QUALITY_IGNORED", message: "`image.quality` is ignored for PNG output."))
        }
        if imageInfo.sizeBytes > 0, imageInfo.sizeBytes <= 400 * 1024 {
            warnings.append(warning(code: "ALREADY_SMALL", message: "Input image is already small; compression savings may be limited."))
        }

        let resized = try downsampleImage(path: localPath, maxLongEdge: plan.maxLongEdge)
        let targetSize = resized.size

        guard let encodedData = encodeImage(
            image: resized,
            format: plan.format,
            quality: plan.quality ?? 0.8,
            preserveMetadata: options.preserveMetadata,
            sourceMetadata: imageInfo.sourceMetadata
        ) else {
            throw unsupportedMediaError("Failed to encode compressed image.")
        }

        let outputExt = plan.format == .png ? "png" : "jpg"
        let outputURL = try outputURL(extension: outputExt)
        try encodedData.write(to: outputURL, options: .atomic)

        let outputSize = self.fileSizeBytes(atPath: outputURL.path)
        if imageInfo.sizeBytes > 0, let outputSize, outputSize >= imageInfo.sizeBytes * 0.95 {
            warnings.append(warning(code: "LIMITED_SAVINGS", message: "Compressed output is close to source size."))
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

    private func compressVideo(
        inputUri: String,
        localPath: String,
        videoInfo: DetectedVideoInfo,
        options: ResolvedCompressOptions
    ) async throws -> MediaInfoResult {
        var warnings: [MediaInfoWarning] = []
        let plan = try resolveVideoCompressionPlan(info: videoInfo, options: options)
        if videoInfo.sizeBytes > 0, videoInfo.sizeBytes <= 1_000 * 1024 {
            warnings.append(warning(code: "ALREADY_SMALL", message: "Input video is already small; compression savings may be limited."))
        }
        if !plan.keepAudio, videoInfo.audioTracks == 0 {
            warnings.append(warning(code: "NO_AUDIO_TRACK", message: "Input has no audio track to remove."))
        }

        let targetSize = evenSize(
            scaledSize(
                width: CGFloat(videoInfo.width),
                height: CGFloat(videoInfo.height),
                maxLongEdge: CGFloat(plan.maxLongEdge)
            )
        )
        let outputPath = try await transcodeVideo(
            inputPath: localPath,
            targetSize: targetSize,
            plan: plan,
            preserveMetadata: options.preserveMetadata
        )
        let outputInfo = try? await detectVideoInfo(atPath: outputPath)
        let outputSize = fileSizeBytes(atPath: outputPath)
        if videoInfo.sizeBytes > 0, let outputSize, outputSize >= videoInfo.sizeBytes * 0.95 {
            warnings.append(warning(code: "LIMITED_SAVINGS", message: "Compressed output is close to source size."))
        }

        let media = buildMediaInfo(
            durationMs: outputInfo?.durationMs ?? videoInfo.durationMs,
            width: outputInfo?.width ?? Double(targetSize.width),
            height: outputInfo?.height ?? Double(targetSize.height),
            fps: outputInfo?.fps ?? plan.maxFps,
            format: "mp4",
            sizeBytes: outputSize,
            audioTracks: plan.keepAudio ? Double(outputInfo?.audioTracks ?? videoInfo.audioTracks) : 0,
            videoTracks: 1
        )

        return makeResult(
            ok: true,
            operation: .compressmedia,
            type: .video,
            inputUri: inputUri,
            outputUri: outputPath,
            media: media,
            warnings: warnings.isEmpty ? nil : warnings
        )
    }

    private func resolveImageCompressionPlan(
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
        case .high:
            presetLongEdge = min(sourceLongEdge, 2560)
        case .balanced:
            presetLongEdge = min(sourceLongEdge, 1920)
        case .small:
            presetLongEdge = min(sourceLongEdge, 1280)
        }

        if let maxLongEdge = options.image?.maxLongEdge {
            try requirePositiveFinite(maxLongEdge, field: "image.maxLongEdge")
            guard maxLongEdge <= 8192 else { throw validationError("image.maxLongEdge must not exceed 8192.") }
        }
        let finalLongEdge = min(
            sourceLongEdge,
            max(2, options.image?.maxLongEdge ?? presetLongEdge)
        )

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
        case .high:
            presetQuality = 0.9
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

    private func resolveVideoCompressionPlan(
        info: DetectedVideoInfo,
        options: ResolvedCompressOptions
    ) throws -> VideoCompressionPlan {
        let durationSec = max(info.durationMs / 1000.0, 0.001)
        let sourceLongEdge = max(info.width, info.height)
        let sourceFps = info.fps > 0 ? info.fps : 30
        let inputBitrateKbps = (info.sizeBytes * 8.0) / durationSec / 1000.0

        let presetLongEdge: Double
        switch options.preset {
        case .auto:
            if sourceLongEdge > 1920 {
                presetLongEdge = 1280
            } else if durationSec > 120 {
                presetLongEdge = min(sourceLongEdge, 720)
            } else if durationSec > 30 {
                presetLongEdge = min(sourceLongEdge, 960)
            } else {
                presetLongEdge = min(sourceLongEdge, 1080)
            }
        case .high:
            presetLongEdge = min(sourceLongEdge, 1280)
        case .balanced:
            presetLongEdge = min(sourceLongEdge, 960)
        case .small:
            presetLongEdge = min(sourceLongEdge, 720)
        }

        let presetFps: Double
        switch options.preset {
        case .small:
            presetFps = min(sourceFps, 24)
        default:
            presetFps = min(sourceFps, 30)
        }

        let presetVideoBitrate: Double
        switch options.preset {
        case .auto:
            let base: Double
            if presetLongEdge <= 480 {
                base = 700
            } else if presetLongEdge <= 720 {
                base = 1200
            } else if presetLongEdge <= 960 {
                base = 1800
            } else {
                base = 2600
            }
            let multiplier: Double
            if durationSec <= 30 {
                multiplier = 1.15
            } else if durationSec <= 120 {
                multiplier = 1.0
            } else {
                multiplier = 0.82
            }
            let target = clamp(base * multiplier, min: 600, max: 3200)
            presetVideoBitrate = min(target, max(600, inputBitrateKbps * 0.85))
        case .high:
            presetVideoBitrate = 2800
        case .balanced:
            presetVideoBitrate = 1800
        case .small:
            presetVideoBitrate = 1100
        }

        let presetAudioBitrate: Double
        switch options.preset {
        case .auto:
            if durationSec > 300 {
                presetAudioBitrate = 64
            } else if durationSec <= 30 {
                presetAudioBitrate = 128
            } else {
                presetAudioBitrate = 96
            }
        case .high:
            presetAudioBitrate = 128
        case .balanced:
            presetAudioBitrate = 96
        case .small:
            presetAudioBitrate = 64
        }

        if let maxLongEdge = options.video?.maxLongEdge {
            try requirePositiveFinite(maxLongEdge, field: "video.maxLongEdge")
            guard maxLongEdge <= 8192 else { throw validationError("video.maxLongEdge must not exceed 8192.") }
        }
        if let maxFps = options.video?.maxFps {
            try requirePositiveFinite(maxFps, field: "video.maxFps")
        }
        if let bitrate = options.video?.videoBitrateKbps {
            try requirePositiveFinite(bitrate, field: "video.videoBitrateKbps")
        }
        if let bitrate = options.video?.audioBitrateKbps {
            try requirePositiveFinite(bitrate, field: "video.audioBitrateKbps")
        }

        return VideoCompressionPlan(
            maxLongEdge: min(sourceLongEdge, max(2, options.video?.maxLongEdge ?? presetLongEdge)),
            maxFps: min(sourceFps, clamp(options.video?.maxFps ?? presetFps, min: 1, max: 120)),
            videoBitrateKbps: clamp(options.video?.videoBitrateKbps ?? presetVideoBitrate, min: 300, max: 12_000),
            audioBitrateKbps: clamp(options.video?.audioBitrateKbps ?? presetAudioBitrate, min: 32, max: 320),
            keepAudio: options.video?.keepAudio ?? true
        )
    }

    private func transcodeVideo(
        inputPath: String,
        targetSize: CGSize,
        plan: VideoCompressionPlan,
        preserveMetadata: Bool,
        compositionOverride: AVVideoComposition? = nil
    ) async throws -> String {
        let inputURL = URL(fileURLWithPath: inputPath)
        let asset = AVAsset(url: inputURL)
        let duration = try await asset.load(.duration)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw unsupportedMediaError("No video track found in input.")
        }
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first

        let outputURL = try outputURL(extension: "mp4")
        let reader = try AVAssetReader(asset: asset)
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        var completed = false
        defer {
            if !completed { reader.cancelReading(); writer.cancelWriting() }
        }
        writer.shouldOptimizeForNetworkUse = true
        if preserveMetadata {
            writer.metadata = try await asset.load(.metadata)
        }

        let normalizedTarget = evenSize(targetSize)
        let naturalSize = try await videoTrack.load(.naturalSize)
        let preferredTransform = try await videoTrack.load(.preferredTransform)
        let videoComposition: AVVideoComposition = compositionOverride ?? makeCompressionVideoComposition(
            duration: duration,
            track: videoTrack,
            naturalSize: naturalSize,
            preferredTransform: preferredTransform,
            targetSize: normalizedTarget,
            fps: plan.maxFps
        )
        let videoOutput = AVAssetReaderVideoCompositionOutput(
            videoTracks: [videoTrack],
            videoSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            ]
        )
        videoOutput.videoComposition = videoComposition
        videoOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput) else {
            throw unsupportedMediaError("Unable to add video reader output.")
        }
        reader.add(videoOutput)

        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(normalizedTarget.width),
            AVVideoHeightKey: Int(normalizedTarget.height),
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: Int(plan.videoBitrateKbps * 1000),
                AVVideoExpectedSourceFrameRateKey: Int(plan.maxFps),
                AVVideoMaxKeyFrameIntervalDurationKey: 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
            ]
        ]
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput) else {
            throw unsupportedMediaError("Unable to add video writer input.")
        }
        writer.add(videoInput)

        var audioInput: AVAssetWriterInput?
        var audioOutput: AVAssetReaderTrackOutput?
        if plan.keepAudio, let audioTrack {
            let descriptions = try await audioTrack.load(.formatDescriptions)
            let stream = descriptions.first.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
            let channels = min(2, max(1, Int(stream?.mChannelsPerFrame ?? 2)))
            let sampleRate = stream?.mSampleRate ?? 44_100
            let output = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channels,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsNonInterleaved: false
            ])
            output.alwaysCopiesSampleData = false
            guard reader.canAdd(output) else { throw unsupportedMediaError("Cannot read audio for compression.") }
            do {
                reader.add(output)
                audioOutput = output

                let settings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVEncoderBitRateKey: Int(plan.audioBitrateKbps * 1000),
                    AVSampleRateKey: sampleRate,
                    AVNumberOfChannelsKey: channels
                ]
                let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
                input.expectsMediaDataInRealTime = false
                guard writer.canAdd(input) else { throw unsupportedMediaError("Cannot encode audio for compression.") }
                writer.add(input)
                audioInput = input
            }
        }

        guard reader.startReading() else {
            throw reader.error ?? unsupportedMediaError("Failed to start AVAssetReader.")
        }
        guard writer.startWriting() else {
            throw writer.error ?? unsupportedMediaError("Failed to start AVAssetWriter.")
        }
        writer.startSession(atSourceTime: .zero)

        var pairs: [(AVAssetReaderOutput, AVAssetWriterInput)] = [(videoOutput, videoInput)]
        if let audioOutput, let audioInput { pairs.append((audioOutput, audioInput)) }
        try await transferSamples(reader: reader, writer: writer, pairs: pairs)

        if reader.status == .failed {
            throw reader.error ?? unsupportedMediaError("Video compression reader failed.")
        }

        completed = true
        return outputURL.path
    }

    private func makeCompressionVideoComposition(
        duration: CMTime,
        track: AVAssetTrack,
        naturalSize: CGSize,
        preferredTransform: CGAffineTransform,
        targetSize: CGSize,
        fps: Double
    ) -> AVMutableVideoComposition {
        let transformedRect = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
        let displayWidth = abs(transformedRect.width)
        let displayHeight = abs(transformedRect.height)
        let safeDisplayWidth = displayWidth > 0 ? displayWidth : abs(naturalSize.width)
        let safeDisplayHeight = displayHeight > 0 ? displayHeight : abs(naturalSize.height)
        let scale = min(
            targetSize.width / max(1, safeDisplayWidth),
            targetSize.height / max(1, safeDisplayHeight)
        )
        let scaledWidth = safeDisplayWidth * scale
        let scaledHeight = safeDisplayHeight * scale
        let offsetX = (targetSize.width - scaledWidth) / 2.0
        let offsetY = (targetSize.height - scaledHeight) / 2.0

        var transform = preferredTransform
        transform = transform.concatenating(
            CGAffineTransform(translationX: -transformedRect.origin.x, y: -transformedRect.origin.y)
        )
        transform = transform.concatenating(CGAffineTransform(scaleX: scale, y: scale))
        transform = transform.concatenating(CGAffineTransform(translationX: offsetX, y: offsetY))

        let composition = AVMutableVideoComposition()
        composition.renderSize = targetSize
        composition.frameDuration = CMTime(seconds: 1 / max(1, fps), preferredTimescale: 600_000)

        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
        layerInstruction.setTransform(transform, at: .zero)
        instruction.layerInstructions = [layerInstruction]
        composition.instructions = [instruction]
        return composition
    }

    private func alphaInfoHasAlpha(_ alphaInfo: CGImageAlphaInfo) -> Bool {
        switch alphaInfo {
        case .first, .last, .premultipliedFirst, .premultipliedLast:
            return true
        default:
            return false
        }
    }

    private func encodeImage(
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

    private func scaledSize(width: CGFloat, height: CGFloat, maxLongEdge: CGFloat) -> CGSize {
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

    private func evenSize(_ size: CGSize) -> CGSize {
        let evenWidth = max(2, Int(round(size.width))) / 2 * 2
        let evenHeight = max(2, Int(round(size.height))) / 2 * 2
        return CGSize(width: evenWidth, height: evenHeight)
    }

    private func clamp(_ value: Double, min minValue: Double, max maxValue: Double) -> Double {
        return Swift.max(minValue, Swift.min(maxValue, value))
    }

    private func requireFinite(_ value: Double, field: String) throws {
        guard value.isFinite else {
            throw validationError("`\(field)` must be a finite number.")
        }
    }

    private func requirePositiveFinite(_ value: Double, field: String) throws {
        try requireFinite(value, field: field)
        guard value > 0 else {
            throw validationError("`\(field)` must be greater than 0.")
        }
    }


    private func performOperation(_ block: @escaping () async -> MediaInfoResult) -> Promise<MediaInfoResult> {
        let promise = Promise<MediaInfoResult>()
        Task.detached(priority: .userInitiated) {
            await MediaWorkGate.shared.acquire()
            let job = MediaJob()
            let result = await MediaJob.$current.withValue(job) { await block() }
            job.finish(success: result.ok)
            await MediaWorkGate.shared.release()
            promise.resolve(withResult: result)
        }
        return promise
    }

    private func run(_ operation: OperationType, input: String?, type: MediaType = .video, _ body: @escaping () async throws -> MediaInfoResult) -> Promise<MediaInfoResult> {
        performOperation {
            do { return try await body() }
            catch { return self.makeErrorResult(operation: operation, type: type, inputUri: input, error: error) }
        }
    }

    private func outputURL(extension ext: String) throws -> URL {
        guard let job = MediaJob.current else { throw validationError("Missing media operation context.") }
        return try job.output(ext)
    }

    private func getLocalFilePath(_ source: String) async throws -> String {
        guard let job = MediaJob.current else { throw validationError("Missing media operation context.") }
        return try await job.resolve(source)
    }

    private func downsampleImage(path: String, maxLongEdge: Double) throws -> UIImage {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: max(1, maxLongEdge)
              ] as CFDictionary) else { throw unsupportedMediaError("Unable to decode image.") }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }

    private func outputResult(_ operation: OperationType, input: String?, output: URL) async throws -> MediaInfoResult {
        let info = try await detectVideoInfo(atPath: output.path)
        return makeResult(ok: true, operation: operation, type: .video, inputUri: input, outputUri: output.path,
            media: buildMediaInfo(durationMs: info.durationMs, width: info.width, height: info.height, fps: info.fps,
                format: output.pathExtension, sizeBytes: fileSizeBytes(atPath: output.path), audioTracks: Double(info.audioTracks), videoTracks: 1))
    }

    public func convertImageToVideo(image: String, duration: Double) -> Promise<MediaInfoResult> {
        run(.convertimagetovideo, input: image) {
            guard duration.isFinite, duration > 0, duration <= 86_400 else { throw self.validationError("Duration must be in (0, 86400] seconds.") }
            let path = try await self.getLocalFilePath(image)
            let source = try self.downsampleImage(path: path, maxLongEdge: 1920)
            let size = self.evenSize(source.size)
            let width = Int(size.width), height = Int(size.height)
            let output = try self.outputURL(extension: "mp4")
            let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
            writer.shouldOptimizeForNetworkUse = true
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
                AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: max(2_000_000, width * height * 4)]
            ])
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ])
            guard writer.canAdd(input) else { throw self.unsupportedMediaError("Cannot add image video writer input.") }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? self.validationError("Cannot start video writer.") }
            writer.startSession(atSourceTime: .zero)
            guard let pool = adaptor.pixelBufferPool,
                  let buffer = self.createPixelBuffer(from: source, pixelBufferPool: pool, width: width, height: height) else {
                writer.cancelWriting()
                throw self.validationError("Cannot create image video frame.")
            }
            try await self.writeFrames(adaptor: adaptor, writerInput: input, pixelBuffer: buffer,
                frameDuration: CMTime(value: 1, timescale: 30), totalFrames: max(1, Int(ceil(duration * 30))), writer: writer)
            return try await self.outputResult(.convertimagetovideo, input: image, output: output)
        }
    }

    public func mergeVideos(videos: [String]) -> Promise<MediaInfoResult> {
        run(.mergevideos, input: videos.first) {
            guard !videos.isEmpty else { throw self.validationError("At least one video is required.") }
            var paths: [String] = []
            for uri in videos { paths.append(try await self.getLocalFilePath(uri)) }
            let firstInfo = try await self.detectVideoInfo(atPath: paths[0])
            let renderSize = self.evenSize(self.scaledSize(width: firstInfo.width, height: firstInfo.height, maxLongEdge: 1920))
            let composition = AVMutableComposition()
            guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw self.validationError("Cannot create composition track.")
            }
            var audio: AVMutableCompositionTrack?
            var cursor = CMTime.zero
            var instructions: [AVMutableVideoCompositionInstruction] = []
            for path in paths {
                let asset = AVURLAsset(url: URL(fileURLWithPath: path))
                let info = try await self.detectVideoInfo(atPath: path)
                let duration = CMTime(seconds: info.durationMs / 1000, preferredTimescale: 600_000)
                guard let sourceVideo = try await asset.loadTracks(withMediaType: .video).first else { throw self.unsupportedMediaError("No video track.") }
                let range = CMTimeRange(start: .zero, duration: duration)
                try video.insertTimeRange(range, of: sourceVideo, at: cursor)
                let transform = try await sourceVideo.load(.preferredTransform)
                let size = try await sourceVideo.load(.naturalSize)
                let rect = CGRect(origin: .zero, size: size).applying(transform)
                let scale = min(renderSize.width / abs(rect.width), renderSize.height / abs(rect.height))
                let fitted = transform.concatenating(CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
                    .concatenating(CGAffineTransform(scaleX: scale, y: scale))
                    .concatenating(CGAffineTransform(translationX: (renderSize.width - abs(rect.width) * scale) / 2,
                        y: (renderSize.height - abs(rect.height) * scale) / 2))
                let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
                layer.setTransform(fitted, at: cursor)
                let instruction = AVMutableVideoCompositionInstruction()
                instruction.timeRange = CMTimeRange(start: cursor, duration: duration)
                instruction.backgroundColor = UIColor.black.cgColor
                instruction.layerInstructions = [layer]
                instructions.append(instruction)
                if let sourceAudio = try await asset.loadTracks(withMediaType: .audio).first {
                    if audio == nil { audio = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) }
                    let audioRange = CMTimeRangeGetIntersection(try await sourceAudio.load(.timeRange), otherRange: range)
                    if audioRange.duration > .zero {
                        try audio?.insertTimeRange(audioRange, of: sourceAudio, at: CMTimeAdd(cursor, audioRange.start))
                    }
                }
                cursor = CMTimeAdd(cursor, duration)
            }
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = renderSize
            videoComposition.frameDuration = CMTime(seconds: 1 / min(120, max(1, firstInfo.fps)), preferredTimescale: 600_000)
            videoComposition.instructions = instructions
            let output = try self.outputURL(extension: "mp4")
            try await self.export(asset: composition, output: output, videoComposition: videoComposition)
            return try await self.outputResult(.mergevideos, input: videos.first, output: output)
        }
    }

    private func validateSegment(_ segment: VideoSegment, durationMs: Double) throws {
        guard segment.startMs.isFinite, segment.endMs.isFinite, segment.startMs >= 0,
              segment.endMs > segment.startMs, segment.endMs <= durationMs else {
            throw validationError("Segments must satisfy 0 <= startMs < endMs <= video duration.")
        }
    }

    private func segmentRange(_ segment: VideoSegment) -> CMTimeRange {
        CMTimeRange(start: CMTime(seconds: segment.startMs / 1000, preferredTimescale: 1_000_000),
            end: CMTime(seconds: segment.endMs / 1000, preferredTimescale: 1_000_000))
    }

    public func splitVideo(video: String, segments: [VideoSegment]) -> Promise<MediaInfoResult> {
        run(.splitvideo, input: video) {
            guard !segments.isEmpty else { throw self.validationError("At least one segment is required.") }
            let path = try await self.getLocalFilePath(video)
            let info = try await self.detectVideoInfo(atPath: path)
            for segment in segments { try self.validateSegment(segment, durationMs: info.durationMs) }
            let asset = AVURLAsset(url: URL(fileURLWithPath: path))
            var outputs: [String] = []
            for segment in segments {
                let output = try self.outputURL(extension: "mp4")
                try await self.export(asset: asset, output: output, range: self.segmentRange(segment))
                outputs.append(output.path)
            }
            return self.makeResult(ok: true, operation: .splitvideo, type: .video, inputUri: video, segments: outputs)
        }
    }

    public func trimVideo(video: String, segment: VideoSegment) -> Promise<MediaInfoResult> {
        run(.trimvideo, input: video) {
            let path = try await self.getLocalFilePath(video)
            let info = try await self.detectVideoInfo(atPath: path)
            try self.validateSegment(segment, durationMs: info.durationMs)
            let output = try self.outputURL(extension: "mp4")
            try await self.export(asset: AVURLAsset(url: URL(fileURLWithPath: path)), output: output, range: self.segmentRange(segment))
            return try await self.outputResult(.trimvideo, input: video, output: output)
        }
    }

    public func removeAudio(video: String) -> Promise<MediaInfoResult> {
        run(.removeaudio, input: video) {
            let path = try await self.getLocalFilePath(video)
            let asset = AVURLAsset(url: URL(fileURLWithPath: path))
            let composition = AVMutableComposition()
            guard let source = try await asset.loadTracks(withMediaType: .video).first,
                  let target = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw self.unsupportedMediaError("No video track found.")
            }
            try target.insertTimeRange(try await source.load(.timeRange), of: source, at: .zero)
            target.preferredTransform = try await source.load(.preferredTransform)
            let output = try self.outputURL(extension: "mp4")
            try await self.export(asset: composition, output: output, passthrough: true)
            return try await self.outputResult(.removeaudio, input: video, output: output)
        }
    }

    public func extractThumbnail(video: String, options: ThumbnailOptions?) -> Promise<MediaInfoResult> {
        run(.extractthumbnail, input: video, type: .image) {
            let timeMs = options?.timeMs ?? 0
            let edge = options?.maxLongEdge ?? 1280
            let quality = options?.quality ?? 0.9
            guard timeMs.isFinite, timeMs >= 0, edge.isFinite, edge >= 1, edge <= 8192,
                  quality.isFinite, (0...1).contains(quality) else { throw self.validationError("Invalid thumbnail time, dimensions or quality.") }
            let path = try await self.getLocalFilePath(video)
            let info = try await self.detectVideoInfo(atPath: path)
            guard timeMs < info.durationMs else { throw self.validationError("timeMs must be less than video duration.") }
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: URL(fileURLWithPath: path)))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: edge, height: edge)
            let time = CMTime(seconds: timeMs / 1000, preferredTimescale: 1_000_000)
            let image: CGImage = try await withCheckedThrowingContinuation { continuation in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, image, _, result, error in
                    if result == .succeeded, let image { continuation.resume(returning: image) }
                    else { continuation.resume(throwing: error ?? self.validationError("Cannot extract thumbnail.")) }
                }
            }
            guard let data = UIImage(cgImage: image).jpegData(compressionQuality: quality) else { throw self.validationError("Cannot encode thumbnail.") }
            let output = try self.outputURL(extension: "jpg")
            try data.write(to: output, options: .atomic)
            return self.makeResult(ok: true, operation: .extractthumbnail, type: .image, inputUri: video, outputUri: output.path,
                media: self.buildMediaInfo(width: Double(image.width), height: Double(image.height), format: "jpg",
                    sizeBytes: Double(data.count), audioTracks: 0, videoTracks: 0))
        }
    }

    public func deleteGeneratedFile(uri: String) -> Promise<Bool> {
        let promise = Promise<Bool>()
        Task.detached {
            await MediaWorkGate.shared.acquire()
            do { let deleted = try MediaJob.deleteOutput(uri); await MediaWorkGate.shared.release(); promise.resolve(withResult: deleted) }
            catch { await MediaWorkGate.shared.release(); promise.reject(withError: error) }
        }
        return promise
    }

    public func watermarkVideo(video: String, watermark: String, options: WatermarkOptions?) -> Promise<MediaInfoResult> {
        run(.watermarkvideo, input: video) {
            guard !watermark.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, watermark.utf16.count <= 4096 else {
                throw self.validationError("Watermark must contain 1 to 4096 characters.")
            }
            let path = try await self.getLocalFilePath(video)
            let info = try await self.detectVideoInfo(atPath: path)
            let size = self.evenSize(CGSize(width: info.width, height: info.height))
            let fontSize = options?.fontSize ?? min(info.width, info.height) * 0.05
            let margin = options?.margin ?? min(info.width, info.height) * 0.025
            let opacity = options?.opacity ?? 1
            guard fontSize.isFinite, (1...512).contains(fontSize), margin.isFinite, margin >= 0,
                  margin * 2 < min(Double(size.width), Double(size.height)), opacity.isFinite, (0...1).contains(opacity) else {
                throw self.validationError("Invalid watermark fontSize, margin or opacity.")
            }
            let color = try self.watermarkColor(options?.color ?? "#FFFFFFFF", opacity: opacity)
            let image = try self.watermarkImage(watermark, fontSize: fontSize, color: color,
                maxSize: CGSize(width: size.width - margin * 2, height: size.height - margin * 2))
            let x: CGFloat, y: CGFloat
            switch options?.position ?? .bottomRight {
            case .topLeft: x = margin; y = size.height - margin - image.extent.height
            case .topRight: x = size.width - margin - image.extent.width; y = size.height - margin - image.extent.height
            case .bottomLeft: x = margin; y = margin
            case .bottomRight: x = size.width - margin - image.extent.width; y = margin
            case .center: x = (size.width - image.extent.width) / 2; y = (size.height - image.extent.height) / 2
            }
            let overlay = image.transformed(by: CGAffineTransform(translationX: x, y: y))
            let asset = AVURLAsset(url: URL(fileURLWithPath: path))
            let context = CIContext(options: [.cacheIntermediates: false])
            let filters = AVMutableVideoComposition(asset: asset) { request in
                let frame = request.sourceImage
                request.finish(with: overlay.composited(over: frame).cropped(to: frame.extent), context: context)
            }
            filters.renderSize = size
            filters.frameDuration = CMTime(seconds: 1 / min(120, max(1, info.fps)), preferredTimescale: 600_000)
            let plan = VideoCompressionPlan(maxLongEdge: max(info.width, info.height), maxFps: min(120, info.fps),
                videoBitrateKbps: min(50_000, max(2000, info.width * info.height * info.fps * 0.12 / 1000)),
                audioBitrateKbps: 192, keepAudio: true)
            let outputPath = try await self.transcodeVideo(inputPath: path, targetSize: size, plan: plan,
                preserveMetadata: false, compositionOverride: filters)
            return try await self.outputResult(.watermarkvideo, input: video, output: URL(fileURLWithPath: outputPath))
        }
    }

    private func watermarkColor(_ value: String, opacity: Double) throws -> UIColor {
        guard value.range(of: "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$", options: .regularExpression) != nil,
              let hex = UInt64(value.dropFirst(), radix: 16) else { throw validationError("color must be #RRGGBB or #RRGGBBAA.") }
        let rgb = value.count == 9 ? hex >> 8 : hex
        let alpha = value.count == 9 ? Double(hex & 255) / 255 : 1
        return UIColor(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255,
            blue: Double(rgb & 255) / 255, alpha: alpha * opacity)
    }

    private func watermarkImage(_ text: String, fontSize: Double, color: UIColor, maxSize: CGSize) throws -> CIImage {
        var fontSize = CGFloat(fontSize)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byWordWrapping
        var attributes: [NSAttributedString.Key: Any]
        var rect: CGRect
        repeat {
            attributes = [.font: UIFont.systemFont(ofSize: fontSize, weight: .bold), .foregroundColor: color, .paragraphStyle: paragraph]
            rect = (text as NSString).boundingRect(with: CGSize(width: maxSize.width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil).integral
            if rect.height <= maxSize.height || fontSize <= 1 { break }
            fontSize = max(1, fontSize * 0.85)
        } while true
        guard rect.height <= maxSize.height else { throw validationError("Watermark cannot fit inside video.") }
        let size = CGSize(width: max(1, min(maxSize.width, rect.width)), height: max(1, rect.height))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            (text as NSString).draw(with: CGRect(origin: .zero, size: size), options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: attributes, context: nil)
        }
        guard let cgImage = rendered.cgImage else { throw validationError("Cannot rasterize watermark.") }
        return CIImage(cgImage: cgImage)
    }

    private func export(asset: AVAsset, output: URL, videoComposition: AVVideoComposition? = nil, range: CMTimeRange? = nil, passthrough: Bool = false) async throws {
        guard let session = AVAssetExportSession(asset: asset, presetName: passthrough ? AVAssetExportPresetPassthrough : AVAssetExportPresetHighestQuality) else {
            throw unsupportedMediaError("Cannot create video exporter.")
        }
        session.outputURL = output
        session.outputFileType = .mp4
        session.videoComposition = videoComposition
        session.shouldOptimizeForNetworkUse = true
        if let range { session.timeRange = range }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            session.exportAsynchronously {
                if session.status == .completed { continuation.resume() }
                else { continuation.resume(throwing: session.error ?? self.validationError("Video export failed or was cancelled.")) }
            }
        }
    }
    private func writeFrames(
        adaptor: AVAssetWriterInputPixelBufferAdaptor,
        writerInput: AVAssetWriterInput,
        pixelBuffer: CVPixelBuffer,
        frameDuration: CMTime,
        totalFrames: Int,
        writer: AVAssetWriter
    ) async throws {
        let queue = DispatchQueue(label: "NitroMediaKit.ImageToVideo.Writer")
        defer { withExtendedLifetime((adaptor, writerInput, writer, pixelBuffer)) {} }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var frameCount = 0
            var didFinish = false
            let timeout = DispatchTime.now().uptimeNanoseconds + 30 * 60 * 1_000_000_000
            let timer = DispatchSource.makeTimerSource(queue: queue)
            func stopTimer() { timer.setEventHandler {}; timer.cancel() }
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler {
                guard !didFinish else { return }
                if writer.status == .failed || writer.status == .cancelled || DispatchTime.now().uptimeNanoseconds >= timeout {
                    didFinish = true
                    stopTimer()
                    let error = writer.error ?? self.validationError("Image video export timed out or was cancelled.")
                    writer.cancelWriting()
                    continuation.resume(throwing: error)
                }
            }
            timer.resume()
            writerInput.requestMediaDataWhenReady(on: queue) { [weak writer, weak writerInput, weak adaptor] in
                guard let writer, let writerInput, let adaptor else { return }
                guard !didFinish else { return }
                while writerInput.isReadyForMoreMediaData && frameCount < totalFrames {
                    let pts = CMTimeMultiply(frameDuration, multiplier: Int32(frameCount))
                    guard adaptor.append(pixelBuffer, withPresentationTime: pts) else {
                        didFinish = true
                        stopTimer()
                        let error = writer.error ?? self.validationError("Cannot append image video frame.")
                        writer.cancelWriting()
                        continuation.resume(throwing: error)
                        return
                    }
                    frameCount += 1
                }
                if frameCount >= totalFrames {
                    didFinish = true
                    stopTimer()
                    writerInput.markAsFinished()
                    writer.endSession(atSourceTime: CMTimeMultiply(frameDuration, multiplier: Int32(totalFrames)))
                    writer.finishWriting {
                        if writer.status == .completed { continuation.resume() }
                        else { continuation.resume(throwing: writer.error ?? self.validationError("Cannot finish image video export.")) }
                    }
                }
            }
        }
    }

    // Helper function to create a pixel buffer from a UIImage
    private func createPixelBuffer(from image: UIImage, pixelBufferPool: CVPixelBufferPool, width: Int, height: Int) -> CVPixelBuffer? {
        guard let cgImage = image.cgImage else { return nil }
        var pixelBufferOut: CVPixelBuffer?

        let status = CVPixelBufferPoolCreatePixelBuffer(nil, pixelBufferPool, &pixelBufferOut)
        guard status == kCVReturnSuccess, let pixelBuffer = pixelBufferOut else {
            return nil
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])

        // Create a context
        let rgbColorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pixelBuffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: rgbColorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
        ) else {
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
