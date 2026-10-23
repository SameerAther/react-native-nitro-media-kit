import XCTest
import UIKit
import AVFoundation
@testable import NitroMediaKit

final class MediaPipelineTests: XCTestCase {
    private let kit = NitroMediaKit()
    private var generated: [String] = []

    private func fixture(_ name: String) throws -> String {
        try XCTUnwrap(Bundle(for: Self.self).path(forResource: name, ofType: "mp4"))
    }

    private func output(_ result: MediaInfoResult) throws -> String {
        XCTAssertTrue(result.ok, result.error?.message ?? "Native operation failed")
        let path = try XCTUnwrap(result.outputUri)
        generated.append(path)
        return path
    }

    override func tearDown() async throws {
        for path in generated { _ = try await kit.deleteGeneratedFile(uri: path).await() }
        generated = []
    }

    func testWatermarkChangesFramesAndPreservesAudioAndRotation() async throws {
        for name in ["landscape", "portrait"] {
            let source = try fixture(name)
            let result = try await kit.watermarkVideo(video: source, watermark: "VISIBLE", options:
                WatermarkOptions(position: .topLeft, fontSize: 20, color: "#FFFFFFFF", opacity: 1, margin: 8)).await()
            let path = try output(result)
            XCTAssertEqual(result.media?.audioTracks, 1)
            XCTAssertEqual(result.media?.width, name == "portrait" ? 180 : 320)
            XCTAssertEqual(result.media?.height, name == "portrait" ? 320 : 180)
            XCTAssertEqual(result.media?.durationMs ?? 0, 2000, accuracy: 150)
            XCTAssertEqual(result.media?.fps ?? 0, 10, accuracy: 0.05)
            for index in 0..<20 {
                let thumbnail = try await kit.extractThumbnail(video: path,
                    options: ThumbnailOptions(timeMs: Double(index * 100), maxLongEdge: nil, quality: 1, precision: .precise)).await()
                XCTAssertEqual(thumbnail.actualTimeMs ?? -1, Double(index * 100), accuracy: 1)
                let imagePath = try output(thumbnail)
                let image = try XCTUnwrap(UIImage(contentsOfFile: imagePath)?.cgImage)
                var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
                try pixels.withUnsafeMutableBytes { bytes in
                    let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                        bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
                    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
                }
                let whitePixels = stride(from: 0, to: pixels.count, by: 4).filter { pixels[$0] > 180 && pixels[$0 + 1] > 180 && pixels[$0 + 2] > 180 }.count
                XCTAssertGreaterThan(whitePixels, 50, "Watermark missing at frame \(index)")
            }
        }
    }

    private func videoTimes(_ path: String) async throws -> [Double] {
        let asset = AVURLAsset(url: URL(fileURLWithPath: path))
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var times: [Double] = []
        while let sample = output.copyNextSampleBuffer() { times.append(CMSampleBufferGetPresentationTimeStamp(sample).seconds) }
        XCTAssertEqual(reader.status, .completed)
        return times.sorted()
    }

    func testCombinedExportHasConstantTimingAndPreservesMonoAudio() async throws {
        let source = try fixture("landscape")
        let result = try await kit.exportVideo(video: source, options: ExportVideoOptions(trim: VideoSegment(startMs: 500, endMs: 1500), watermark: "VISIBLE",
            watermarkOptions: WatermarkOptions(position: .topLeft, fontSize: 12, color: "#FFFFFFFF", opacity: 1, margin: 4), preset: .high,
            video: CompressVideoOptions(maxLongEdge: 160, maxFps: 20, videoBitrateKbps: nil, audioBitrateKbps: nil, keepAudio: nil, codec: .h264, colorPolicy: .sdr, frameRatePolicy: .constant, targetSizeBytes: nil),
            audio: .preserve, preserveMetadata: false, limits: nil), jobId: "combined-regression", onProgress: nil).await()
        let path = try output(result)
        XCTAssertEqual(result.media?.width, 160)
        XCTAssertEqual(result.media?.audioChannels, 1)
        let times = try await videoTimes(path)
        XCTAssertEqual(times.count, 20)
        for (index, time) in times.enumerated() { XCTAssertEqual(time - times[0], Double(index) / 20, accuracy: 0.0001) }
    }

    func testWatermarkPreservesVariablePresentationTimes() async throws {
        let source = try fixture("motion-vfr")
        let result = try await kit.watermarkVideo(video: source, watermark: "VFR", options: nil).await()
        let path = try output(result)
        let before = try await videoTimes(source), after = try await videoTimes(path)
        XCTAssertEqual(before.count, after.count)
        for (a, b) in zip(before, after) { XCTAssertEqual(a - before[0], b - after[0], accuracy: 0.0001) }
    }

    func testTrimSplitMuteAndCompressionKeepValidMedia() async throws {
        let source = try fixture("landscape")
        let trimmed = try await kit.trimVideo(video: source, segment: VideoSegment(startMs: 250, endMs: 1250)).await()
        _ = try output(trimmed)
        XCTAssertEqual(trimmed.media?.durationMs ?? 0, 1000, accuracy: 150)
        let muted = try await kit.removeAudio(video: source).await()
        _ = try output(muted)
        XCTAssertEqual(muted.media?.audioTracks, 0)
        let compressed = try await kit.compressMedia(inputUri: source, options: nil).await()
        _ = try output(compressed)
        XCTAssertEqual(compressed.media?.audioTracks, 1, "Compression must retain the primary audio track")
        let split = try await kit.splitVideo(video: source, segments: [VideoSegment(startMs: 0, endMs: 500), VideoSegment(startMs: 1000, endMs: 1500)]).await()
        XCTAssertTrue(split.ok, split.error?.message ?? "Split failed")
        XCTAssertEqual(split.segments?.count, 2)
        generated += split.segments ?? []
    }

    func testImageVideoAndMixedOrientationMerge() async throws {
        let image = try XCTUnwrap(Bundle(for: Self.self).path(forResource: "photo", ofType: "png"))
        let converted = try await kit.convertImageToVideo(image: image, duration: 0.01).await()
        let convertedPath = try output(converted)
        XCTAssertGreaterThan(converted.media?.durationMs ?? 0, 0)
        XCTAssertEqual(converted.media?.audioTracks, 0)
        let merged = try await kit.mergeVideos(videos: [try fixture("landscape"), try fixture("portrait"), convertedPath]).await()
        _ = try output(merged)
        XCTAssertEqual(merged.media?.width, 320)
        XCTAssertEqual(merged.media?.height, 180)
        XCTAssertEqual(merged.media?.audioTracks, 1)
        XCTAssertEqual(merged.media?.durationMs ?? 0, 4033, accuracy: 150)
        let fileInfo = try await kit.getMediaInfo(inputUri: URL(fileURLWithPath: convertedPath).absoluteString).await()
        XCTAssertTrue(fileInfo.ok)
    }

    func testInvalidRangesAndSafeDeletion() async throws {
        let source = try fixture("landscape")
        let result = try await kit.trimVideo(video: source, segment: VideoSegment(startMs: .nan, endMs: 1000)).await()
        XCTAssertFalse(result.ok)
        do {
            _ = try await kit.deleteGeneratedFile(uri: source).await()
            XCTFail("Caller-owned input must not be deletable")
        } catch { XCTAssertTrue(FileManager.default.fileExists(atPath: source)) }
    }
}
