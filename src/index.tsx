import { NitroModules } from 'react-native-nitro-modules';
import type { NitroMediaKit as NitroMediaKitObject } from './NitroMediaKit.nitro';

export type {
  MediaInfoResult,
  MediaInfoWarning,
  VideoSegment,
} from './NitroMediaKit.nitro';

export const NitroMediaKit =
  NitroModules.createHybridObject<NitroMediaKitObject>('NitroMediaKit');

export const getMediaInfo: NitroMediaKitObject['getMediaInfo'] = (inputUri) =>
  NitroMediaKit.getMediaInfo(inputUri);

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
  position
) => NitroMediaKit.watermarkVideo(video, watermark, position);
