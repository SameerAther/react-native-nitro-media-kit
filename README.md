# react-native-nitro-media-kit

High-performance image-and-video processing for React Native powered by [Nitro Modules](https://nitro.margelo.com/).

## ✨ Features

| Feature                | Android | iOS | Notes                                                                     |
| ---------------------- | ------- | --- | ------------------------------------------------------------------------- |
| `getMediaInfo`         | ✔︎       | ✔︎   | Reads basic metadata (duration, dimensions, type).                        |
| `convertImageToVideo`  | ✔︎       | ✔︎   | Turns any local file or remote URL into an H.264 MP4 (typically ~30 fps). |
| `mergeVideos`          | ✔︎       | ✔︎   | Concatenates an arbitrary list of MP4s, normalising size/FPS when needed. |
| `watermarkVideo`       | ✔︎       | ✔︎   | Places a text watermark in 4 corners or centre – remote URLs supported.   |
| Hardware-backed codecs | ✔︎       | ✔︎   | Uses MediaCodec / AVFoundation surfaces for efficient rendering.          |
| Remote-URL fallback    | ✔︎       | ✔︎   | Automatically downloads HTTP/HTTPS sources to a temp cache.               |
| Pause-free JS thread   | ✔︎       | ✔︎   | Heavy work happens in Kotlin/Swift on background queues.                  |

## 📦 Installation

```sh
# 1) Library + peer dependency
npm i react-native-nitro-media-kit react-native-nitro-modules

# 2) iOS pods
cd ios && pod install
```

`react-native-nitro-modules` is required (this library is a Nitro Module).

### Android requirements

- Android builds use CMake/NDK (via Nitro). Make sure your RN Android toolchain is set up with the NDK installed.

## 🏁 Quick-start

```ts
import {
  convertImageToVideo,
  mergeVideos,
  watermarkVideo,
} from 'react-native-nitro-media-kit';

// 1️⃣ Turn a PNG into a 5-second clip
const result1 = await convertImageToVideo('https://example.com/banner.png', 5);
if (!result1.ok) throw new Error(result1.error?.message ?? 'Convert failed');
const video1 = result1.outputUri!;

// 2️⃣ Add a watermark bottom-right
const result2 = await watermarkVideo(
  video1,
  '© ACME Corp',
  'bottom-right' // top-left | top-right | bottom-left | bottom-right | center
);
if (!result2.ok) throw new Error(result2.error?.message ?? 'Watermark failed');
const watermarked = result2.outputUri!;

// 3️⃣ Merge with an existing clip
const result3 = await mergeVideos([
  watermarked,
  '/storage/emulated/0/DCIM/clip.mp4',
]);
if (!result3.ok) throw new Error(result3.error?.message ?? 'Merge failed');
const final = result3.outputUri!;

console.log('Done! ->', final);
```

## 🧾 Return Type

All methods return the same shape: `Promise<MediaInfoResult>`.

```ts
export type MediaInfoResult = {
  ok: boolean;

  /** What operation produced this result */
  operation: 'getMediaInfo' | 'convert' | 'watermark' | 'trim' | 'merge';

  /** Media type */
  type: 'image' | 'video';

  /** Input media */
  inputUri?: string;

  /** Output media (if generated) */
  outputUri?: string;

  /** Core media info (images will only provide width/height) */
  media?: {
    durationMs?: number; // video only
    width?: number;
    height?: number;
    fps?: number; // video only
  };

  /** Error info when ok = false */
  error?: {
    code: string;
    message: string;
  };
};
```

Notes:

- For **images**, `durationMs` and `fps` are **omitted** (left `undefined`) — only `width`/`height` are returned.
- For operations that generate files (`convert`, `watermark`, `merge`), `outputUri` contains the created file path.

## 🗂️ API Reference

### `getMediaInfo(inputUri: string): Promise<MediaInfoResult>`

Reads metadata for images or videos and returns a `MediaInfoResult`.

| Param      | Type     | Description                                         |
| ---------- | -------- | --------------------------------------------------- |
| `inputUri` | `string` | Local absolute path or remote URL (`http`/`https`). |

Returns: `Promise<MediaInfoResult>`.

### `convertImageToVideo(image: string, duration: number): Promise<MediaInfoResult>`

| Param      | Type     | Description                                         |
| ---------- | -------- | --------------------------------------------------- |
| `image`    | `string` | Local absolute path or remote URL (`http`/`https`). |
| `duration` | `number` | Length of the generated video in seconds.           |

Returns: `Promise<MediaInfoResult>` — `outputUri` holds the absolute path to the newly created MP4.

Under the hood the image is resized to the nearest supported H.264 resolution while preserving aspect ratio.

### `mergeVideos(videos: string[]): Promise<MediaInfoResult>`

Concatenates multiple MP4s into a single file. Videos with mismatched width/height/fps are re-encoded transparently.

| Param    | Type       | Description                   |
| -------- | ---------- | ----------------------------- |
| `videos` | `string[]` | Array of local paths or URLs. |

Returns: `Promise<MediaInfoResult>` — `outputUri` holds the path to the merged file.

### `watermarkVideo(video: string, watermark: string, position: string): Promise<MediaInfoResult>`

Adds a text watermark to any corner (or centre).

| Param       | Type     | Description                                                              |
| ----------- | -------- | ------------------------------------------------------------------------ |
| `video`     | `string` | Source MP4 path or URL.                                                  |
| `watermark` | `string` | The text to render.                                                      |
| `position`  | `string` | One of `top-left`, `top-right`, `bottom-left`, `bottom-right`, `center`. |

Returns: `Promise<MediaInfoResult>` — `outputUri` holds the path to the water-marked file.

## ⚠️ Troubleshooting

| Symptom                                           | Fix                                                                                    |
| ------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `IllegalArgumentException: No video track found…` | Ensure the input is an MP4 (H.264). Other containers aren’t supported yet.             |
| Black video on Android                            | Some hardware codecs dislike odd dimensions. Prefer even-number sizes (e.g. 1280×720). |
| iOS export stuck at 0%                            | Check free disk space — AVFoundation can fail when temp space is low.                  |

## 🗺️ Roadmap

- Arbitrary X/Y watermark coordinates.
- Font size / style props for `watermarkVideo`.
- Contributions & PRs welcome!

## 🤝 Contributing

We follow the Nitro Modules contributing guide. Run examples locally with `yarn example ios` or `yarn example android`.

## 📄 License

MIT © 2025 — Sameer Ather

Made with ❤️ by Sameer Ather & create-react-native-library.
