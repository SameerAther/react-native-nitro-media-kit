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

/** Starts immediately; native ownership covers cancellation even before processing begins. */
export function createVideoJob(
  uri: string,
  options: ExportVideoOptions = {},
  onProgress?: (progress: ExportProgress) => void
): VideoJob {
  // Snapshot values before crossing the asynchronous native boundary.
  const snapshot: ExportVideoOptions = {
    ...options,
    trim: options.trim && { ...options.trim },
    geometry: options.geometry && {
      ...options.geometry,
      crop: options.geometry.crop && { ...options.geometry.crop },
    },
    overlays: options.overlays?.map((overlay) => ({ ...overlay })),
    subtitles: options.subtitles && { ...options.subtitles },
    audioEdit: options.audioEdit && {
      ...options.audioEdit,
      musicTrim: options.audioEdit.musicTrim && {
        ...options.audioEdit.musicTrim,
      },
    },
    video: options.video && { ...options.video },
    limits: options.limits && { ...options.limits },
    watermarkOptions: options.watermarkOptions && {
      ...options.watermarkOptions,
    },
  };
  const nativeJob = NitroMediaKit.createExport(uri, snapshot, onProgress);
  const id = nativeJob.id;
  // Compatibility API retains the v0.5 result shape. New callers can use createExport directly.
  const result: Promise<MediaInfoResult> = nativeJob.result().then(
    (value) => ({
      ...value,
      ok: true,
      operation: 'exportVideo',
      type: 'video',
      inputUri: uri,
    }),
    (error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      const code = /\[([A-Z_]+)\]/.exec(message)?.[1] ?? 'EXPORT_FAILED';
      return {
        ok: false,
        operation: 'exportVideo',
        type: 'video',
        inputUri: uri,
        error: { code, message },
      };
    }
  );
  return Object.freeze({
    id,
    result,
    cancel: () => Promise.resolve(nativeJob.cancel()),
  });
}

export function exportVideo(
  uri: string,
  options: ExportVideoOptions = {},
  onProgress?: (progress: ExportProgress) => void
): Promise<MediaInfoResult> {
  return createVideoJob(uri, options, onProgress).result;
}
