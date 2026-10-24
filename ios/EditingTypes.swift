import Foundation

extension ExportVideoOptions {
  init(
    trim: VideoSegment? = nil, watermark: String? = nil, watermarkOptions: WatermarkOptions? = nil,
    preset: CompressionPreset? = nil, video: CompressVideoOptions? = nil, audio: AudioPolicy? = nil,
    preserveMetadata: Bool? = nil, limits: ProcessingLimits? = nil
  ) {
    self.init(
      trim: trim, trimMode: nil, copyPolicy: nil, geometry: nil, overlays: nil, subtitles: nil,
      audioEdit: nil, speed: nil, watermark: watermark, watermarkOptions: watermarkOptions,
      preset: preset, video: video, audio: audio, preserveMetadata: preserveMetadata, limits: limits
    )
  }
  func editing(trim: VideoSegment?, speed: Double? = nil, geometry: VideoGeometry? = nil)
    -> ExportVideoOptions
  {
    ExportVideoOptions(
      trim: trim, trimMode: trimMode, copyPolicy: copyPolicy, geometry: geometry ?? self.geometry,
      overlays: overlays, subtitles: subtitles, audioEdit: audioEdit, speed: speed ?? self.speed,
      watermark: watermark, watermarkOptions: watermarkOptions, preset: preset, video: video,
      audio: audio,
      preserveMetadata: preserveMetadata, limits: limits)
  }
}
