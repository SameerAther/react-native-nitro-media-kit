import Foundation
@preconcurrency import AVFoundation
import UIKit
import NitroModules // Import Promise from NitroModules core

class NitroMediaKit: HybridNitroMediaKitSpec {
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

    public func getMediaInfo(inputUri: String) -> Promise<MediaInfoResult> {
        return Promise.async {
            do {
                let localPath = try await self.getLocalFilePath(inputUri)
                let url = URL(fileURLWithPath: localPath)
                let ext = url.pathExtension.lowercased()
                let sizeBytes = self.fileSizeBytes(atPath: localPath)

                if let image = UIImage(contentsOfFile: localPath) {
                    let media = self.buildMediaInfo(
                        width: Double(image.size.width),
                        height: Double(image.size.height),
                        format: ext.isEmpty ? nil : ext,
                        sizeBytes: sizeBytes,
                        audioTracks: 0,
                        videoTracks: 0
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

                let primaryVideoTrack = videoTracks.first
                let naturalSize = try await primaryVideoTrack?.load(.naturalSize)
                let preferredTransform = try await primaryVideoTrack?.load(.preferredTransform)

                var width: Int?
                var height: Int?
                if let naturalSize = naturalSize, let preferredTransform = preferredTransform {
                    let transformedRect = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
                    width = Int(round(abs(transformedRect.size.width)))
                    height = Int(round(abs(transformedRect.size.height)))
                }

                let fps = primaryVideoTrack?.nominalFrameRate
                let media = self.buildMediaInfo(
                    durationMs: duration.seconds.isFinite ? duration.seconds * 1000.0 : nil,
                    width: width.map { Double($0) },
                    height: height.map { Double($0) },
                    fps: fps.map { Double($0) },
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

    public func convertImageToVideo(image: String, duration: Double) -> Promise<MediaInfoResult> {
        return Promise.async {
            var result: MediaInfoResult
            // This runs on a separate thread and can use `await` syntax
            do {
                // Get the local file path, downloading if necessary
                let localImagePath = try await self.getLocalFilePath(image)

            // Load the image
            guard let uiImage = UIImage(contentsOfFile: localImagePath) else {
                throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot load image at path: \(localImagePath)"])
            }

            // Get image dimensions
            let width = Int(uiImage.size.width)
            let height = Int(uiImage.size.height)

            // Ensure dimensions are even numbers (some codecs require this)
            let adjustedWidth = width - (width % 2)
            let adjustedHeight = height - (height % 2)

            // Set up the video settings
            let videoSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: adjustedWidth,
                AVVideoHeightKey: adjustedHeight
            ]

            // Create a temporary file path for the video
            let tempDir = NSTemporaryDirectory()
            let outputURL = URL(fileURLWithPath: tempDir).appendingPathComponent("video_\(Int(Date().timeIntervalSince1970)).mp4")

            // Set up the asset writer
            let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)

            let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
            writerInput.expectsMediaDataInRealTime = false

            // Set up the pixel buffer adaptor
            let sourcePixelBufferAttributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                kCVPixelBufferWidthKey as String: adjustedWidth,
                kCVPixelBufferHeightKey as String: adjustedHeight,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ]
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: writerInput, sourcePixelBufferAttributes: sourcePixelBufferAttributes)

            // Add the input to the writer
            guard writer.canAdd(writerInput) else {
                throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot add input to writer"])
            }
            writer.add(writerInput)

            // Start writing
            writer.startWriting()
            writer.startSession(atSourceTime: .zero)

            // Calculate the frame duration and total frames
            let frameDuration = CMTime(value: 1, timescale: 30) // 30 fps
            let totalFrames = Int(duration * 30)

            // Create a pixel buffer from the image
            guard let pixelBufferPool = adaptor.pixelBufferPool else {
                throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Pixel buffer pool not available"])
            }

            // Prepare the pixel buffer
            guard let pixelBuffer = self.createPixelBuffer(from: uiImage, pixelBufferPool: pixelBufferPool, width: adjustedWidth, height: adjustedHeight) else {
                throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create pixel buffer from image"])
            }

            // Write frames asynchronously
            try await self.writeFrames(
                adaptor: adaptor,
                writerInput: writerInput,
                pixelBuffer: pixelBuffer,
                frameDuration: frameDuration,
                totalFrames: totalFrames,
                writer: writer
            )

                let outputPath = outputURL.path
                let media = self.buildMediaInfo(
                    durationMs: duration * 1000.0,
                    width: Double(adjustedWidth),
                    height: Double(adjustedHeight),
                    fps: 30,
                    format: "mp4",
                    sizeBytes: self.fileSizeBytes(atPath: outputPath),
                    audioTracks: 0,
                    videoTracks: 1
                )
                result = self.makeResult(
                    ok: true,
                    operation: .convertimagetovideo,
                    type: .video,
                    inputUri: image,
                    outputUri: outputPath,
                    media: media
                )
            } catch {
                result = self.makeErrorResult(
                    operation: .convertimagetovideo,
                    type: .video,
                    inputUri: image,
                    error: error
                )
            }
            return result
        }
    }

    public func mergeVideos(videos: [String]) -> Promise<MediaInfoResult> {
    return Promise.async {
        var result: MediaInfoResult
        var localVideoPaths = [String]()
        do {
            for videoPathOrUrl in videos {
                let localPath = try await self.getLocalFilePath(videoPathOrUrl)
                localVideoPaths.append(localPath)
                print("Local video path: \(localPath)")
            }
        
        // Step 2: Create an AVMutableComposition
        let composition = AVMutableComposition()
        var currentTime = CMTime.zero
        
        // Video and audio tracks in the composition
        guard let compositionVideoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create video track"])
        }
        
        var compositionAudioTrack: AVMutableCompositionTrack? = nil
        
        // Step 3: Loop through each video and insert it into the composition
        for localVideoPath in localVideoPaths {
            let videoURL = URL(fileURLWithPath: localVideoPath)
            let asset = AVAsset(url: videoURL)
            
            if !asset.isReadable {
                throw NSError(
                    domain: "HybridMediaKit",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Asset is not readable at \(videoURL.path)"]
                )
            }
            
            do {
                // Load asset properties
                _ = try await asset.load(.tracks)
                let duration = try await asset.load(.duration)
                
                if duration == .zero {
                    throw NSError(
                        domain: "HybridMediaKit",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Asset duration is zero at \(videoURL.path)"]
                    )
                }
                
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                
                // Insert video track
                if let assetVideoTrack = asset.tracks(withMediaType: .video).first {
                    try compositionVideoTrack.insertTimeRange(
                        timeRange,
                        of: assetVideoTrack,
                        at: currentTime
                    )
                } else {
                    throw NSError(
                        domain: "HybridMediaKit",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "No video track found in asset at \(videoURL.path)"]
                    )
                }
                
                // Insert audio track if available
                if let assetAudioTrack = asset.tracks(withMediaType: .audio).first {
                    if compositionAudioTrack == nil {
                        compositionAudioTrack = composition.addMutableTrack(
                            withMediaType: .audio,
                            preferredTrackID: kCMPersistentTrackID_Invalid
                        )
                    }
                    try compositionAudioTrack?.insertTimeRange(
                        timeRange,
                        of: assetAudioTrack,
                        at: currentTime
                    )
                }
                
                // Update current time
                currentTime = CMTimeAdd(currentTime, duration)
                
                print("Successfully added asset at \(videoURL.path)")
                
            } catch {
                print("Error processing asset at \(videoURL.path): \(error.localizedDescription)")
                throw error
            }
        }
        
        // Step 4: Export the merged video
        // Save to Documents directory
        let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let outputURL = documentsDirectory.appendingPathComponent("merged_video_\(Int(Date().timeIntervalSince1970)).mp4")
        
        // Remove existing file if necessary
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try FileManager.default.removeItem(at: outputURL)
        }
        
        // Set up the exporter
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create AVAssetExportSession"])
        }
        exporter.outputURL = outputURL
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        
        // Export the video asynchronously
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed:
                    print("Export completed successfully")
                    continuation.resume()
                case .failed:
                    if let error = exporter.error {
                        print("Export failed with error: \(error.localizedDescription)")
                        continuation.resume(throwing: error)
                    } else {
                        let error = NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unknown error during export"])
                        continuation.resume(throwing: error)
                    }
                case .cancelled:
                    print("Export cancelled")
                    let error = NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Export cancelled"])
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
        }
        
