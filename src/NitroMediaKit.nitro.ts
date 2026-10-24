import type { HybridObject } from 'react-native-nitro-modules';

export type MediaType = 'image' | 'video' | 'audio';

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
  /** Keyframe mode expands the range to complete GOPs and reports the actual range. */
  trimMode?: TrimMode;
  copyPolicy?: CopyPolicy;
  geometry?: VideoGeometry;
  overlays?: VideoOverlay[];
  subtitles?: SubtitleOptions;
  audioEdit?: AudioEditOptions;
  /** Constant playback rate, [0.25, 4]. Audio pitch is preserved. */
  speed?: number;
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
  actualTrim?: VideoSegment;
  diagnostics?: ExportDiagnostics;
};

export interface NitroMediaKit extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  openMedia(uri: string, limits?: ProcessingLimits): Promise<MediaAsset>;
  getCapabilities(): Promise<MediaCapabilities>;
  analyzeAudio(
    uri: string,
    options: AudioAnalysisOptions
  ): Promise<LoudnessAnalysis>;
  normalizeAudio(
    uri: string,
    options: NormalizeAudioOptions
  ): Promise<NormalizedAudioResult>;
  planExport(uri: string, options: ExportVideoOptions): Promise<ExportPlan>;
  createExport(
    uri: string,
    options: ExportVideoOptions,
    onProgress?: (progress: ExportProgress) => void
  ): ExportJob;
  createTimeline(
    clips: TimelineClip[],
    options: ExportVideoOptions,
    onProgress?: (progress: ExportProgress) => void
  ): ExportJob;
  extractThumbnails(
    uri: string,
    options: ThumbnailBatchOptions
  ): Promise<ThumbnailBatchResult>;
  extractAudio(
    uri: string,
    options: ExtractAudioOptions
  ): Promise<AudioExportResult>;
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

export type TrimMode = 'accurate' | 'keyframe';
export type CopyPolicy = 'auto' | 'require-copy' | 'encode';
export type JobState =
  'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
export type CapabilitySupport = 'supported' | 'unsupported' | 'unknown';

/** Coordinates use oriented source pixels, top-left origin. Order: crop, clockwise rotation, flip, canvas. */
export type CropRect = { x: number; y: number; width: number; height: number };
export type VideoGeometry = {
  crop?: CropRect;
  rotation?: number;
  flipHorizontal?: boolean;
  flipVertical?: boolean;
  /** Both dimensions required. Even integers, [2, 8192]. Default: cropped/rotated source size. */
  width?: number;
  height?: number;
  fit?: GeometryFit;
};

