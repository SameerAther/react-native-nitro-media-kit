import Foundation
import Darwin

/// Owns cancellation and a whole-job deadline, including queueing and file finalization.
final class PipelineControl: @unchecked Sendable {
    private static let registryLock = NSLock()
    private static var jobs: [String: PipelineControl] = [:]
    let id: String
    let started = DispatchTime.now().uptimeNanoseconds
    let timeoutMs: Double
    let maxInputBytes: Int64
    let maxOutputBytes: Int64
    let minFreeDiskBytes: Int64
    private let lock = NSLock()
    private var failure: Error?
    private var terminal = false
    private var peakResidentBytes: Double? = PipelineControl.residentBytes()
    private var lastMemorySample: UInt64 = 0
    var peakMemoryBytes: Double? { lock.lock(); defer { lock.unlock() }; return peakResidentBytes }
    private var hooks: [UUID: () -> Void] = [:]
    private var timer: DispatchSourceTimer?
    private let progress: ((ExportProgress) -> Void)?
    private var lastStage: ExportStage?
    private var lastProgress: UInt64 = 0
    private var resourceCallback: (() throws -> Void)?
    var resourceCheck: (() throws -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return resourceCallback }
        set { lock.lock(); resourceCallback = newValue; lock.unlock() }
    }
    private(set) var frameCount = 0

    init(id: String = UUID().uuidString, limits: ProcessingLimits? = nil, progress: ((ExportProgress) -> Void)? = nil) throws {
        self.id = id; self.progress = progress
        timeoutMs = try Self.limit(limits?.timeoutMs, default: 1_800_000, min: 1, max: 86_400_000, name: "timeoutMs")
        maxInputBytes = Int64(try Self.limit(limits?.maxInputBytes, default: 2_147_483_648, min: 1, max: 68_719_476_736, name: "maxInputBytes"))
        maxOutputBytes = Int64(try Self.limit(limits?.maxOutputBytes, default: 4_294_967_296, min: 1, max: 68_719_476_736, name: "maxOutputBytes"))
        minFreeDiskBytes = Int64(try Self.limit(limits?.minFreeDiskBytes, default: 67_108_864, min: 0, max: 68_719_476_736, name: "minFreeDiskBytes"))
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + .milliseconds(100), repeating: .milliseconds(100))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if DispatchTime.now().uptimeNanoseconds - self.lastMemorySample >= 1_000_000_000 {
                if let memory = Self.residentBytes() { self.lock.lock(); self.peakResidentBytes = max(self.peakResidentBytes ?? 0, memory); self.lock.unlock() }
                self.lastMemorySample = DispatchTime.now().uptimeNanoseconds
            }
            do { try self.check(); try self.resourceCheck?() }
            catch { _ = self.cancel(error) }
        }
        self.timer = timer; timer.resume()
    }

    static func registered(id: String, limits: ProcessingLimits?, progress: ((ExportProgress) -> Void)?) throws -> PipelineControl {
        guard !id.isEmpty, id.count <= 128 else { throw MediaJob.error("Invalid job ID.") }
        let control = try PipelineControl(id: id, limits: limits, progress: progress)
        do { try admit(control) } catch { control.close(); throw error }
        return control
    }
    static func admit(_ control: PipelineControl) throws {
        registryLock.lock(); defer { registryLock.unlock() }
        if jobs[control.id] === control { return }
        guard jobs[control.id] == nil else { throw MediaJob.error("Job ID is already active.") }
        guard jobs.count < 64 else { throw MediaJob.error("Media job queue is full.") }
        jobs[control.id] = control
    }
    static func cancelJob(_ id: String) -> Bool {
        registryLock.lock(); let control = jobs[id]; registryLock.unlock()
        return control?.cancel() ?? false
    }
    static func remove(_ control: PipelineControl) {
        registryLock.lock(); if jobs[control.id] === control { jobs.removeValue(forKey: control.id) }; registryLock.unlock()
    }
    var elapsedMs: Double { Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000 }
    func check() throws {
        lock.lock(); let error = failure; let done = terminal; lock.unlock()
        if done { return }
        if let error { throw error }
        if elapsedMs >= timeoutMs { throw NSError(domain: "TIMEOUT", code: 1, userInfo: [NSLocalizedDescriptionKey: "Media job deadline exceeded."]) }
    }
    @discardableResult func cancel(_ error: Error = NSError(domain: "CANCELLED", code: -999, userInfo: [NSLocalizedDescriptionKey: "Media job cancelled."])) -> Bool {
        lock.lock()
        guard failure == nil && !terminal else { lock.unlock(); return false }
        failure = error; let actions = Array(hooks.values); lock.unlock()
        actions.forEach { $0() }; return true
    }
    func onCancel(_ action: @escaping () -> Void) -> UUID {
        let key = UUID()
        lock.lock(); hooks[key] = action; let cancelled = failure != nil; lock.unlock()
        if cancelled { action() }
        return key
    }
    func removeHook(_ key: UUID) { lock.lock(); hooks.removeValue(forKey: key); lock.unlock() }
    func emit(_ stage: ExportStage, fraction: Double? = nil) {
        lock.lock()
        let now = DispatchTime.now().uptimeNanoseconds
        if terminal && stage != .completed { lock.unlock(); return }
        guard stage != lastStage || now - lastProgress >= 200_000_000 else { lock.unlock(); return }
        lastStage = stage; lastProgress = now; lock.unlock()
        progress?(ExportProgress(jobId: id, stage: stage, fraction: fraction.map { min(1, max(0, $0)) }))
    }
    func seal() throws {
        lock.lock(); defer { lock.unlock() }
        if let failure { throw failure }
        guard elapsedMs < timeoutMs else { throw NSError(domain: "TIMEOUT", code: 1, userInfo: [NSLocalizedDescriptionKey: "Media job deadline exceeded."]) }
        terminal = true
    }
    func countFrame() { lock.lock(); frameCount += 1; lock.unlock() }
    func close() { timer?.cancel(); timer = nil; lock.lock(); hooks = [:]; resourceCallback = nil; lock.unlock() }
    static func limit(_ value: Double?, default fallback: Double, min lower: Double, max upper: Double, name: String) throws -> Double {
        let value = value ?? fallback
        guard value.isFinite, value >= lower, value <= upper else { throw MediaJob.error("\(name) must be finite and in [\(lower), \(upper)].") }
        return value
    }
    private static func residentBytes() -> Double? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Double(info.resident_size) : nil
    }
    deinit { timer?.cancel() }
}

