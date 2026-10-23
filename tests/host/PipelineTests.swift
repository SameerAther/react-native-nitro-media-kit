import Foundation

@main struct PipelineHostTests {
    static func rejects(_ body: () throws -> Void) {
        do { try body(); fatalError("Expected invalid plan to fail") } catch {}
    }
    static func main() async throws {
        let source = VideoSource(width: 3840, height: 2160, fps: 60000.0 / 1001, durationMs: 10000, sizeBytes: 50_000_000,
            audioTracks: 1, extensionName: "mp4", mime: "video/avc", hdr: false, bitDepth: 8, bitrateKbps: 40000,
            audioChannels: 6, audioSampleRate: 48000, colorPrimaries: nil, colorTransfer: nil, colorMatrix: nil)
        let preserved = try ExportPlanner.plan(source, options: ExportVideoOptions())
        precondition(preserved.canCopy && preserved.width == 3840 && preserved.fps == source.fps)
        let merged = try ExportPlanner.plan(source, options: ExportVideoOptions(), forceEncode: true)
        precondition(!merged.canCopy && merged.bitrateKbps >= 40000)
        let high = try ExportPlanner.plan(source, options: ExportVideoOptions(preset: .high))
        precondition(high.width == 3840 && high.fps == source.fps && high.bitrateKbps > 12000)
        let cap = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(maxFps: 23.5, frameRatePolicy: .cap)))
        precondition(cap.fps == 23.5)
        rejects { _ = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(maxFps: 24, frameRatePolicy: .preserve))) }
        rejects { _ = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(frameRatePolicy: .constant))) }
        rejects { _ = try ExportPlanner.plan(source, options: ExportVideoOptions(trim: VideoSegment(startMs: .nan, endMs: 5000))) }
        rejects { _ = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(maxFps: .infinity, frameRatePolicy: .cap))) }
        let size = try ExportPlanner.plan(source, options: ExportVideoOptions(trim: VideoSegment(startMs: 2000, endMs: 7000), video: CompressVideoOptions(targetSizeBytes: 5_000_000)))
        precondition(abs(size.estimatedSizeBytes - 5_000_000) < 1 && size.durationMs == 5000)
        rejects { _ = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(videoBitrateKbps: 5000, targetSizeBytes: 5_000_000))) }
        let hdr = VideoSource(width: 3840, height: 2160, fps: source.fps, durationMs: 10000, sizeBytes: 50_000_000,
            audioTracks: 1, extensionName: "mp4", mime: "video/hevc", hdr: true, bitDepth: 10, bitrateKbps: 40000,
            audioChannels: 6, audioSampleRate: 48000, colorPrimaries: nil, colorTransfer: nil, colorMatrix: nil)
        let preservedHdr = try ExportPlanner.plan(hdr, options: ExportVideoOptions())
        precondition(preservedHdr.mime == "video/hevc")
        rejects { _ = try ExportPlanner.plan(hdr, options: ExportVideoOptions(video: CompressVideoOptions(codec: .h264))) }
        rejects { _ = try ExportPlanner.plan(hdr, options: ExportVideoOptions(video: CompressVideoOptions(colorPolicy: .rejectHdr))) }
        _ = try ExportPlanner.plan(hdr, options: ExportVideoOptions(video: CompressVideoOptions(codec: .h264, colorPolicy: .sdr)))
        let explicitSDR = try ExportPlanner.plan(source, options: ExportVideoOptions(video: CompressVideoOptions(colorPolicy: .sdr)))
        precondition(!explicitSDR.canCopy)
        let depthSDR = VideoSource(width: 3840, height: 2160, fps: source.fps, durationMs: 10000, sizeBytes: 50_000_000,
            audioTracks: 1, extensionName: "mp4", mime: "video/hevc", hdr: false, bitDepth: 10, bitrateKbps: 40000,
            audioChannels: 6, audioSampleRate: 48000, colorPrimaries: nil, colorTransfer: nil, colorMatrix: nil)
        let precision = try ExportPlanner.plan(depthSDR, options: ExportVideoOptions())
        precondition(precision.canCopy && precision.mime == "video/hevc")
        rejects { _ = try ExportPlanner.plan(depthSDR, options: ExportVideoOptions(video: CompressVideoOptions(codec: .h264))) }
        print("Swift export planning: dimensions, fractional timing, target size, audio and color policies passed")

        let control = try PipelineControl.registered(id: "host-cancel", limits: nil, progress: nil)
        var cancellationCount = 0
        _ = control.onCancel { cancellationCount += 1 }
        precondition(PipelineControl.cancelJob("host-cancel"))
        precondition(!PipelineControl.cancelJob("host-cancel"))
        precondition(cancellationCount == 1)
        rejects { try control.check() }
        control.close(); PipelineControl.remove(control)
        precondition(!PipelineControl.cancelJob("host-cancel"))
        var admitted: [PipelineControl] = []
        for index in 0..<64 { admitted.append(try PipelineControl.registered(id: "admission-\(index)", limits: nil, progress: nil)) }
        rejects { _ = try PipelineControl.registered(id: "admission-overflow", limits: nil, progress: nil) }
        rejects { _ = try PipelineControl.registered(id: "admission-0", limits: nil, progress: nil) }
        admitted.forEach { $0.close(); PipelineControl.remove($0) }
        let complete = try PipelineControl()
        try complete.seal(); precondition(!complete.cancel()); complete.close()
        rejects { _ = try PipelineControl(limits: ProcessingLimits(timeoutMs: .nan)) }
        let deadline = try PipelineControl(limits: ProcessingLimits(timeoutMs: 1))
        try await Task.sleep(nanoseconds: 10_000_000)
        rejects { try deadline.check() }; deadline.close()
        let gate = MediaWorkGate(permits: 1)
        let first = try PipelineControl(); let second = try PipelineControl(); let third = try PipelineControl()
        try await gate.acquire(first)
        let waiting = Task { try await gate.acquire(second) }
        try await Task.sleep(nanoseconds: 10_000_000)
        _ = second.cancel()
        do { try await waiting.value; fatalError("Cancelled waiter acquired encoder permit") } catch {}
        await gate.release()
        try await gate.acquire(third); await gate.release()
        [first, second, third].forEach { $0.close() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("media-files-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let fileControl = try PipelineControl()
        let files = try MediaJob(control: fileControl, root: root)
        let stage = try files.output("mp4")
        try Data([1, 2, 3]).write(to: stage)
        let result = try files.commit(MediaInfoResult(ok: true, outputUri: stage.path))
        precondition(result.outputUri != stage.path && !FileManager.default.fileExists(atPath: stage.path))
        files.finish(success: true)
        let publicPath = result.outputUri!
        let leaseControl = try PipelineControl()
        let reader = try MediaJob(control: leaseControl, root: root)
        _ = try await reader.resolve(publicPath)
        rejects { _ = try MediaJob.deleteOutput(publicPath, root: root) }
        reader.finish(success: true)
        let deleted = try MediaJob.deleteOutput(publicPath, root: root)
        let deletedAgain = try MediaJob.deleteOutput(publicPath, root: root)
        precondition(deleted && !deletedAgain)
        let limited = try PipelineControl(limits: ProcessingLimits(maxOutputBytes: 2))
        let rollback = try MediaJob(control: limited, root: root)
        let partial = try rollback.output("mp4")
        try Data([1, 2, 3]).write(to: partial)
        rejects { _ = try rollback.commit(MediaInfoResult(ok: true, outputUri: partial.path)) }
        rollback.finish(success: false)
        precondition(!FileManager.default.fileExists(atPath: partial.path))
        let batchControl = try PipelineControl()
        let batch = try MediaJob(control: batchControl, root: root)
        let firstPart = try batch.output("mp4"), missingPart = try batch.output("mp4")
        try Data([1]).write(to: firstPart)
        rejects { _ = try batch.commit(MediaInfoResult(ok: true, segments: [firstPart.path, missingPart.path])) }
        batch.finish(success: false)
        let published = try FileManager.default.contentsOfDirectory(at: MediaJob.outputDirectory(base: root), includingPropertiesForKeys: nil)
        precondition(published.isEmpty, "Partially committed batch must roll back public files")
        print("Swift file ownership: staging, atomic commit, leases, idempotent deletion and byte budgets passed")
        print("Swift job controls: cancellation, sealing, deadline, validation and permit recovery passed")
    }
}
