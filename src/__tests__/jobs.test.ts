import { NitroMediaKit } from '../native';
import { createVideoJob, exportVideo } from '../jobs';
import type {
  ExportProgress,
  ExportVideoOptions,
} from '../NitroMediaKit.nitro';

jest.mock('../native', () => ({
  NitroMediaKit: {
    exportVideo: jest.fn(() =>
      Promise.resolve({ ok: true, operation: 'exportVideo', type: 'video' })
    ),
    cancelJob: jest.fn(() => Promise.resolve(true)),
  },
}));

beforeEach(() => jest.clearAllMocks());

test('starts one native export with a snapshot and forwards the progress callback', async () => {
  const options: ExportVideoOptions = {
    trim: { startMs: 0, endMs: 2000 },
    watermark: 'Brand',
    watermarkOptions: { opacity: 0.5 },
    video: { maxFps: 23.976, frameRatePolicy: 'cap', colorPolicy: 'preserve' },
    limits: { timeoutMs: 30000 },
  };
  const progress = jest.fn<void, [ExportProgress]>();
  const job = createVideoJob('/input.mp4', options, progress);
  options.video!.maxFps = 60;
  options.trim!.endMs = 500;
  options.limits!.timeoutMs = 1;
  expect(NitroMediaKit.exportVideo).toHaveBeenCalledTimes(1);
  expect(NitroMediaKit.exportVideo).toHaveBeenCalledWith(
    '/input.mp4',
    expect.objectContaining({
      trim: { startMs: 0, endMs: 2000 },
      video: expect.objectContaining({ maxFps: 23.976 }),
      limits: { timeoutMs: 30000 },
    }),
    job.id,
    progress
  );
  expect(Object.isFrozen(job)).toBe(true);
  expect((await job.result).ok).toBe(true);
  await expect(job.cancel()).resolves.toBe(true);
  expect(NitroMediaKit.cancelJob).toHaveBeenCalledWith(job.id);
});

test('concurrent jobs receive distinct IDs and exportVideo returns the same native result', async () => {
  const first = createVideoJob('/a.mp4');
  const second = createVideoJob('/a.mp4');
  expect(first.id).not.toBe(second.id);
  await expect(exportVideo('/a.mp4')).resolves.toMatchObject({
    ok: true,
    operation: 'exportVideo',
  });
  expect(NitroMediaKit.exportVideo).toHaveBeenCalledTimes(3);
});

test('native cancellation and initialization failures remain observable', async () => {
  jest
    .mocked(NitroMediaKit.exportVideo)
    .mockRejectedValueOnce(new Error('CANCELLED'));
  await expect(createVideoJob('/a.mp4').result).rejects.toThrow('CANCELLED');
});
