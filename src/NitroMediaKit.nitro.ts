import type { HybridObject } from 'react-native-nitro-modules';

export type MediaType = 'image' | 'video';

export type OperationType =
  | 'exportVideo'
  | 'getMediaInfo'
  | 'compressMedia'
  | 'convertImageToVideo'
  | 'watermarkVideo'
  | 'mergeVideos'
  | 'splitVideo'
  | 'trimVideo'
  | 'removeAudio'
  | 'extractThumbnail';

export type WatermarkPosition =
  'top-left' | 'top-right' | 'bottom-left' | 'bottom-right' | 'center';

/** Sizes are in output pixels. Long text wraps inside the video bounds. */
export type WatermarkOptions = {
  position?: WatermarkPosition;
  fontSize?: number;
  /** #RRGGBB or #RRGGBBAA. Default: white. */
  color?: string;
  opacity?: number;
  margin?: number;
};

export type ThumbnailOptions = {
  timeMs?: number;
  maxLongEdge?: number;
  quality?: number;
  precision?: ThumbnailPrecision;
};

export type CompressionPreset =
  'preserve' | 'auto' | 'high' | 'balanced' | 'small';
export type CompressImageFormat = 'auto' | 'jpeg' | 'png';

export type CompressImageOptions = {
  format?: CompressImageFormat;
  quality?: number;
  maxLongEdge?: number;
};

export type CompressVideoOptions = {
  maxLongEdge?: number;
  maxFps?: number;
  videoBitrateKbps?: number;
  audioBitrateKbps?: number;
  keepAudio?: boolean;
  codec?: VideoCodec;
  colorPolicy?: ColorPolicy;
  frameRatePolicy?: FrameRatePolicy;
  targetSizeBytes?: number;
};

export type CompressMediaOptions = {
  preset?: CompressionPreset;
  preserveMetadata?: boolean;
  image?: CompressImageOptions;
  video?: CompressVideoOptions;
};

export type ThumbnailPrecision = 'fast' | 'precise';
export type ExportStage =
  'queued' | 'downloading' | 'processing' | 'validating' | 'completed';
export type VideoProcessingPath = 'copied' | 'encoded' | 'mixed';
export type AudioProcessingPath = 'copied' | 'encoded' | 'absent';
export type VideoCodec = 'auto' | 'h264' | 'hevc';
export type ColorPolicy = 'preserve' | 'sdr' | 'reject-hdr';
export type FrameRatePolicy = 'preserve' | 'cap' | 'constant';
export type AudioPolicy = 'preserve' | 'stereo' | 'mute';

export type ProcessingLimits = {
  /** Whole job, including queueing and finalization. Default 30 minutes. */
  timeoutMs?: number;
  /** Default 2 GiB per input. */
  maxInputBytes?: number;
  /** Default 4 GiB per output. */
  maxOutputBytes?: number;
  /** Default 64 MiB free space reserve. */
  minFreeDiskBytes?: number;
};

export type ExportVideoOptions = {
  trim?: VideoSegment;
  watermark?: string;
  watermarkOptions?: WatermarkOptions;
  /** Default preserve. */
  preset?: CompressionPreset;
  video?: CompressVideoOptions;
  /** Preserve the primary audio track without unnecessary encoding. */
  audio?: AudioPolicy;
  preserveMetadata?: boolean;
  limits?: ProcessingLimits;
};

export type ExportProgress = {
  jobId: string;
  stage: ExportStage;
  /** Null-equivalent undefined when total work is unknown. */
  fraction?: number;
};

export type ExportDiagnostics = {
  elapsedMs: number;
  outputSizeBytes: number;
  /** Sampled process RSS, including other app work; GPU memory may be excluded. */
  peakProcessMemoryBytes?: number;
  videoPath: VideoProcessingPath;
  audioPath: AudioProcessingPath;
  codec?: string;
  estimatedSizeBytes?: number;
  frameCount?: number;
};

export type VideoSegment = {
  startMs: number;
  endMs: number;
};

export type MediaInfoWarning = {
  code: string;
  message: string;
};

export type MediaInfoError = {
  code: string;
  message: string;
};

export type MediaInfoMedia = {
  durationMs?: number;
  width?: number;
  height?: number;
  fps?: number;
  format?: string;
  sizeBytes?: number;
  audioTracks?: number;
  videoTracks?: number;
  codec?: string;
  hdr?: boolean;
  bitDepth?: number;
  bitrateKbps?: number;
  audioChannels?: number;
  audioSampleRate?: number;
};

export type MediaInfoResult = {
  ok: boolean;

  operation: OperationType;

  type: MediaType;

  inputUri?: string;
  outputUri?: string;
  segments?: string[];

  media?: MediaInfoMedia;

  warnings?: MediaInfoWarning[];

  error?: MediaInfoError;
  actualTimeMs?: number;
  diagnostics?: ExportDiagnostics;
};

export interface NitroMediaKit extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  exportVideo(
    video: string,
    options: ExportVideoOptions,
    jobId: string,
    onProgress?: (progress: ExportProgress) => void
  ): Promise<MediaInfoResult>;
  cancelJob(jobId: string): Promise<boolean>;
  getMediaInfo(inputUri: string): Promise<MediaInfoResult>;
  compressMedia(
    inputUri: string,
    options?: CompressMediaOptions
  ): Promise<MediaInfoResult>;
  convertImageToVideo(
    image: string,
    duration: number
  ): Promise<MediaInfoResult>;
  mergeVideos(videos: string[]): Promise<MediaInfoResult>;
  splitVideo(video: string, segments: VideoSegment[]): Promise<MediaInfoResult>;
  watermarkVideo(
    video: string,
    watermark: string,
    options?: WatermarkOptions
  ): Promise<MediaInfoResult>;
  trimVideo(video: string, segment: VideoSegment): Promise<MediaInfoResult>;
  removeAudio(video: string): Promise<MediaInfoResult>;
  extractThumbnail(
    video: string,
    options?: ThumbnailOptions
  ): Promise<MediaInfoResult>;
  /** Deletes only a file created by this library. Returns false if already absent. */
  deleteGeneratedFile(uri: string): Promise<boolean>;
}
