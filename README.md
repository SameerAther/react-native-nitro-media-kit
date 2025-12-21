# react-native-nitro-media-kit

High-performance image-and-video processing for React Native powered by [Nitro Modules](https://nitro.margelo.com/).

⚠️ Status: Experimental — not production-ready yet

The API surface may change without notice, and certain edge-cases (see “Known issues” below) are still being debugged.

## ✨ Features

| Feature | Android | iOS | Notes |
| --- | --- | --- | --- |
| `convertImageToVideo` | ✔︎ | ✔︎ | Turns any local file or remote URL into an H.264 MP4 (30 fps). |
| `mergeVideos` | ✔︎ | ✔︎ | Concatenates an arbitrary list of MP4s, normalising size/FPS when needed. |
| `watermarkVideo` | ✔︎ | ✔︎ | Places a text watermark in 4 corners or centre – remote URLs supported. |
| Hardware-backed codecs | ✔︎ | ✔︎ | Uses MediaCodec / AVFoundation surfaces for zero-copy rendering. |
| Remote-URL fallback | ✔︎ | ✔︎ | Automatically downloads HTTP/HTTPS sources to a temp cache. |
| Pause-free JS thread | ✔︎ | ✔︎ | All heavy lifting happens in Kotlin/Swift on background queues. |

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
const video1 = await convertImageToVideo('https://example.com/banner.png', 5);

// 2️⃣ Add a watermark bottom-right
const watermarked = await watermarkVideo(
  video1,
  '© ACME Corp',
  'bottom-right' // top-left | top-right | bottom-left | bottom-right | center
);

// 3️⃣ Merge with an existing clip
const final = await mergeVideos([watermarked, '/storage/emulated/0/DCIM/clip.mp4']);

console.log('Done! ->', final);
```

## 🗂️ API Reference

### `convertImageToVideo(image: string, duration: number): Promise<string>`

| Param | Type | Description |
| --- | --- | --- |
| `image` | `string` | Local absolute path or remote URL (`http`/`https`). |
| `duration` | `number` | Length of the generated video in seconds. |

Returns: `Promise<string>` — Absolute path to the newly created MP4.

Under the hood the image is resized to the nearest supported H.264 resolution (1080p → 720p → 480p) while preserving aspect ratio.

### `mergeVideos(videos: string[]): Promise<string>`

Concatenates multiple MP4s into a single file. Videos with mismatched width/height/fps are re-encoded transparently.

| Param | Type | Description |
| --- | --- | --- |
| `videos` | `string[]` | Array of local paths or URLs. |

Returns: `Promise<string>` — Path to the merged file.

### `watermarkVideo(video: string, watermark: string, position: string): Promise<string>`

Adds a text watermark to any corner (or centre).

| Param | Type | Description |
| --- | --- | --- |
| `video` | `string` | Source MP4 path or URL. |
| `watermark` | `string` | The text to render. |
| `position` | `string` | One of `top-left`, `top-right`, `bottom-left`, `bottom-right`, `center`. |

Returns: `Promise<string>` — Path to the water-marked file.

## ⚠️ Troubleshooting

| Symptom | Fix |
| --- | --- |
| `IllegalArgumentException: No video track found…` | Ensure the input is an MP4 (H.264). Other containers aren’t supported yet. |
| Black video on Android | Your device’s hardware codec may dislike the resolution. Use even-number dimensions (e.g. 1280×720). |
| iOS export stuck at 0% | Check free disk space – AVFoundation can fail when temp space is low. |

## 🐞 Known issues

| Issue | Details |
| --- | --- |
| Occasional flicker in the output video | On certain GPU/driver and device combinations you may see a brief black frame or brightness flash in the output video. A fix is in progress. |

## 🗺️ Roadmap

- Arbitrary X/Y watermark coordinates.
- Font size / style props for `watermarkVideo`.
- Contributions & PRs welcome!

## 🤝 Contributing

We follow the Nitro Modules contributing guide. Run examples locally with `yarn example ios` or `yarn example android`.

## 📄 License

MIT © 2025 — Sameer Ather

Made with ❤️ by Sameer Ather & create-react-native-library.
