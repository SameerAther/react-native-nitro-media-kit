# react-native-nitro-media-kit

Native image and video processing for React Native, powered by [Nitro Modules](https://nitro.margelo.com/). Android exports use Media3 Transformer; iOS uses AVFoundation and Core Image. Media stays in native memory and files; JavaScript receives paths and metadata.

## Install

```sh
npm install react-native-nitro-media-kit react-native-nitro-modules@0.37.1
cd ios && pod install
```

Requires React Native 0.77+, Nitro Modules `>=0.37.1 <0.38.0`, iOS 15.1+ (or your React Native version's higher minimum), and Android API 24+. Android needs compile SDK 36+, JDK 17 and the React Native NDK/CMake toolchain. Rebuild the native app after installation or upgrading. This package does not run in Expo Go.

## Use

```ts
import {
  compressMedia,
  watermarkVideo,
  extractThumbnail,
  trimVideo,
  removeAudio,
  deleteGeneratedFile,
} from 'react-native-nitro-media-kit';

const marked = await watermarkVideo('/path/to/video.mp4', '© My brand', {
  position: 'bottom-right',
  color: '#FFFFFFCC',
  margin: 24,
});
if (!marked.ok || !marked.outputUri) {
  throw new Error(marked.error?.message ?? 'Watermark failed');
}

const preview = await extractThumbnail(marked.outputUri, {
  timeMs: 500,
  maxLongEdge: 1280,
  quality: 0.9,
});
const clip = await trimVideo(marked.outputUri, { startMs: 0, endMs: 1000 });
const muted = await removeAudio(marked.outputUri);
const smaller = await compressMedia(marked.outputUri, {
  preset: 'balanced',
  video: { maxLongEdge: 1280, maxFps: 30 },
});

// Copy outputs into your own storage if they must persist.
await deleteGeneratedFile(marked.outputUri);
```

Inputs accept absolute paths, percent-encoded `file://` URIs, and HTTP(S) URLs. Android also accepts readable `content://` URIs. Grant content-provider access before calling. Remote inputs stream to temporary disk storage with connection/read timeouts; temporary inputs are deleted when their operation ends. Repeated URLs within a job share the same download.

All processing methods resolve to `MediaInfoResult`. Check `ok` before using outputs. Invalid arguments/media produce `ok: false` with an error. Nitro argument conversion or native initialization can also throw/reject. `deleteGeneratedFile` rejects invalid deletion requests.

```ts
type MediaInfoResult = {
  ok: boolean;
  operation: OperationType;
  type: 'image' | 'video';
  inputUri?: string;
  outputUri?: string;
  segments?: string[];
  media?: {
    durationMs?: number;
    width?: number;
    height?: number;
    fps?: number;
    format?: string;
    sizeBytes?: number;
    audioTracks?: number;
    videoTracks?: number;
  };
  warnings?: { code: string; message: string }[];
  error?: { code: string; message: string };
};
```

Dimensions describe displayed pixels after orientation. Image results omit duration/FPS. Output metadata is inspected after export completes. Multi-output split results contain `segments` in request order.

## API

| Function                                    | Behavior                                                                                                                                                                                 |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getMediaInfo(uri)`                         | Reads image/video metadata without decoding full-size images.                                                                                                                            |
| `compressMedia(uri, options?)`              | Detects input type and applies an image/video compression plan.                                                                                                                          |
| `convertImageToVideo(uri, durationSeconds)` | Creates a silent H.264 MP4 at 30 FPS, capped at a 1920-pixel long edge. Duration must be finite and in `(0, 86400]`. Short/fractional durations round up to a whole frame.               |
| `watermarkVideo(uri, text, options?)`       | Renders text into actual video frames on devices and simulators, retaining primary audio.                                                                                                |
| `mergeVideos(uris)`                         | Concatenates a nonempty list; fits each clip to the first clip's aspect ratio, capped at a 1920-pixel long edge. Letterboxes different aspect ratios. Missing audio creates silent gaps. |
| `splitVideo(uri, segments)`                 | Exports one clip per range. All ranges are validated before exporting; a failure removes every partial output from that job.                                                             |
| `trimVideo(uri, segment)`                   | Exports a single requested range.                                                                                                                                                        |
| `removeAudio(uri)`                          | Exports video without audio. Uses passthrough when the native pipeline supports it.                                                                                                      |
| `extractThumbnail(uri, options?)`           | Creates an oriented JPEG preview.                                                                                                                                                        |
| `deleteGeneratedFile(uri)`                  | Deletes one generated output, returning `false` if already absent. Caller-owned inputs cannot be deleted.                                                                                |

Video output uses MP4. Re-encoding uses native codecs; capabilities and codec fallback depend on the device. Android exports target H.264/AAC and tone-map HDR to SDR. HDR/color fidelity and exact frame boundaries need validation with your source formats and target devices. This is a file-processing API; it does not maintain an editor timeline or expose cancellation/progress.

### Watermarks

```ts
type WatermarkOptions = {
  position?:
    'top-left' | 'top-right' | 'bottom-left' | 'bottom-right' | 'center';
  fontSize?: number; // output pixels, 1..512; default 5% of the shorter side
  color?: string; // #RRGGBB or #RRGGBBAA, default white
  opacity?: number; // 0..1, default 1; multiplied by color alpha
  margin?: number; // output pixels, default 2.5% of the shorter side
};
```

The default position is bottom-right. Text supports multiple lines, wraps within the available width, and shrinks if necessary to fit the video. Empty/whitespace-only text and text over 4096 UTF-16 code units are rejected. Margin must leave room on both axes. Font rendering can differ between Android and iOS.

### Compression

```ts
type CompressMediaOptions = {
  preset?: 'auto' | 'high' | 'balanced' | 'small'; // default auto
  preserveMetadata?: boolean; // default false
  image?: {
    format?: 'auto' | 'jpeg' | 'png'; // auto preserves transparency via PNG
    quality?: number; // 0..1; ignored for PNG, with a warning
    maxLongEdge?: number; // positive, <=8192; never enlarges the source
  };
  video?: {
    maxLongEdge?: number; // positive, <=8192
    maxFps?: number; // finite and positive; never increases source FPS
    videoBitrateKbps?: number;
    audioBitrateKbps?: number;
    keepAudio?: boolean; // default true
  };
};
```

`auto` adapts to source size/duration. `high`, `balanced`, and `small` trade resolution/bitrate for file size. Explicit settings override the preset within native limits. Compression does not guarantee a smaller file; inspect `warnings` and `media.sizeBytes`. PNG is lossless, so `quality` does not reduce PNG size. Metadata preservation is partial and platform-dependent; explicit preservation may retain EXIF/GPS. Orientation is applied to image pixels and normalized in output metadata.

### Ranges and thumbnails

```ts
type VideoSegment = { startMs: number; endMs: number };
type ThumbnailOptions = {
  timeMs?: number; // default 0; 0 <= timeMs < duration
  maxLongEdge?: number; // default 1280; 1..8192
  quality?: number; // default 0.9; 0..1
};
```

Ranges must satisfy `0 <= startMs < endMs <= durationMs` with finite numbers. Invalid ranges fail; they are not clamped or rounded to earlier keyframes. Native exporters can quantize cuts to media time/sample boundaries. Thumbnail selection uses the closest decodable frame.

### File lifetime and concurrency

Generated outputs live in the app's `nitro-media-kit/output` cache directory with unique UUID filenames. They survive subsequent calls, but the OS can evict cache files. Copy files to app-owned permanent storage before retaining/sharing them long-term. Use `deleteGeneratedFile` for prompt cleanup. Failed jobs remove partial outputs; successful split jobs keep all requested clips.

A native queue serializes jobs across HybridObject instances to bound memory use and hardware codec contention. File I/O, image decoding, and media processing stay off the JavaScript thread. Android Transformer control callbacks use its required application looper; media work runs in native worker threads.

## Migrating to 0.4

- Upgrade Nitro runtime and Nitrogen together to **0.37.1** and regenerate bindings before building.
- Replace `watermarkVideo(uri, text, 'bottom-right')` with `watermarkVideo(uri, text, { position: 'bottom-right' })`.
- Generated outputs now live in the library cache namespace. Persist important outputs explicitly.
- Invalid/out-of-bounds split ranges now fail instead of being silently clamped or snapped to sync frames.
- React Native peers now declare a real minimum version instead of `*`.
- Android uses Media3 Transformer instead of the custom EGL/MediaCodec pipeline.

## Development and regression checks

Use the Yarn version checked into this repository (`yarn@3.6.1`) and Node from `.nvmrc`.

The example Podfile includes a version-scoped module-map fix for React Native's `glog` 0.3.5 when building Swift/C++ interop from source with Xcode 26. It marks glog's nested includes as textual headers; upstream source files remain untouched.

```sh
yarn install --immutable
yarn nitrogen
yarn typecheck
yarn lint
yarn test --runInBand
yarn prepare

# Android: boot an emulator/device first; use JDK 17.
yarn test:native:android

# iOS: install pods from example/ios first.
yarn test:native:ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

Native regression tests use small synthetic fixtures under `test-fixtures/`, including mono AAC audio and a rotation-tagged portrait video. They verify real watermark pixels, audio retention, orientation, duration, trimming, compression, and protected input deletion. Run on physical devices as well as emulators before releasing; hardware encoders and fonts differ.

## License

MIT
