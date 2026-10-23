// Host-test value types mirror the generated Nitro boundary. Production builds compile against Nitrogen's types.
import Foundation

enum CompressionPreset { case preserve, auto, high, balanced, small }
enum ColorPolicy { case preserve, sdr, rejectHdr }
enum VideoCodec { case auto, h264, hevc }
enum FrameRatePolicy { case preserve, cap, constant }
enum AudioPolicy { case preserve, stereo, mute }
enum ExportStage { case queued, downloading, processing, validating, completed }
struct ExportProgress { let jobId: String; let stage: ExportStage; let fraction: Double? }
struct ProcessingLimits {
    var timeoutMs: Double? = nil
    var maxInputBytes: Double? = nil
    var maxOutputBytes: Double? = nil
    var minFreeDiskBytes: Double? = nil
}
struct VideoSegment { let startMs: Double; let endMs: Double }
struct CompressVideoOptions {
    var maxLongEdge: Double? = nil
    var maxFps: Double? = nil
    var videoBitrateKbps: Double? = nil
    var audioBitrateKbps: Double? = nil
    var keepAudio: Bool? = nil
    var codec: VideoCodec? = nil
    var colorPolicy: ColorPolicy? = nil
    var frameRatePolicy: FrameRatePolicy? = nil
    var targetSizeBytes: Double? = nil
}
struct ExportVideoOptions {
    var trim: VideoSegment? = nil
    var watermark: String? = nil
    var preset: CompressionPreset? = nil
    var video: CompressVideoOptions? = nil
    var audio: AudioPolicy? = nil
}
enum OperationType { case exportvideo }
enum MediaType { case video, image }
struct MediaInfoMedia {}
struct MediaInfoWarning {}
struct MediaInfoError {}
struct ExportDiagnostics {}
struct MediaInfoResult {
    var ok: Bool
    var operation: OperationType = .exportvideo
    var type: MediaType = .video
    var inputUri: String? = nil
    var outputUri: String? = nil
    var segments: [String]? = nil
    var media: MediaInfoMedia? = nil
    var warnings: [MediaInfoWarning]? = nil
    var error: MediaInfoError? = nil
    var actualTimeMs: Double? = nil
    var diagnostics: ExportDiagnostics? = nil
}
