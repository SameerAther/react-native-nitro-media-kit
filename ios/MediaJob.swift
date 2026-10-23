import Foundation
@preconcurrency import AVFoundation

/// Each operation owns a private staging directory. Only verified outputs become public cache files.
final class MediaJob: @unchecked Sendable {
    @TaskLocal static var current: MediaJob?
    let control: PipelineControl
    private let lock = NSLock()
    private let directory: URL
    private let root: URL
    private var outputs: [URL] = []
    private var committed: [URL] = []
    private var resolved: [String: String] = [:]
    private var leases: Set<String> = []
    private static let leaseLock = NSLock()
    private static var inputLeases: [String: Int] = [:]
    private static var activeDirectories: Set<String> = []

    init(control: PipelineControl, root suppliedRoot: URL? = nil) throws {
        self.control = control
        let root = try Self.rootDirectory(base: suppliedRoot)
        self.root = root
        let jobs = root.appendingPathComponent("jobs", isDirectory: true)
        directory = jobs.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        Self.leaseLock.lock(); Self.activeDirectories.insert(directory.path); Self.leaseLock.unlock()
        control.resourceCheck = { [weak self] in try self?.checkResources() }
        let cutoff = Date().addingTimeInterval(-86400)
        for url in (try? FileManager.default.contentsOfDirectory(at: jobs, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            Self.leaseLock.lock(); let active = Self.activeDirectories.contains(url.path); Self.leaseLock.unlock()
            if !active, let date = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, date < cutoff { try? FileManager.default.removeItem(at: url) }
        }
    }
    private static func rootDirectory(base: URL? = nil) throws -> URL {
        let root = base ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("nitro-media-kit", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
    static func outputDirectory(base: URL? = nil) throws -> URL {
        let directory = try rootDirectory(base: base).appendingPathComponent("output", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
    func output(_ ext: String) throws -> URL {
        try control.check()
        let url = directory.appendingPathComponent(UUID().uuidString.lowercased()).appendingPathExtension(ext)
        lock.lock(); outputs.append(url); lock.unlock()
        return url
    }
    func resolve(_ source: String) async throws -> String {
        if let path = resolved[source] { return path }
        try control.check()
        guard !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw Self.error("Input URI must not be empty.") }
        let parsed = URL(string: source)
        let path: String
        if let url = parsed, ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            try await MediaWorkGate.io.acquire(control)
            do {
                let destination = directory.appendingPathComponent("input-\(UUID().uuidString).bin")
                try await BoundedDownload.download(url: url, destination: destination, control: control)
                path = destination.path
                await MediaWorkGate.io.release()
            } catch { await MediaWorkGate.io.release(); throw error }
        } else {
            if let parsed, parsed.scheme != nil, !parsed.isFileURL { throw Self.error("Unsupported input URI scheme.") }
            guard source.hasPrefix("/") || parsed?.isFileURL == true else { throw Self.error("Input path must be absolute.") }
            let url = (parsed?.isFileURL == true ? parsed! : URL(fileURLWithPath: source)).resolvingSymlinksInPath().standardizedFileURL
            acquireLease(url.path)
            path = url.path
        }
        var isDirectory: ObjCBool = false
        let size = ((try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.int64Value ?? 0
        guard path.hasPrefix("/"), FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory), !isDirectory.boolValue,
              FileManager.default.isReadableFile(atPath: path), size > 0, size <= control.maxInputBytes else {
            throw Self.error("Input must be readable, nonempty, and within the input byte budget.")
        }
        try control.check(); resolved[source] = path
        return path
    }
    private func acquireLease(_ path: String) {
        Self.leaseLock.lock(); defer { Self.leaseLock.unlock() }
        if leases.insert(path).inserted { Self.inputLeases[path, default: 0] += 1 }
    }
    private func checkResources() throws {
        try control.check()
        let free = (try FileManager.default.attributesOfFileSystem(forPath: directory.path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        guard free >= control.minFreeDiskBytes else { throw Self.error("Insufficient free disk space.") }
        lock.lock(); let files = outputs; lock.unlock()
        for file in files {
            let size = ((try? FileManager.default.attributesOfItem(atPath: file.path)[.size]) as? NSNumber)?.int64Value ?? 0
            guard size <= control.maxOutputBytes else { throw Self.error("Output byte budget exceeded.") }
        }
    }
    func commit(_ result: MediaInfoResult) throws -> MediaInfoResult {
        guard result.ok else { return result }
        try checkResources()
        var mapping: [String: String] = [:]
        for path in (result.outputUri.map { [$0] } ?? []) + (result.segments ?? []) {
            let file = URL(fileURLWithPath: path)
            guard outputs.contains(file), ((try FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.int64Value ?? 0 > 0 else { throw Self.error("Invalid staged output.") }
            let destination = try Self.outputDirectory(base: root).appendingPathComponent(file.lastPathComponent)
            try FileManager.default.moveItem(at: file, to: destination)
            committed.append(destination); mapping[path] = destination.path
        }
        try control.seal()
        return MediaInfoResult(ok: result.ok, operation: result.operation, type: result.type, inputUri: result.inputUri,
            outputUri: result.outputUri.flatMap { mapping[$0] }, segments: result.segments?.compactMap { mapping[$0] },
            media: result.media, warnings: result.warnings, error: result.error, actualTimeMs: result.actualTimeMs, diagnostics: result.diagnostics)
    }
    func finish(success: Bool) {
        control.resourceCheck = nil
        if !success { committed.forEach { try? FileManager.default.removeItem(at: $0) } }
        try? FileManager.default.removeItem(at: directory)
        Self.leaseLock.lock()
        Self.activeDirectories.remove(directory.path)
        for path in leases {
            let count = (Self.inputLeases[path] ?? 1) - 1
            if count == 0 { Self.inputLeases.removeValue(forKey: path) } else { Self.inputLeases[path] = count }
        }
        Self.leaseLock.unlock(); control.close()
    }
    static func deleteOutput(_ source: String, root: URL? = nil) throws -> Bool {
        let url: URL
        if source.hasPrefix("/") { url = URL(fileURLWithPath: source) }
        else if let parsed = URL(string: source), parsed.isFileURL { url = parsed }
        else { throw error("Expected a generated file path or file URI.") }
        let canonical = url.resolvingSymlinksInPath().standardizedFileURL
        guard canonical.deletingLastPathComponent() == (try outputDirectory(base: root)).resolvingSymlinksInPath().standardizedFileURL,
              UUID(uuidString: canonical.deletingPathExtension().lastPathComponent) != nil,
              ["jpg", "png", "mp4"].contains(canonical.pathExtension) else { throw error("Only generated outputs can be deleted.") }
        leaseLock.lock(); defer { leaseLock.unlock() }
        guard inputLeases[canonical.path] == nil else { throw error("Output is in use by an active job.") }
        guard FileManager.default.fileExists(atPath: canonical.path) else { return false }
        try FileManager.default.removeItem(at: canonical); return true
    }
    static func error(_ message: String) -> NSError { NSError(domain: "NitroMediaKit", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}

/// Writer callbacks, cancellation and finalization converge on one serial terminal state.
private final class SampleTransfer: @unchecked Sendable {
    let reader: AVAssetReader
    let writer: AVAssetWriter
    let pairs: [(AVAssetReaderOutput, AVAssetWriterInput)]
    let control: PipelineControl
    let duration: Double
    let maxFps: Double?
    private var firstVideoTime: Double?
    private var nextVideoIndex = 0.0
    let continuation: CheckedContinuation<Void, Error>
    private let queue = DispatchQueue(label: "NitroMediaKit.samples")
    private var remaining: Int
    private var completed = false
    private var hook: UUID?
    private var keepAlive: SampleTransfer?
    init(reader: AVAssetReader, writer: AVAssetWriter, pairs: [(AVAssetReaderOutput, AVAssetWriterInput)], control: PipelineControl, duration: Double, maxFps: Double?, continuation: CheckedContinuation<Void, Error>) {
        self.reader = reader; self.writer = writer; self.pairs = pairs; self.control = control; self.duration = duration; self.maxFps = maxFps
        self.continuation = continuation; remaining = pairs.count
    }
    func start() { queue.async { self.begin() } }
    private func begin() {
        keepAlive = self
        hook = control.onCancel { [weak self] in
            guard let self else { return }
            self.reader.cancelReading()
            self.queue.async {
                do { try self.control.check(); self.terminate(CancellationError()) }
                catch { self.terminate(error) }
            }
        }
        for index in pairs.indices {
            let input = pairs[index].1
            var ended = false
            input.requestMediaDataWhenReady(on: queue) { [weak self, weak input] in
                guard let self, let input, !ended, !self.completed else { return }
                do {
                    while input.isReadyForMoreMediaData && !self.completed {
                        try self.control.check()
                        guard let sample = autoreleasepool(invoking: { self.pairs[index].0.copyNextSampleBuffer() }) else {
                            ended = true; input.markAsFinished()
                            if self.reader.status == .failed { throw self.reader.error ?? MediaJob.error("Cannot read media sample.") }
                            self.remaining -= 1
                            if self.remaining == 0 {
                                self.writer.finishWriting { [self] in self.queue.async {
                                    self.terminate(self.writer.status == .completed ? nil : self.writer.error ?? MediaJob.error("Cannot finalize media file."))
                                } }
                            }
                            return
                        }
                        if input.mediaType == .video, let maxFps = self.maxFps {
                            let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds
                            if self.firstVideoTime == nil { self.firstVideoTime = time }
                            let position = (time - self.firstVideoTime!) * maxFps
                            if position + 0.000001 < self.nextVideoIndex { continue }
                            self.nextVideoIndex = floor(position + 0.000001) + 1
                        }
                        guard input.append(sample) else { throw self.writer.error ?? MediaJob.error("Cannot append media sample.") }
                        if input.mediaType == .video {
                            self.control.countFrame()
                            let time = CMSampleBufferGetPresentationTimeStamp(sample).seconds
                            self.control.emit(.processing, fraction: self.duration > 0 ? time / self.duration : nil)
                        }
                    }
                } catch { ended = true; self.terminate(error) }
            }
        }
    }
    private func terminate(_ error: Error?) {
        guard !completed else { return }
        completed = true
        if let hook { control.removeHook(hook) }
        keepAlive = nil
        if let error { reader.cancelReading(); writer.cancelWriting(); continuation.resume(throwing: error) }
        else {
            do { try control.check(); continuation.resume() }
            catch { continuation.resume(throwing: error) }
        }
    }
}
func transferSamples(reader: AVAssetReader, writer: AVAssetWriter, pairs: [(AVAssetReaderOutput, AVAssetWriterInput)], duration: Double = 0, maxFps: Double? = nil) async throws {
    guard let control = MediaJob.current?.control else { throw MediaJob.error("Missing media job context.") }
    try await withCheckedThrowingContinuation { continuation in
        SampleTransfer(reader: reader, writer: writer, pairs: pairs, control: control, duration: duration, maxFps: maxFps, continuation: continuation).start()
    }
}

/// Serial ownership keeps the reusable image buffer alive through writer finalization.
private final class ImageFrameTransfer: @unchecked Sendable {
    let adaptor: AVAssetWriterInputPixelBufferAdaptor
    let input: AVAssetWriterInput
    let writer: AVAssetWriter
    let buffer: CVPixelBuffer
    let frameDuration: CMTime
    let totalFrames: Int
    let control: PipelineControl
    let continuation: CheckedContinuation<Void, Error>
    private let queue = DispatchQueue(label: "NitroMediaKit.imageFrames")
    private var count = 0
    private var completed = false
    private var finalizing = false
    private var hook: UUID?
    private var keepAlive: ImageFrameTransfer?
    init(adaptor: AVAssetWriterInputPixelBufferAdaptor, input: AVAssetWriterInput, writer: AVAssetWriter, buffer: CVPixelBuffer,
        frameDuration: CMTime, totalFrames: Int, control: PipelineControl, continuation: CheckedContinuation<Void, Error>) {
        self.adaptor = adaptor; self.input = input; self.writer = writer; self.buffer = buffer
        self.frameDuration = frameDuration; self.totalFrames = totalFrames; self.control = control; self.continuation = continuation
    }
    func start() { queue.async { self.begin() } }
    private func begin() {
        keepAlive = self
        hook = control.onCancel { [weak self] in
            guard let self else { return }
            self.queue.async {
                do { try self.control.check(); self.finish(CancellationError()) } catch { self.finish(error) }
            }
        }
        input.requestMediaDataWhenReady(on: queue) { [weak self] in
            guard let self, !self.completed, !self.finalizing else { return }
            do {
                while self.input.isReadyForMoreMediaData && self.count < self.totalFrames {
                    try self.control.check()
                    let time = CMTimeMultiply(self.frameDuration, multiplier: Int32(self.count))
                    guard self.adaptor.append(self.buffer, withPresentationTime: time) else { throw self.writer.error ?? MediaJob.error("Cannot append image video frame.") }
                    self.count += 1; self.control.countFrame()
                    self.control.emit(.processing, fraction: Double(self.count) / Double(self.totalFrames))
                }
                if self.count == self.totalFrames {
                    self.finalizing = true
                    self.input.markAsFinished()
                    self.writer.endSession(atSourceTime: CMTimeMultiply(self.frameDuration, multiplier: Int32(self.totalFrames)))
                    self.writer.finishWriting { [self] in self.queue.async {
                        do {
                            try self.control.check()
                            self.finish(self.writer.status == .completed ? nil : self.writer.error ?? MediaJob.error("Cannot finalize image video."))
                        } catch { self.finish(error) }
                    } }
                }
            } catch { self.finish(error) }
        }
    }
    private func finish(_ error: Error?) {
        guard !completed else { return }
        completed = true
        if let hook { control.removeHook(hook) }
        if let error { writer.cancelWriting(); continuation.resume(throwing: error) }
        else { continuation.resume() }
        keepAlive = nil
    }
}

func transferImageFrames(adaptor: AVAssetWriterInputPixelBufferAdaptor, input: AVAssetWriterInput, writer: AVAssetWriter,
    buffer: CVPixelBuffer, frameDuration: CMTime, totalFrames: Int, control: PipelineControl) async throws {
    try await withCheckedThrowingContinuation { continuation in
        ImageFrameTransfer(adaptor: adaptor, input: input, writer: writer, buffer: buffer,
            frameDuration: frameDuration, totalFrames: totalFrames, control: control, continuation: continuation).start()
    }
}
