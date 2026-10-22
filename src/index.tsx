import { NitroModules } from 'react-native-nitro-modules';
import type { NitroMediaKit as NitroMediaKitObject } from './NitroMediaKit.nitro';

export type {
  CompressImageFormat,
  CompressImageOptions,
  CompressionPreset,
  CompressMediaOptions,
  CompressVideoOptions,
  MediaInfoResult,
  MediaInfoWarning,
  MediaInfoError,
  MediaInfoMedia,
  MediaType,
  OperationType,
  ThumbnailOptions,
  WatermarkOptions,
  WatermarkPosition,
  VideoSegment,
} from './NitroMediaKit.nitro';

export const NitroMediaKit =
  NitroModules.createHybridObject<NitroMediaKitObject>('NitroMediaKit');

export const getMediaInfo: NitroMediaKitObject['getMediaInfo'] = (inputUri) =>
  NitroMediaKit.getMediaInfo(inputUri);

export const compressMedia: NitroMediaKitObject['compressMedia'] = (
  inputUri,
  options
) => NitroMediaKit.compressMedia(inputUri, options);

export const convertImageToVideo: NitroMediaKitObject['convertImageToVideo'] = (
  image,
  duration
) => NitroMediaKit.convertImageToVideo(image, duration);

export const mergeVideos: NitroMediaKitObject['mergeVideos'] = (videos) =>
  NitroMediaKit.mergeVideos(videos);

export const splitVideo: NitroMediaKitObject['splitVideo'] = (
  video,
  segments
) => NitroMediaKit.splitVideo(video, segments);

export const watermarkVideo: NitroMediaKitObject['watermarkVideo'] = (
  video,
  watermark,
  options
) => NitroMediaKit.watermarkVideo(video, watermark, options);

export const trimVideo: NitroMediaKitObject['trimVideo'] = (video, segment) =>
  NitroMediaKit.trimVideo(video, segment);

export const removeAudio: NitroMediaKitObject['removeAudio'] = (video) =>
  NitroMediaKit.removeAudio(video);

export const extractThumbnail: NitroMediaKitObject['extractThumbnail'] = (
  video,
  options
) => NitroMediaKit.extractThumbnail(video, options);

export const deleteGeneratedFile: NitroMediaKitObject['deleteGeneratedFile'] = (
  uri
) => NitroMediaKit.deleteGeneratedFile(uri);
