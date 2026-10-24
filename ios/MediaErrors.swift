import Foundation

enum MediaErrors {
  static func normalize(_ error: Error) -> NSError {
    let original = error as NSError
    var code = original.domain
    if error is CancellationError {
      code = "CANCELLED"
    } else if code == "NitroMediaKit" || code == "ValidationError" {
      code = "INVALID_ARGUMENT"
    } else if code == "UnsupportedMediaError" {
      code = "UNSUPPORTED_MEDIA"
    } else if code == NSCocoaErrorDomain || code == NSPOSIXErrorDomain {
      code = "IO_ERROR"
    } else if code == NSURLErrorDomain {
      code = "DOWNLOAD_FAILED"
    } else if code.range(of: "^[A-Z][A-Z_]+$", options: .regularExpression) == nil {
      code = "PROCESSING_FAILED"
    }
    let message = original.localizedDescription
    return NSError(
      domain: code, code: original.code,
      userInfo: [
        NSLocalizedDescriptionKey: message.hasPrefix("[\(code)]") ? message : "[\(code)] \(message)"
      ])
  }
}
