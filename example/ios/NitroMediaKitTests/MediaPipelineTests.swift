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
            let thumbnail = try await kit.extractThumbnail(video: path, options: nil).await()
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
            XCTAssertGreaterThan(whitePixels, 50, "Watermark must alter real video pixels, including on Simulator")
        }
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
        XCTAssertEqual(compressed.media?.audioTracks, 1, "AAC compression must receive decoded PCM")
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
