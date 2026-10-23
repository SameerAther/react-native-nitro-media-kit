import { NitroMediaKit } from './native';
import type {
  ExportProgress,
  ExportVideoOptions,
  MediaInfoResult,
} from './NitroMediaKit.nitro';

export type VideoJob = {
  readonly id: string;
  readonly result: Promise<MediaInfoResult>;
  cancel(): Promise<boolean>;
};

let sequence = 0;

/** Starts immediately; native ownership covers cancellation even before processing begins. */
export function createVideoJob(
  uri: string,
  options: ExportVideoOptions = {},
  onProgress?: (progress: ExportProgress) => void
): VideoJob {
  const id = `media-${Date.now()}-${++sequence}-${Math.random().toString(36).slice(2)}`;
  // Snapshot values before crossing the asynchronous native boundary.
  const snapshot: ExportVideoOptions = {
    ...options,
    trim: options.trim && { ...options.trim },
    video: options.video && { ...options.video },
    limits: options.limits && { ...options.limits },
    watermarkOptions: options.watermarkOptions && {
      ...options.watermarkOptions,
    },
  };
  const result = NitroMediaKit.exportVideo(uri, snapshot, id, onProgress);
  return Object.freeze({
    id,
    result,
    cancel: () => NitroMediaKit.cancelJob(id),
  });
}

export function exportVideo(
  uri: string,
  options: ExportVideoOptions = {},
  onProgress?: (progress: ExportProgress) => void
): Promise<MediaInfoResult> {
  return createVideoJob(uri, options, onProgress).result;
}