        // Log and return the output file path
        print("Merged video saved at path: \(outputURL.path)")
        let outputPath = outputURL.path
        let media = self.buildMediaInfo(
            durationMs: currentTime.seconds.isFinite ? currentTime.seconds * 1000.0 : nil,
            format: "mp4",
            sizeBytes: self.fileSizeBytes(atPath: outputPath),
            audioTracks: compositionAudioTrack == nil ? 0 : 1,
            videoTracks: 1
        )
        result = self.makeResult(
            ok: true,
            operation: .mergevideos,
            type: .video,
            outputUri: outputPath,
            media: media
        )
        } catch {
            result = self.makeErrorResult(
                operation: .mergevideos,
                type: .video,
                error: error
            )
        }
        return result
    }
}
  
  private var isSimulator: Bool {
#if targetEnvironment(simulator)
    return true
#else
    return false
#endif
  }

  public func watermarkVideo(video: String, watermark: String, position: String) -> Promise<MediaInfoResult> {
    return Promise.async {
      var result: MediaInfoResult
      do {
        let localVideoPath = try await self.getLocalFilePath(video, defaultExtension: "mp4")
        
        // Simulator + CoreAnimationTool watermark is notoriously crashy (xpc_api_misuse + -12900).
        // Bypass on Simulator so development doesn't get nuked.
        if self.isSimulator {
          let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
          let out = docs.appendingPathComponent("watermarked_SIM_\(Int(Date().timeIntervalSince1970)).mp4")
          if FileManager.default.fileExists(atPath: out.path) { try FileManager.default.removeItem(at: out) }
          try FileManager.default.copyItem(at: URL(fileURLWithPath: localVideoPath), to: out)
          let outputPath = out.path
          let media = self.buildMediaInfo(
            format: "mp4",
            sizeBytes: self.fileSizeBytes(atPath: outputPath)
          )
          result = self.makeResult(
            ok: true,
            operation: .watermarkvideo,
            type: .video,
            inputUri: video,
            outputUri: outputPath,
            media: media
          )
          return result
        }
      
      // ---- Your existing CoreAnimationTool export code (device only) ----
      let asset = AVAsset(url: URL(fileURLWithPath: localVideoPath))
      let duration = try await asset.load(.duration)
      guard duration > .zero else {
        throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Video duration is zero"])
      }
      
      let audioTrackCount = try await asset.loadTracks(withMediaType: .audio).count
      guard let assetVideoTrack = try await asset.loadTracks(withMediaType: .video).first else {
        throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
      }
      
      let composition = AVMutableComposition()
      guard let compositionVideoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
        throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create composition video track"])
      }
      
      let timeRange = CMTimeRange(start: .zero, duration: duration)
      try compositionVideoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: .zero)
      
      let preferredTransform = try await assetVideoTrack.load(.preferredTransform)
      let naturalSize = try await assetVideoTrack.load(.naturalSize)
      
      // Compute the displayed rect after rotation/transform
      let transformedRect = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
      
      func evenInt(_ v: CGFloat) -> Int {
        let r = max(2, Int(round(abs(v))))
        return r - (r % 2)
      }
      
      let renderW = evenInt(transformedRect.size.width)
      let renderH = evenInt(transformedRect.size.height)
      let renderSize = CGSize(width: renderW, height: renderH)
      
      let videoComposition = AVMutableVideoComposition()
      videoComposition.renderSize = renderSize
      videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
      
      // Instruction
      let instruction = AVMutableVideoCompositionInstruction()
      instruction.timeRange = timeRange
      
      let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideoTrack)
      
      // Apply the preferredTransform AND fix negative-origin translations so it lands inside renderSize
      var fixedTransform = preferredTransform
      
      // When rotated, the transformed rect often has negative origin.
      // Translate it back into the visible render box.
      let tx = -transformedRect.origin.x
      let ty = -transformedRect.origin.y
      fixedTransform = fixedTransform.concatenating(CGAffineTransform(translationX: tx, y: ty))
      
      layerInstruction.setTransform(fixedTransform, at: .zero)
      
      instruction.layerInstructions = [layerInstruction]
      videoComposition.instructions = [instruction]
      
      // ---- CoreAnimationTool layers (do NOT flip geometry) ----
      let (parentLayer, videoLayer) = await MainActor.run { () -> (CALayer, CALayer) in
        let parent = CALayer()
        parent.frame = CGRect(origin: .zero, size: renderSize)
        parent.isGeometryFlipped = false   // ✅ keep default coordinate system
        
        let videoL = CALayer()
        videoL.frame = parent.bounds
        parent.addSublayer(videoL)
        
        // Watermark layer
        let overlay = CATextLayer()
        overlay.contentsScale = UIScreen.main.scale
        overlay.alignmentMode = .left
        overlay.foregroundColor = UIColor.white.cgColor
        
        let fontSize: CGFloat = 64
        overlay.fontSize = fontSize
        overlay.font = CTFontCreateWithName("Helvetica-Bold" as CFString, fontSize, nil)
        overlay.string = watermark
        
        let uiFont = UIFont.systemFont(ofSize: fontSize, weight: .bold)
        let textSize = (watermark as NSString).size(withAttributes: [.font: uiFont])
        
        let padding: CGFloat = 50
        let x: CGFloat
        let y: CGFloat
        
        // In default CoreAnimation coords: origin is bottom-left for our purposes.
        switch position.lowercased() {
          case "top-left":
            x = padding
            y = renderSize.height - textSize.height - padding
          case "top-right":
            x = renderSize.width - textSize.width - padding
            y = renderSize.height - textSize.height - padding
          case "center":
            x = (renderSize.width - textSize.width) / 2
            y = (renderSize.height - textSize.height) / 2
          case "bottom-right":
            x = renderSize.width - textSize.width - padding
            y = padding
          case "bottom-left":
            x = padding
            y = padding
          default:
            x = padding
            y = padding
        }
        
        let clampedX = max(0, min(x, renderSize.width - textSize.width))
        let clampedY = max(0, min(y, renderSize.height - textSize.height))
        
        overlay.frame = CGRect(x: clampedX, y: clampedY, width: textSize.width, height: textSize.height)
        
        // Optional: make it unmistakable during debugging
        // overlay.backgroundColor = UIColor.black.withAlphaComponent(0.35).cgColor
        
        parent.addSublayer(overlay)
        
        return (parent, videoL)
      }
      
      videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
        postProcessingAsVideoLayer: videoLayer,
        in: parentLayer
      )
      
      let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
      let outputURL = docs.appendingPathComponent("watermarked_video_\(Int(Date().timeIntervalSince1970)).mp4")
      if FileManager.default.fileExists(atPath: outputURL.path) { try FileManager.default.removeItem(at: outputURL) }
      
      guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
        throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create AVAssetExportSession"])
      }
      
      exporter.videoComposition = videoComposition
      exporter.outputURL = outputURL
      exporter.outputFileType = .mp4
      exporter.shouldOptimizeForNetworkUse = true
      
      try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
        exporter.exportAsynchronously {
          switch exporter.status {
            case .completed:
              c.resume()
            case .failed:
              c.resume(throwing: exporter.error ?? NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unknown export error"]))
            case .cancelled:
              c.resume(throwing: NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "Export cancelled"]))
            default:
              break
          }
        }
      }
      
      let outputPath = outputURL.path
      let media = self.buildMediaInfo(
        durationMs: duration.seconds.isFinite ? duration.seconds * 1000.0 : nil,
        width: Double(renderW),
        height: Double(renderH),
        fps: 30,
        format: "mp4",
        sizeBytes: self.fileSizeBytes(atPath: outputPath),
        audioTracks: Double(audioTrackCount),
        videoTracks: 1
      )
      result = self.makeResult(
        ok: true,
        operation: .watermarkvideo,
        type: .video,
        inputUri: video,
        outputUri: outputPath,
        media: media
      )
      } catch {
        result = self.makeErrorResult(
          operation: .watermarkvideo,
          type: .video,
          inputUri: video,
          error: error
        )
      }
      return result
    }
  }



    // Helper function to determine if the path is a remote URL and download if necessary
  private func getLocalFilePath(_ pathOrUrl: String, defaultExtension: String? = nil) async throws -> String {
    if let url = URL(string: pathOrUrl), url.scheme == "http" || url.scheme == "https" {
      let (data, response) = try await URLSession.shared.data(from: url)
      
      if let http = response as? HTTPURLResponse, http.statusCode != 200 {
        throw NSError(domain: "HybridMediaKit", code: http.statusCode,
                      userInfo: [NSLocalizedDescriptionKey: "Failed to download file: HTTP \(http.statusCode)"])
      }
      
      let mime = (response as? HTTPURLResponse)?
        .value(forHTTPHeaderField: "Content-Type")?
        .lowercased() ?? ""
      
      func extFromMime(_ mime: String) -> String? {
        if mime.contains("video/") { return "mp4" }
        if mime.contains("image/png") { return "png" }
        if mime.contains("image/jpeg") { return "jpg" }
        if mime.contains("image/jpg") { return "jpg" }
        if mime.contains("image/") { return "png" }
        return nil
      }
      
      let ext: String = {
        if !url.pathExtension.isEmpty { return url.pathExtension }
        if let e = extFromMime(mime) { return e }
        if let e = defaultExtension { return e }
        return "bin"
      }()
      
      let tempFileURL = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent(UUID().uuidString)
        .appendingPathExtension(ext)
      
      try data.write(to: tempFileURL)
      return tempFileURL.path
    }
    
    if !FileManager.default.fileExists(atPath: pathOrUrl) {
      throw NSError(domain: "HybridMediaKit", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "File does not exist at path: \(pathOrUrl)"])
    }
    return pathOrUrl
  }


    // Helper function to write frames asynchronously
  private func writeFrames(
    adaptor: AVAssetWriterInputPixelBufferAdaptor,
    writerInput: AVAssetWriterInput,
    pixelBuffer: CVPixelBuffer,
    frameDuration: CMTime,
    totalFrames: Int,
    writer: AVAssetWriter
  ) async throws {
    let queue = DispatchQueue(label: "NitroMediaKit.ImageToVideo.Writer")
    
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      var frameCount = 0
      var didFinish = false
      
      writerInput.requestMediaDataWhenReady(on: queue) {
        if didFinish { return }
        
        while writerInput.isReadyForMoreMediaData && frameCount < totalFrames {
          let pts = CMTimeMultiply(frameDuration, multiplier: Int32(frameCount))
          guard adaptor.append(pixelBuffer, withPresentationTime: pts) else {
            didFinish = true
            writerInput.markAsFinished()
            writer.cancelWriting()
            continuation.resume(throwing: writer.error ?? NSError(domain: "HybridMediaKit", code: -1,
                                                                  userInfo: [NSLocalizedDescriptionKey: "Failed to append pixel buffer"]))
            return
          }
          frameCount += 1
        }
        
        if frameCount >= totalFrames && !didFinish {
          didFinish = true
          writerInput.markAsFinished()
          writer.finishWriting {
            if writer.status == .completed {
              continuation.resume()
            } else {
              continuation.resume(throwing: writer.error ?? NSError(domain: "HybridMediaKit", code: -1,
                                                                    userInfo: [NSLocalizedDescriptionKey: "Unknown error during finishWriting"]))
            }
          }
        }
      }
    }
  }

    // Helper function to create a pixel buffer from a UIImage
    private func createPixelBuffer(from image: UIImage, pixelBufferPool: CVPixelBufferPool, width: Int, height: Int) -> CVPixelBuffer? {
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
        context.draw(image.cgImage!, in: CGRect(x: 0, y: 0, width: width, height: height))

        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        return pixelBuffer
    }
}
