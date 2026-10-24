import Foundation

/// Streams chunks to job-owned disk storage; declared and actual lengths are both enforced.
private final class DownloadReceiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
  let url: URL
  let destination: URL
  let control: PipelineControl
  let continuation: CheckedContinuation<Void, Error>
  private var session: URLSession?
  private var task: URLSessionDataTask?
  private var handle: FileHandle?
  private var count: Int64 = 0
  private var expected: Int64 = -1
  private var finished = false
  private var hook: UUID?
  private let queue = OperationQueue()
  init(
    url: URL, destination: URL, control: PipelineControl,
    continuation: CheckedContinuation<Void, Error>
  ) {
    self.url = url; self.destination = destination; self.control = control;
    self.continuation = continuation
    queue.maxConcurrentOperationCount = 1
  }
  func start() {
    do {
      try control.check()
      guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
        throw MediaJob.error("Cannot create download file.")
      }
      handle = try FileHandle(forWritingTo: destination)
      let configuration = URLSessionConfiguration.ephemeral
      configuration.timeoutIntervalForRequest = 30
      configuration.timeoutIntervalForResource = control.timeoutMs / 1000
      let session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
      self.session = session
      var request = URLRequest(url: url); request.timeoutInterval = 30
      let task = session.dataTask(with: request); self.task = task
      hook = control.onCancel { [weak self] in self?.task?.cancel() }
      control.emit(.downloading); task.resume()
    } catch { finish(error) }
  }
  func urlSession(
    _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    do {
      try control.check()
      guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
        throw MediaJob.error(
          "Media download failed: HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0).")
      }
      expected = response.expectedContentLength
      guard expected <= control.maxInputBytes else {
        throw MediaJob.error("Input byte budget exceeded.")
      }
      completionHandler(.allow)
    } catch { completionHandler(.cancel); finish(error) }
  }
  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    guard !finished else { return }
    do {
      try control.check()
      count += Int64(data.count)
      guard count <= control.maxInputBytes else {
        throw MediaJob.error("Input byte budget exceeded.")
      }
      let free =
        (try FileManager.default.attributesOfFileSystem(
          forPath: destination.deletingLastPathComponent().path)[.systemFreeSize] as? NSNumber)?
        .int64Value ?? 0
      guard free - Int64(data.count) >= control.minFreeDiskBytes else {
        throw MediaJob.error("Insufficient download disk space.")
      }
      try handle?.write(contentsOf: data)
      control.emit(.downloading, fraction: expected > 0 ? Double(count) / Double(expected) : nil)
    } catch { finish(error) }
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    do { try control.check(); finish(error) } catch { finish(error) }
  }
  private func finish(_ error: Error?) {
    guard !finished else { return }
    finished = true
    if let hook { control.removeHook(hook) }
    try? handle?.close(); handle = nil
    session?.invalidateAndCancel(); session = nil
    if let error {
      continuation.resume(throwing: error)
    } else if count == 0 {
      continuation.resume(throwing: MediaJob.error("Downloaded input is empty."))
    } else {
      continuation.resume()
    }
  }
}

enum BoundedDownload {
  static func download(url: URL, destination: URL, control: PipelineControl) async throws {
    try await withCheckedThrowingContinuation { continuation in
      DownloadReceiver(
        url: url, destination: destination, control: control, continuation: continuation
      ).start()
    }
  }
}
