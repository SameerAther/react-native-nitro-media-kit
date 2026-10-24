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
  var trimMode: TrimMode? = nil
  var copyPolicy: CopyPolicy? = nil
  var geometry: VideoGeometry? = nil
  var overlays: [VideoOverlay]? = nil
  var subtitles: SubtitleOptions? = nil
  var audioEdit: AudioEditOptions? = nil
  var speed: Double? = nil
  var watermark: String? = nil
  var watermarkOptions: WatermarkOptions? = nil
  var preset: CompressionPreset? = nil
  var video: CompressVideoOptions? = nil
  var audio: AudioPolicy? = nil
}
enum OperationType { case exportvideo }
enum MediaType { case video, image, audio }
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
  var actualTrim: VideoSegment? = nil
  var diagnostics: ExportDiagnostics? = nil
}

enum TrimMode { case accurate, keyframe }
enum CopyPolicy { case auto, requireCopy, encode }
enum GeometryFit { case contain, cover }
enum OverlayKind { case image, text }
enum SubtitleFormat { case srt, vtt }
struct CropRect { let x: Double, y: Double, width: Double, height: Double }
struct VideoGeometry {
  var crop: CropRect? = nil; var rotation: Double? = nil; var flipHorizontal: Bool? = nil;
  var flipVertical: Bool? = nil; var width: Double? = nil; var height: Double? = nil;
  var fit: GeometryFit? = nil
}
struct VideoOverlay {
  let kind: OverlayKind; var uri: String? = nil; var text: String? = nil;
  let x: Double, y: Double, width: Double, height: Double; var opacity: Double? = nil;
  var startMs: Double? = nil; var endMs: Double? = nil; var fadeInMs: Double? = nil;
  var fadeOutMs: Double? = nil; var fontSize: Double? = nil; var color: String? = nil
}
struct SubtitleOptions {
  let content: String; let format: SubtitleFormat; var fontSize: Double? = nil;
  var color: String? = nil; var margin: Double? = nil
}
struct AudioEditOptions {
  var trackIndex: Double? = nil; var volume: Double? = nil; var fadeInMs: Double? = nil;
  var fadeOutMs: Double? = nil; var musicUri: String? = nil; var musicVolume: Double? = nil;
  var musicStartMs: Double? = nil; var musicTrim: VideoSegment? = nil
}

struct WatermarkOptions {
  var fontSize: Double? = nil; var color: String? = nil; var opacity: Double? = nil;
  var margin: Double? = nil
}
