import { NitroMediaKit } from '../native';
import { createVideoJob, exportVideo } from '../jobs';
import type { ExportVideoOptions } from '../NitroMediaKit.nitro';

const mockCompletion = {
  outputUri: '/output.mp4',
  media: { width: 1920 },
  warnings: [],
};
const mockJob = {
  id: 'native-id',
  result: jest.fn(() => Promise.resolve(mockCompletion)),
  cancel: jest.fn(() => true),
};
jest.mock('../native', () => ({
  NitroMediaKit: { createExport: jest.fn(() => mockJob) },
}));
beforeEach(() => jest.clearAllMocks());

test('compatibility wrapper uses a native-owned job and snapshots nested edit options', async () => {
  const options: ExportVideoOptions = {
    geometry: { crop: { x: 0, y: 0, width: 100, height: 200 } },
    overlays: [
      { kind: 'image', uri: '/logo.png', x: 0, y: 0, width: 20, height: 20 },
    ],
    audioEdit: {
      musicUri: '/music.m4a',
      musicTrim: { startMs: 0, endMs: 1000 },
    },
  };
  const progress = jest.fn();
  const job = createVideoJob('/input.mp4', options, progress);
  options.geometry!.crop!.width = 50;
  options.overlays![0]!.width = 10;
  options.audioEdit!.musicTrim!.endMs = 500;
  expect(NitroMediaKit.createExport).toHaveBeenCalledWith(
    '/input.mp4',
    expect.objectContaining({
      geometry: { crop: { x: 0, y: 0, width: 100, height: 200 } },
      overlays: [expect.objectContaining({ width: 20 })],
      audioEdit: expect.objectContaining({
        musicTrim: { startMs: 0, endMs: 1000 },
      }),
    }),
    progress
  );
  expect(job.id).toBe('native-id');
  await expect(job.result).resolves.toMatchObject({
    ok: true,
    operation: 'exportVideo',
    outputUri: '/output.mp4',
  });
  await expect(job.cancel()).resolves.toBe(true);
  expect(mockJob.cancel).toHaveBeenCalledTimes(1);
});

test('legacy helper preserves terminal cancellation as a failure result', async () => {
  mockJob.result.mockRejectedValueOnce(
    new Error('[CANCELLED] Media job cancelled.')
  );
  await expect(exportVideo('/input.mp4')).resolves.toMatchObject({
    ok: false,
    error: { code: 'CANCELLED' },
  });
});

test('initialization failures remain synchronous and observable', () => {
  jest.mocked(NitroMediaKit.createExport).mockImplementationOnce(() => {
    throw new Error('QUEUE_FULL');
  });
  expect(() => createVideoJob('/input.mp4')).toThrow('QUEUE_FULL');
});
