import type { HybridObject } from 'react-native-nitro-modules';

export type MediaType = 'image' | 'video';

export type OperationType =
  | 'getMediaInfo'
  | 'convertImageToVideo'
  | 'watermarkVideo'
  | 'mergeVideos';

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

  media?: MediaInfoMedia;

  warnings?: MediaInfoWarning[];

  error?: MediaInfoError;
};

export interface NitroMediaKit extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  getMediaInfo(inputUri: string): Promise<MediaInfoResult>;
  convertImageToVideo(
    image: string,
    duration: number
  ): Promise<MediaInfoResult>;
  mergeVideos(videos: string[]): Promise<MediaInfoResult>;
  watermarkVideo(
    video: string,
    watermark: string,
    position: string
  ): Promise<MediaInfoResult>;
}
