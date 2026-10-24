import Foundation
import NitroModules

final class ProgressRelay: @unchecked Sendable {
  private let lock = NSLock()
  private var listener: ((ExportProgress) -> Void)?
  private var finished = false
  init(_ listener: ((ExportProgress) -> Void)?) { self.listener = listener }
  func set(_ value: ((ExportProgress) -> Void)?) {
    lock.lock()
    if !finished { listener = value }
    lock.unlock()
  }
  func finish() {
    lock.lock()
    finished = true
    listener = nil
    lock.unlock()
  }
  func emit(_ value: ExportProgress) {
    lock.lock()
    let callback = listener
    lock.unlock()
    callback?(value)
  }
}

final class NativeExportJob: HybridExportJobSpec, @unchecked Sendable {
  let id: String
  private let control: PipelineControl
  private let relay: ProgressRelay
  private let lock = NSLock()
  private var currentState: JobState = .queued
  var state: JobState {
    lock.lock()
    defer { lock.unlock() }
    return currentState
  }
  private let completion = Promise<VideoExportResult>()
  init(
    control: PipelineControl, relay: ProgressRelay,
    work: @escaping () async throws -> VideoExportResult
  ) {
    id = control.id
    self.control = control
    self.relay = relay
    super.init()
    Task.detached(priority: .userInitiated) { [self] in
      do {
        update(.running)
        let result = try await work()
        update(.completed)
        relay.finish()
        completion.resolve(withResult: result)
      } catch {
        update((error as NSError).domain == "CANCELLED" ? .cancelled : .failed)
        relay.finish()
        completion.reject(withError: MediaErrors.normalize(error))
      }
    }
  }
  private func update(_ value: JobState) {
    lock.lock()
    currentState = value
    lock.unlock()
  }
  func result() -> Promise<VideoExportResult> { completion }
  func cancel() -> Bool { (state == .queued || state == .running) && control.cancel() }
  func setProgressListener(listener: ((ExportProgress) -> Void)?) {
    if state == .queued || state == .running { relay.set(listener) }
  }
  func dispose() {
    relay.finish()
    control.cancel()
  }
}

final class NativeMediaAsset: HybridMediaAssetSpec, @unchecked Sendable {
  let uri: String, type: MediaType, info: MediaInfoMedia
  private let kit: NitroMediaKit, storage: AssetStorage
  private let source: VideoSource?
  var memorySize: Int { 512 }
  init(
    kit: NitroMediaKit, uri: String, type: MediaType, info: MediaInfoMedia, storage: AssetStorage,
    source: VideoSource?
  ) {
    self.kit = kit
    self.uri = uri
    self.type = type
    self.info = info
    self.storage = storage
    self.source = source
    super.init()
  }
  func planExport(options: ExportVideoOptions) throws -> Promise<ExportPlan> {
    let lease = try storage.borrow()
    return kit.owned(limits: options.limits) { [self, lease] job in
      defer { withExtendedLifetime(lease) {} }
      try lease.validate()
      let path = try await job.resolve(storage.path)
      let plan = try await kit.planLocal(path: path, options: options, source: source)
      _ = try job.commitPaths([])
      return plan
    }
  }
  func createExport(options: ExportVideoOptions, onProgress: ((ExportProgress) -> Void)?) throws
    -> any HybridExportJobSpec
  {
    let lease = try storage.borrow()
    return try kit.startExport(
      uri: storage.path, options: options, onProgress: onProgress, source: source, lease: lease)
  }
  func extractThumbnails(options: ThumbnailBatchOptions) throws -> Promise<ThumbnailBatchResult> {
    let lease = try storage.borrow()
    return Promise.async { [self, lease] in
      defer { withExtendedLifetime(lease) {} }
      try lease.validate()
      return try await kit.thumbnailBatch(uri: storage.path, options: options, source: source)
        .await()
    }
  }
  func extractAudio(options: ExtractAudioOptions) throws -> Promise<AudioExportResult> {
    let lease = try storage.borrow()
    return Promise.async { [self, lease] in
      defer { withExtendedLifetime(lease) {} }
      try lease.validate()
      return try await kit.extractAudio(uri: storage.path, options: options).await()
    }
  }
  func analyzeAudio(options: AudioAnalysisOptions) throws -> Promise<LoudnessAnalysis> {
    let lease = try storage.borrow()
    return Promise.async { [self, lease] in
      defer { withExtendedLifetime(lease) {} }
      try lease.validate()
      return try await kit.analyzeAudio(uri: storage.path, options: options).await()
    }
  }
  func normalizeAudio(options: NormalizeAudioOptions) throws -> Promise<NormalizedAudioResult> {
    let lease = try storage.borrow()
    return Promise.async { [self, lease] in
      defer { withExtendedLifetime(lease) {} }
      try lease.validate()
      return try await kit.normalizeAudio(uri: storage.path, options: options).await()
    }
  }
  func dispose() { storage.close() }
  deinit { storage.close() }
}
