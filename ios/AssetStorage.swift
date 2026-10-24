import Foundation

final class AssetStorage: @unchecked Sendable {
  private static let countLock = NSLock()
  private static var count = 0
  let path: String
  private let closeOwner: () -> Void
  private let lock = NSLock()
  private let size: UInt64
  private let modified: Date?
  private var closed = false
  private var users = 0
  init(path: String, onClose: @escaping () -> Void) throws {
    self.path = path
    self.closeOwner = onClose
    let attrs = try FileManager.default.attributesOfItem(atPath: path)
    size = (attrs[.size] as? NSNumber)?.uint64Value ?? 0
    modified = attrs[.modificationDate] as? Date
    Self.countLock.lock()
    guard Self.count < 64 else {
      Self.countLock.unlock()
      throw NSError(
        domain: "ASSET_LIMIT", code: 1,
        userInfo: [
          NSLocalizedDescriptionKey:
            "[ASSET_LIMIT] Dispose an asset before opening more than 64 inputs."
        ])
    }
    Self.count += 1
    Self.countLock.unlock()
  }
  func borrow() throws -> AssetLease {
    lock.lock()
    defer { lock.unlock() }
    guard !closed else {
      throw NSError(
        domain: "ASSET_CLOSED", code: 1,
        userInfo: [NSLocalizedDescriptionKey: "[ASSET_CLOSED] Media asset has been disposed."])
    }
    users += 1
    return AssetLease(self)
  }
  fileprivate func validate() throws {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: path) else {
      throw NSError(
        domain: "INPUT_CHANGED", code: 1,
        userInfo: [NSLocalizedDescriptionKey: "[INPUT_CHANGED] Media source changed; reopen it."])
    }
    guard (attrs[.size] as? NSNumber)?.uint64Value == size,
      attrs[.modificationDate] as? Date == modified
    else {
      throw NSError(
        domain: "INPUT_CHANGED", code: 1,
        userInfo: [NSLocalizedDescriptionKey: "[INPUT_CHANGED] Media source changed; reopen it."])
    }
  }
  private func finishOwner() {
    Self.countLock.lock()
    Self.count -= 1
    Self.countLock.unlock()
    closeOwner()
  }
  fileprivate func release() {
    lock.lock()
    users -= 1
    let finish = closed && users == 0
    lock.unlock()
    if finish { finishOwner() }
  }
  func close() {
    lock.lock()
    let finish = !closed && users == 0
    closed = true
    lock.unlock()
    if finish { finishOwner() }
  }
}
final class AssetLease: @unchecked Sendable {
  private let storage: AssetStorage
  init(_ storage: AssetStorage) { self.storage = storage }
  func validate() throws { try storage.validate() }
  deinit { storage.release() }
}
