import { NitroModules } from 'react-native-nitro-modules';
import {
  NitroMediaKit as mockNative,
  watermarkVideo,
  deleteGeneratedFile,
} from '../index';

jest.mock('react-native-nitro-modules', () => ({
  NitroModules: {
    createHybridObject: jest.fn(() => ({
      watermarkVideo: jest.fn(function (this: unknown) {
        if (this !== mockNative) {
          throw new Error('HybridObject method lost its receiver');
        }
        return Promise.resolve({
          ok: false,
          operation: 'watermarkVideo',
          type: 'video',
          error: { code: 'INVALID_MEDIA', message: 'Cannot decode video' },
        });
      }),
      deleteGeneratedFile: jest.fn(function (this: unknown) {
        if (this !== mockNative) {
          throw new Error('HybridObject method lost its receiver');
        }
        return Promise.reject(new Error('Only generated files can be deleted'));
      }),
    })),
  },
}));

test('creates a single native instance for shared codec scheduling', () => {
  expect(NitroModules.createHybridObject).toHaveBeenCalledTimes(1);
  expect(NitroModules.createHybridObject).toHaveBeenCalledWith('NitroMediaKit');
});

test('a detached public function retains the HybridObject receiver and native failure result', async () => {
  const call = watermarkVideo;
  const options = Object.freeze({
    position: 'top-left' as const,
    opacity: 0.5,
  });
  const result = await call('file:///video.mp4', 'Brand', options);
  expect(result).toEqual({
    ok: false,
    operation: 'watermarkVideo',
    type: 'video',
    error: { code: 'INVALID_MEDIA', message: 'Cannot decode video' },
  });
  expect(options).toEqual({ position: 'top-left', opacity: 0.5 });
});

test('cleanup permission errors remain promise rejections', async () => {
  await expect(deleteGeneratedFile('/caller-owned.mp4')).rejects.toThrow(
    'Only generated files can be deleted'
  );
});
