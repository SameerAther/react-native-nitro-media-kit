import type { HybridObject } from 'react-native-nitro-modules';

export type MediaType = 'image' | 'video';

export type OperationType =
  | 'getMediaInfo'
  | 'convertImageToVideo'
  | 'watermarkVideo'
  | 'mergeVideos'
  | 'splitVideo';

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

export interface NitroMediaKit
  extends HybridObject<{
    ios: 'swift';
    android: 'kotlin';
  }> {
  getMediaInfo(inputUri: string): Promise<MediaInfoResult>;
  convertImageToVideo(
    image: string,
    duration: number
  ): Promise<MediaInfoResult>;
  mergeVideos(videos: string[]): Promise<MediaInfoResult>;
  splitVideo(video: string, segments: VideoSegment[]): Promise<MediaInfoResult>;
  watermarkVideo(
    video: string,
    watermark: string,
    position: string
  ): Promise<MediaInfoResult>;
}
