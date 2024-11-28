//
//  HybridMediaKit.swift
//  Pods
//
//  Created by Sameer Ather on 20/11/2024.
//

import Foundation
import AVFoundation
import UIKit
import NitroModules // Import Promise from NitroModules core

class HybridMediaKit: HybridMediaKitSpec {
    public var hybridContext = margelo.nitro.HybridContext()
    public var memorySize: Int {
        return getSizeOf(self)
    }

    public func convertImageToVideo(image: String, duration: Double) -> Promise<String> {
        return Promise.async {
            // This runs on a separate thread and can use `await` syntax
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

            // Return the output file path
            return outputURL.path
        }
    }

    public func mergeVideos(videos: [String]) -> Promise<String> {
    return Promise.async {
        var localVideoPaths = [String]()
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
                let tracks = try await asset.load(.tracks)
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
        return outputURL.path
    }
}


    // Helper function to determine if the path is a remote URL and download if necessary
    private func getLocalFilePath(_ pathOrUrl: String) async throws -> String {
    if let url = URL(string: pathOrUrl), url.scheme == "http" || url.scheme == "https" {
        // Download the file asynchronously
        let (data, response) = try await URLSession.shared.data(from: url)
        
        // Check for HTTP errors
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
            throw NSError(domain: "HybridMediaKit", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Failed to download file: HTTP \(httpResponse.statusCode)"])
        }
        
        // Save the data to a temporary file
        let tempDir = NSTemporaryDirectory()
        let tempFilePath = URL(fileURLWithPath: tempDir).appendingPathComponent(UUID().uuidString + ".mp4")
        try data.write(to: tempFilePath)
        
        // Log file size
        let fileSize = try FileManager.default.attributesOfItem(atPath: tempFilePath.path)[.size] as? Int64 ?? 0
        print("Downloaded file size: \(fileSize) bytes at path: \(tempFilePath.path)")
        
        return tempFilePath.path
    } else {
        // It's a local file path
        // Verify the file exists
        if !FileManager.default.fileExists(atPath: pathOrUrl) {
            throw NSError(domain: "HybridMediaKit", code: -1, userInfo: [NSLocalizedDescriptionKey: "File does not exist at path: \(pathOrUrl)"])
        }
        return pathOrUrl
    }
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
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var frameCount = 0
            writerInput.requestMediaDataWhenReady(on: DispatchQueue.global(qos: .background)) {
                while writerInput.isReadyForMoreMediaData && frameCount < totalFrames {
                    let presentationTime = CMTimeMultiply(frameDuration, multiplier: Int32(frameCount))

                    if !adaptor.append(pixelBuffer, withPresentationTime: presentationTime) {
                        writerInput.markAsFinished()
                        writer.cancelWriting()
                        continuation.resume(throwing: writer.error ?? NSError(
                            domain: "HybridMediaKit",
                            code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Failed to append pixel buffer"]
                        ))
                        return
                    }

                    frameCount += 1
                }

                if frameCount >= totalFrames {
                    writerInput.markAsFinished()
                    writer.finishWriting {
                        if writer.status == .completed {
                            continuation.resume()
                        } else if let error = writer.error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(throwing: NSError(
                                domain: "HybridMediaKit",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "Unknown error during finishWriting"]
                            ))
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