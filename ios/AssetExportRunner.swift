import Foundation
@preconcurrency import AVFoundation

/// The whole-job deadline remains armed through moov writing and encoder finalization.
private final class ExportRunner: @unchecked Sendable {
    let session: AVAssetExportSession
    let control: PipelineControl
    let continuation: CheckedContinuation<Void, Error>
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "MediaKit.exportSession")
    private var completed = false
    private var keepAlive: ExportRunner?
    private var hook: UUID?
    private var timer: DispatchSourceTimer?
    init(session: AVAssetExportSession, control: PipelineControl, continuation: CheckedContinuation<Void, Error>) {
        self.session = session; self.control = control; self.continuation = continuation
    }
    func start() { queue.async { self.begin() } }
    private func begin() {
        keepAlive = self
        hook = control.onCancel { [weak self] in
            guard let self else { return }
            self.queue.async {
                // Wait for the export completion callback before releasing staging files/permits.
                self.session.cancelExport()
            }
        }
        do { try control.check() } catch { finish(error); return }
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now(), repeating: .milliseconds(200))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.control.emit(.processing, fraction: Double(self.session.progress))
        }
        self.timer = timer; timer.resume()
        session.exportAsynchronously { [weak self] in
            guard let self else { return }
            self.queue.async {
                do {
                    try self.control.check()
                    if self.session.status == .completed { self.finish(nil) }
                    else { self.finish(self.session.error ?? MediaJob.error("Media export failed.")) }
                } catch { self.finish(error) }
            }
        }
    }
    private func finish(_ error: Error?) {
        lock.lock(); guard !completed else { lock.unlock(); return }; completed = true; lock.unlock()
        timer?.cancel(); timer = nil
        if let hook { control.removeHook(hook) }
        keepAlive = nil
        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
    }
}

func runAssetExport(_ session: AVAssetExportSession, control: PipelineControl) async throws {
    try await withCheckedThrowingContinuation { continuation in
        ExportRunner(session: session, control: control, continuation: continuation).start()
    }
}