/** Output pixel coordinates, top-left origin; times are on the final output timeline. Array order is z-order. */
export type VideoOverlay = {
  kind: OverlayKind;
  uri?: string;
  text?: string;
  x: number;
  y: number;
  width: number;
  height: number;
  opacity?: number;
  startMs?: number;
  endMs?: number;
  fadeInMs?: number;
  fadeOutMs?: number;
  fontSize?: number;
  color?: string;
};
export type SubtitleOptions = {
  /** UTF-8 SRT or WebVTT content. Plain text cues; markup is stripped. */
  content: string;
  format: SubtitleFormat;
  fontSize?: number;
  color?: string;
  margin?: number;
};
export type AudioEditOptions = {
  /** Zero-based index among input audio tracks. */
  trackIndex?: number;
  /** Linear gain in [0, 1]. */
  volume?: number;
  fadeInMs?: number;
  fadeOutMs?: number;
  /** Mix this track over the output timeline; use source volume 0 to replace audio. */
  musicUri?: string;
  /** Linear gain in [0, 1]. Default 1. */
  musicVolume?: number;
  musicStartMs?: number;
  musicTrim?: VideoSegment;
};
export type TimelineClip = {
  uri: string;
  trim?: VideoSegment;
  speed?: number;
  geometry?: VideoGeometry;
  /** Dissolve into the following clip. Must fit both clips; no triple overlaps. */
  transitionMs?: number;
};
export type CodecCapability = {
  codec: VideoCodec;
  encode: CapabilitySupport;
  hardware: CapabilitySupport;
  hdr: CapabilitySupport;
  encoderNames: string[];
  maxWidth?: number;
  maxHeight?: number;
};
export type MediaCapabilities = {
  /** Operating system name: ios or android. String avoids the Android C++ ANDROID macro. */
  platform: string;
  codecs: CodecCapability[];
  /** Capability discovery is advisory; resources may change before export. */
  advisory: boolean;
};
export type ExportPlan = {
  width: number;
  height: number;
  fps: number;
  durationMs: number;
  codec: VideoCodec;
  colorPolicy: ColorPolicy;
  videoPath: VideoProcessingPath;
  estimatedSizeBytes: number;
  reasons: string[];
  warnings: MediaInfoWarning[];
};
export type VideoExportResult = {
  outputUri: string;
  media: MediaInfoMedia;
  warnings: MediaInfoWarning[];
  diagnostics?: ExportDiagnostics;
  actualTrim?: VideoSegment;
};
export type ThumbnailBatchOptions = {
  timesMs: number[];
  maxLongEdge?: number;
  quality?: number;
  precision?: ThumbnailPrecision;
  /** Also generate one bounded JPEG sprite. All thumbnails have equal cell dimensions. */
  storyboardColumns?: number;
};
export type ThumbnailResult = {
  outputUri: string;
  requestedTimeMs: number;
  actualTimeMs: number;
  width: number;
  height: number;
};
export type StoryboardCell = {
  index: number;
  x: number;
  y: number;
  width: number;
  height: number;
  timeMs: number;
};
export type StoryboardResult = {
  outputUri: string;
  width: number;
  height: number;
  cells: StoryboardCell[];
};
export type ThumbnailBatchResult = {
  thumbnails: ThumbnailResult[];
  storyboard?: StoryboardResult;
};
export type ExtractAudioOptions = {
  trackIndex?: number;
  trim?: VideoSegment;
  bitrateKbps?: number;
};
export type AudioExportResult = {
  outputUri: string;
  durationMs: number;
  sizeBytes: number;
  channels: number;
  sampleRate: number;
  codec: string;
};

/** Native-owned task. Completion settles once; dispose cancels unfinished work and detaches listeners. */
export interface ExportJob extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  readonly id: string;
  readonly state: JobState;
  result(): Promise<VideoExportResult>;
  cancel(): boolean;
  setProgressListener(listener?: (progress: ExportProgress) => void): void;
}

/** Metadata and resolved input are reused. Disposing prevents new work; active jobs retain their leases. */
export interface MediaAsset extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  readonly uri: string;
  readonly type: MediaType;
  readonly info: MediaInfoMedia;
  analyzeAudio(options: AudioAnalysisOptions): Promise<LoudnessAnalysis>;
  normalizeAudio(
    options: NormalizeAudioOptions
  ): Promise<NormalizedAudioResult>;
  planExport(options: ExportVideoOptions): Promise<ExportPlan>;
  createExport(
    options: ExportVideoOptions,
    onProgress?: (progress: ExportProgress) => void
  ): ExportJob;
  extractThumbnails(
    options: ThumbnailBatchOptions
  ): Promise<ThumbnailBatchResult>;
  extractAudio(options: ExtractAudioOptions): Promise<AudioExportResult>;
}

export type GeometryFit = 'contain' | 'cover';
export type OverlayKind = 'image' | 'text';
export type SubtitleFormat = 'srt' | 'vtt';

/** Analysis/normalization supports mono/stereo; normalize other layouts to stereo explicitly first. */
export type AudioAnalysisOptions = {
  trackIndex?: number;
  trim?: VideoSegment;
  limits?: ProcessingLimits;
};
export type NormalizeAudioOptions = {
  trackIndex?: number;
  trim?: VideoSegment;
  limits?: ProcessingLimits;
  targetLufs?: number;
  maxTruePeakDbtp?: number;
  bitrateKbps?: number;
};
export type LoudnessAnalysis = {
  integratedLufs?: number;
  samplePeakDbfs?: number;
  truePeakDbtp?: number;
  durationMs: number;
  channels: number;
  sampleRate: number;
};
export type NormalizedAudioResult = {
  audio: AudioExportResult;
  before: LoudnessAnalysis;
  after: LoudnessAnalysis;
  appliedGainDb: number;
  limitedByPeak: boolean;
};
