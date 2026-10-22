import type { HybridObject } from 'react-native-nitro-modules';

export type MediaType = 'image' | 'video';

export type OperationType =
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
};

export type CompressionPreset = 'auto' | 'high' | 'balanced' | 'small';
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
};

export type CompressMediaOptions = {
  preset?: CompressionPreset;
  preserveMetadata?: boolean;
  image?: CompressImageOptions;
  video?: CompressVideoOptions;
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
};

export interface NitroMediaKit extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
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