/// Bounded, cancellation-aware permits. A cancelled waiter never consumes an encoder slot.
actor MediaWorkGate {
    static let shared = MediaWorkGate(permits: 1)
    static let io = MediaWorkGate(permits: 2)
    static let images = MediaWorkGate(permits: 1)
    private struct Waiter {
        let id: UUID; let control: PipelineControl; let hook: UUID
        let continuation: CheckedContinuation<Void, Error>
    }
    private var permits: Int
    private var waiting: [Waiter] = []
    init(permits: Int) { self.permits = permits }
    func acquire(_ control: PipelineControl) async throws {
        try control.check()
        if permits > 0 { permits -= 1; return }
        guard waiting.count < 64 else { throw MediaJob.error("Media job queue is full.") }
        try await withCheckedThrowingContinuation { continuation in
            let id = UUID()
            let hook = control.onCancel { Task.detached { await self.cancel(id) } }
            waiting.append(Waiter(id: id, control: control, hook: hook, continuation: continuation))
        }
    }
    private func cancel(_ id: UUID) {
        guard let index = waiting.firstIndex(where: { $0.id == id }) else { return }
        let waiter = waiting.remove(at: index); waiter.control.removeHook(waiter.hook)
        do { try waiter.control.check() } catch { waiter.continuation.resume(throwing: error); return }
        waiter.continuation.resume(throwing: CancellationError())
    }
    func release() {
        while !waiting.isEmpty {
            let waiter = waiting.removeFirst(); waiter.control.removeHook(waiter.hook)
            do { try waiter.control.check(); waiter.continuation.resume(); return }
            catch { waiter.continuation.resume(throwing: error) }
        }
        permits += 1
    }
}

func withMediaEncoder<T>(_ body: () async throws -> T) async throws -> T {
    guard let control = MediaJob.current?.control else { throw MediaJob.error("Missing job context.") }
    try await MediaWorkGate.shared.acquire(control)
    do { let result = try await body(); await MediaWorkGate.shared.release(); return result }
    catch { await MediaWorkGate.shared.release(); throw error }
}
