# react-native-nitro-media-kit

Native image, video, and audio processing for React Native, powered by [Nitro Modules](https://nitro.margelo.com/). Android uses Media3; iOS uses AVFoundation and Core Image. Media buffers stay native—JavaScript works with file paths, metadata, and progress events.

- Combine trimming, resizing, watermarks, overlays, subtitles, and audio edits in one video export.
- Reuse resolved inputs and metadata through native `MediaAsset` objects.
- Control native export jobs with progress, cancellation, and explicit disposal.
- Create timelines with per-clip edits and dissolves.
- Extract thumbnails, JPEG storyboards, and AAC audio.
- Compress images and videos, inspect codecs, and analyze or normalize audio loudness.

[Installation](#installation) · [Quick start](#quick-start) · [Reusable assets](#reusable-assets) · [Editing](#editing) · [Thumbnails](#thumbnails-and-storyboards) · [Timelines](#timelines) · [Audio](#audio) · [Quality](#quality-and-export-settings) · [API](#api-reference)

## Installation

```sh
npm install react-native-nitro-media-kit react-native-nitro-modules@0.37.1
cd ios && pod install
```

| Requirement   | Version                                                                    |
| ------------- | -------------------------------------------------------------------------- |
| React Native  | 0.77 or newer                                                              |
| Nitro Modules | `>=0.37.1 <0.38.0`; use 0.37.1 with this source tree                       |
| iOS           | The minimum deployment target required by your React Native version        |
| Android       | API 24+, compile SDK 36+, JDK 17, and the React Native NDK/CMake toolchain |

Rebuild your native app after installation or upgrades. Expo projects need a native development build; this package does not run in Expo Go.

## Quick start

Use `createExport` to apply edits together, avoiding intermediate files and repeated video encoding. This example exports seconds 1–6 of an input that is at least six seconds long:

```ts
import { createExport } from 'react-native-nitro-media-kit';

const job = createExport(
  '/path/to/input.mp4',
  {
    trim: { startMs: 1000, endMs: 6000 },
    watermark: '© My brand',
    watermarkOptions: { position: 'bottom-right', margin: 24 },
    preset: 'balanced',
    video: { codec: 'h264', colorPolicy: 'sdr' },
    limits: { timeoutMs: 120_000 },
  },
  ({ stage, fraction }) => {
    console.log(stage, fraction);
  }
);

try {
  const result = await job.result();
  console.log(result.outputUri, result.media, result.warnings);
  // Copy the output to permanent app storage if you need to retain it.
} finally {
  job.dispose();
}
```

The example explicitly chooses H.264 and SDR for compatibility. Omit those settings to use the default color-preservation policy. Video exports produce MP4 files; edits that require encoding use a lossy codec.

### Job lifecycle and progress

`createExport` and `createTimeline` start immediately and return a native `ExportJob`:

| Member                          | Behavior                                                             |
| ------------------------------- | -------------------------------------------------------------------- |
| `id`                            | Native job identifier                                                |
| `state`                         | `queued`, `running`, `completed`, `failed`, or `cancelled`           |
| `result()`                      | Resolves to `VideoExportResult`; rejects on failure or cancellation  |
| `cancel()`                      | Synchronously requests cancellation; returns whether it was accepted |
| `setProgressListener(listener)` | Replaces the callback; pass `undefined` to detach it                 |
| `dispose()`                     | Cancels unfinished work and detaches the listener                    |

After requesting cancellation, await `result()` for the terminal outcome and cleanup. `dispose()` does not delete a successful output. Jobs are in-process and do not survive app termination or grant unrestricted background execution.

Progress stages are `queued`, `downloading`, `processing`, `validating`, and `completed`. `fraction` can be undefined and describes the current stage; it can reset between stages. Updates are throttled to about five per second. Completion and errors come through `result()`. A callback already queued for JavaScript may still arrive after listener detachment.

## Reusable assets

Open an input once when several operations use the same file. An asset retains the resolved input and cached metadata:

```ts
import { openMedia } from 'react-native-nitro-media-kit';

const asset = await openMedia('/path/to/input.mp4');
try {
  console.log(asset.type, asset.info);

  const options = { preset: 'preserve' as const };
  const plan = await asset.planExport(options);
  console.log(plan.videoPath, plan.estimatedSizeBytes, plan.reasons);

  const job = asset.createExport(options);
  try {
    const result = await job.result();
    console.log(result.outputUri);
  } finally {
    job.dispose();
  }
} finally {
  asset.dispose();
}
```

Assets also expose `extractThumbnails`, `extractAudio`, `analyzeAudio`, and `normalizeAudio`. Remote and Android content inputs are staged once; local inputs are leased. Keep local files unchanged while in use. A size or modification-time change requires reopening the asset.

Dispose assets when finished. Disposal prevents new operations while active operations retain their input until completion. Up to 64 assets can remain open. Generated output files have their own lifetime, independent of the asset.

## Editing

All video edit options belong to the same `ExportVideoOptions` object and can be combined in one export. Times are milliseconds unless an API explicitly says otherwise.

### Geometry, overlays, and subtitles

```ts
import {
  createExport,
  type ExportVideoOptions,
} from 'react-native-nitro-media-kit';

const options = {
  trim: { startMs: 1000, endMs: 6000 },
  geometry: { width: 1080, height: 1920, fit: 'cover' },
  overlays: [
    {
      kind: 'image',
      uri: '/path/to/logo.png',
      x: 40,
      y: 1680,
      width: 180,
      height: 180,
      startMs: 0,
      endMs: 5000,
      fadeInMs: 200,
      fadeOutMs: 200,
    },
    {
      kind: 'text',
      text: 'Made for mobile',
      x: 40,
      y: 100,
      width: 1000,
      height: 160,
      fontSize: 48,
      color: '#FFFFFFFF',
    },
  ],
  subtitles: {
    format: 'srt',
    content: '1\n00:00:00,000 --> 00:00:02,000\nHello world',
    fontSize: 48,
  },
} satisfies ExportVideoOptions;

const job = createExport('/path/to/input.mp4', options);
try {
  const result = await job.result();
  console.log(result.outputUri);
} finally {
  job.dispose();
}
```

Geometry applies in this order: source orientation → crop → clockwise rotation → flips → output canvas. Crop uses oriented source pixels with a top-left origin. Rotation accepts `0`, `90`, `180`, or `270`. An explicit canvas needs both `width` and `height`, each an even integer in `[2, 8192]`. `contain` adds black padding; `cover` crops to fill. Preset or `maxLongEdge` settings can further reduce the canvas.

Overlay coordinates are final output pixels. Array order determines z-order. Images retain their aspect ratio inside their rectangles; text wraps inside its rectangle. Alpha and fades are supported. Bounds must fit the output canvas. Up to 32 overlays share a bounded image memory budget.

Overlay and subtitle times refer to the **final output timeline**, after trim and speed changes. End times are exclusive. Subtitles accept plain SRT or WebVTT content, up to 1 MiB and 2,000 cues, with four simultaneous cues. Markup is stripped; subtitle styling and position settings are not interpreted. Only the current caption raster is cached.

### Text watermarks

For a positioned watermark, set `watermark` and `watermarkOptions` on the export, or call `watermarkVideo(uri, text, options?)`.

| Option     | Default / supported values                                            |
| ---------- | --------------------------------------------------------------------- |
| `position` | `bottom-right`; also `top-left`, `top-right`, `bottom-left`, `center` |
| `fontSize` | 5% of the shorter side; 1–512 output pixels                           |
| `margin`   | 2.5% of the shorter side, in output pixels                            |
| `color`    | White; `#RRGGBB` or `#RRGGBBAA`                                       |
| `opacity`  | `1`; range `[0, 1]`, multiplied by color alpha                        |

Text wraps or shrinks to fit and supports 1–4,096 UTF-16 code units. Font rendering differs by platform.

### Trim and stream copy

Ranges use `0 <= startMs < endMs <= source duration`. The default `trimMode: 'accurate'` follows the requested range, subject to native sample boundaries.

`trimMode: 'keyframe'` expands the range to the preceding video sync sample and the next sync sample at or after the requested end. Read `result.actualTrim` for the selected source range.

| `copyPolicy`   | Behavior                                           |
| -------------- | -------------------------------------------------- |
| `auto`         | Default; copy compatible streams when edits permit |
| `require-copy` | Fail if video or audio would require encoding      |
| `encode`       | Force video encoding                               |

Keyframe trimming can use stream copy when compatible. Spatial edits, watermarks, overlays, and speed changes require video encoding.

## Thumbnails and storyboards

```ts
import { extractThumbnails } from 'react-native-nitro-media-kit';

const result = await extractThumbnails('/path/to/input.mp4', {
  timesMs: [0, 1000, 2000, 3000],
  maxLongEdge: 240,
  precision: 'precise',
  storyboardColumns: 2,
});

console.log(result.thumbnails); // JPEG paths, dimensions, requested/actual times
console.log(result.storyboard); // JPEG sprite and top-left pixel cell coordinates
```

A batch reuses one native extraction session, processes timestamps in temporal order, and returns results in the caller's original order. Requests support 1–256 timestamps. Always inspect `actualTimeMs`, since a requested time can fall between frames.

Storyboards are optional and bounded to 8,192 pixels per dimension and the shared image memory budget. Reduce the thumbnail size or split large requests. A failed batch rolls back its generated files. You own every returned thumbnail and storyboard file.

For a single thumbnail, `extractThumbnail(uri, options?)` supports `timeMs` (default `0`), `maxLongEdge` (default `1280`), `quality` (default `0.9`), and `precision`. `precise` is the default; `fast` uses sync-frame seeking. Times must be less than the source duration.

## Timelines

```ts
import { createTimeline } from 'react-native-nitro-media-kit';

const job = createTimeline(
  [
    {
      uri: '/path/to/first.mp4',
      trim: { startMs: 0, endMs: 5000 },
      transitionMs: 500,
    },
    { uri: '/path/to/second.mp4', speed: 1.5, geometry: { rotation: 90 } },
  ],
  {
    geometry: { width: 1920, height: 1080, fit: 'contain' },
    video: { maxFps: 30, frameRatePolicy: 'constant', colorPolicy: 'sdr' },
  }
);

try {
  const result = await job.result();
  console.log(result.outputUri, result.media.durationMs);
} finally {
  job.dispose();
}
```

Timelines support 1–64 video clips with per-clip trim, speed, geometry, and an outgoing `transitionMs` dissolve. Dissolves overlap video and crossfade source audio. Transitions must fit adjacent clips and cannot create three overlapping clips. Final duration subtracts the overlaps.

Put crop, rotation, and flips on individual clips; global geometry sets the output canvas. Global overlays, subtitles, and music use the combined timeline. Global trim, global speed, and `require-copy` are unsupported because timelines are composed and encoded.

Timelines render at a constant planned cadence: the maximum speed-adjusted source FPS, reduced by any requested cap or preset. On Android, **HDR or high-bit-depth clips with dissolves require explicit `colorPolicy: 'sdr'`** because Media3's overlapping video compositor supports SDR only. Hard-cut timelines retain the HDR preservation path when compatible.

## Audio

### Track selection, music, and speed

Set `audio: 'preserve'` (default), `'stereo'`, or `'mute'`. Preservation retains the selected primary audio layout and copies compatible compressed audio where possible. Video effects alone do not request audio encoding. Bitrate changes, unsupported container codecs, and explicit stereo conversion can require encoding.

Use `audioEdit` for zero-based `trackIndex`, source `volume`, `fadeInMs`, `fadeOutMs`, `musicUri`, `musicVolume`, `musicStartMs`, and `musicTrim`. Volumes are in `[0, 1]`. Source volume `0` replaces original audio with music. Music is clipped to the output duration; it does not extend the video or loop automatically. Fades apply to source audio; music volume is independent. Lower gains if the mix would clip.

`speed` accepts a constant rate in `[0.25, 4]`. Output duration is divided by speed; source cadence is multiplied before applying frame-rate settings. Audio pitch is preserved. Select a frame-rate cap or constant rate when needed for the target device.

Unsupported channel layouts fail rather than silently downmixing. `mergeVideos` requires matching primary channel counts; use an explicit stereo export to align inputs. Additional audio tracks and alternate-language passthrough are outside the export contract.

### Extract audio

```ts
import { extractAudio } from 'react-native-nitro-media-kit';

const audio = await extractAudio('/path/to/input.mp4', {
  trackIndex: 0,
  trim: { startMs: 1000, endMs: 6000 },
  bitrateKbps: 192,
});
console.log(
  audio.outputUri,
  audio.durationMs,
  audio.channels,
  audio.sampleRate
);
```

Output is AAC in an M4A file; bitrate defaults to 192 kbps. `asset.extractAudio(options)` provides the same operation for an open input.

### Loudness analysis and normalization

```ts
import { analyzeAudio, normalizeAudio } from 'react-native-nitro-media-kit';

const measurement = await analyzeAudio('/path/to/audio.m4a', {});
console.log(measurement.integratedLufs, measurement.truePeakDbtp);

const normalized = await normalizeAudio('/path/to/audio.m4a', {
  targetLufs: -16,
  maxTruePeakDbtp: -1,
  bitrateKbps: 192,
});
console.log(
  normalized.audio.outputUri,
  normalized.appliedGainDb,
  normalized.limitedByPeak
);
```

Both operations support `trackIndex`, `trim`, and `limits`, and are available on `MediaAsset`. The streaming meter measures gated integrated loudness, sample peak, and a 4× oversampled true-peak estimate. It follows [ITU-R BS.1770](https://www.itu.int/rec/R-REC-BS.1770) K weighting with 400 ms blocks, 100 ms hops, and absolute/relative gating. Mono and stereo inputs at 8–192 kHz are supported, up to 24 hours per request. Silence leaves loudness and peak fields undefined. The 48-tap peak estimate is not broadcast-equipment certification.

Normalization requires at least 400 ms and applies constant gain, retaining source dynamics. Defaults are −16 LUFS, −1 dBTP, and 192 kbps AAC/M4A. If the target loudness exceeds the peak ceiling, gain is reduced and `limitedByPeak` is true. The encoded result is analyzed again; overshoots trigger up to two retries from the original input with additional headroom. An output still above the measured ceiling by more than 0.05 dB is rejected.

The result includes `before`, `after`, `appliedGainDb`, and `audio`. Codec priming or padding can affect decoded duration. To use normalized audio in a video, pass its M4A path as `audioEdit.musicUri` with source volume `0`, and keep that file until the video export completes.

## Quality and export settings

| Preset             | Maximum long edge | Default timing    | Encoding budget                                          |
| ------------------ | ----------------- | ----------------- | -------------------------------------------------------- |
| `preserve`         | Source            | Source timestamps | Copy when compatible; source-aware bitrate when encoding |
| `high`             | Source            | Source timestamps | Higher resolution × FPS budget                           |
| `auto`, `balanced` | 1920              | Cap at 30 FPS     | Moderate budget                                          |
| `small`            | 1280              | Cap at 24 FPS     | Smaller budget                                           |

Exports default to `preserve`; `compressMedia` defaults to `auto`. Explicit settings override presets. `high` keeps source 4K/60 FPS when present. Presets do not upscale source dimensions; an explicit geometry canvas can set a different output size. Encoded dimensions are even.

| `video` option     | Values / behavior                                            |
| ------------------ | ------------------------------------------------------------ |
| `maxLongEdge`      | 2–8192; caps the planned geometry                            |
| `maxFps`           | 1–240; fractional FPS is retained                            |
| `frameRatePolicy`  | `preserve`, `cap`, or `constant`                             |
| `codec`            | `auto`, `h264`, or `hevc`                                    |
| `colorPolicy`      | `preserve`, `sdr`, or `reject-hdr`                           |
| `videoBitrateKbps` | 300–160000                                                   |
| `targetSizeBytes`  | Estimated target; cannot be combined with `videoBitrateKbps` |
| `audioBitrateKbps` | 32–512; requests audio encoding                              |
| `keepAudio`        | Compatibility alias; `false` means mute                      |

`preserve` timing retains source presentation times and cannot be combined with `maxFps`. `cap` drops excess frames, retaining selected timestamps and never increasing FPS. Supplying `maxFps` selects `cap` unless overridden. `constant` requires `maxFps` and repeats or drops frames to produce a regular timestamp grid.

Bitrate estimates account for resolution, FPS, codec, duration, audio, and container overhead. HEVC uses a lower nominal bitrate budget. Actual sizes can differ; inspect `TARGET_SIZE_EXCEEDED` or `LIMITED_SAVINGS` warnings. Use `limits.maxOutputBytes` for a hard output-file budget. Compression can produce a larger file.

### HDR and color

The default `colorPolicy: 'preserve'` requires suitable native codec support and HEVC for HDR/high-bit-depth output. Unsupported preservation fails. Select `sdr` explicitly to permit tone mapping and 8-bit output, or `reject-hdr` to reject HDR inputs. H.264 cannot preserve HDR/high-bit-depth input.

The iOS encoding path carries color properties and uses 10-bit output with half-float Core Image processing for HDR/high-bit-depth content. Android uses Media3 HDR retention or explicit SDR conversion; edited high-bit-depth SDR input is rejected if precision cannot be guaranteed. Android converts ordinary SDR overlay rasters for HDR composition. Device fallbacks are reported through warnings and actual output metadata.

Re-encoding does not guarantee identical Dolby Vision dynamic metadata, HDR mastering metadata, camera metadata, or pixels. Device codec support still determines which exports can run.

### Images

`compressMedia` accepts `image: { format, quality, maxLongEdge }`. Formats are `auto`, `jpeg`, and `png`; `auto` selects PNG for alpha. PNG ignores quality and reports a warning. Image presets have their own size and quality budgets.

Image decoding applies orientation and downsampling with a 16-megapixel ceiling and platform memory limits. Explicit oversized requests fail; automatic dimensions can be reduced. `preserveMetadata` defaults to `false`. Preservation is partial and can retain EXIF/GPS; orientation is applied to pixels and normalized in output metadata.

## API reference

All public option and result types are exported. See the [TypeScript contracts](src/NitroMediaKit.nitro.ts) for complete fields.

| Native API                                    | Returns / purpose                                                                              |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `openMedia(uri, limits?)`                     | `Promise<MediaAsset>`; reusable resolved input and metadata                                    |
| `getCapabilities()`                           | `Promise<MediaCapabilities>`; device encoder information                                       |
| `planExport(uri, options)`                    | `Promise<ExportPlan>`; dimensions, timing, codec, estimated size, processing path, and reasons |
| `createExport(uri, options, onProgress?)`     | `ExportJob` for a video export                                                                 |
| `createTimeline(clips, options, onProgress?)` | `ExportJob` for a composed timeline                                                            |
| `extractThumbnails(uri, options)`             | `Promise<ThumbnailBatchResult>`                                                                |
| `extractAudio(uri, options)`                  | `Promise<AudioExportResult>`                                                                   |
| `analyzeAudio(uri, options)`                  | `Promise<LoudnessAnalysis>`                                                                    |
| `normalizeAudio(uri, options)`                | `Promise<NormalizedAudioResult>`                                                               |
| `deleteGeneratedFile(uri)`                    | `Promise<boolean>`; delete one unleased library output                                         |

Capabilities are advisory: `supported`, `unsupported`, or `unknown`. Resources and platform behavior can change at execution. Plans estimate the chosen path; the final result describes the actual output.

### Results and errors

Native export results contain `outputUri`, `media`, `warnings`, optional `diagnostics`, and optional `actualTrim`. New processing APIs reject on failure with a `[CODE]` prefix in the error message. Calls can also throw during native initialization, argument conversion, or job admission.

Metadata is read from completed outputs. Unknown values such as FPS or bit depth are omitted. `media.fps` alone does not establish constant spacing for variable-frame-rate input.

Diagnostics include elapsed time, output bytes, video/audio processing paths, selected codec, estimated size, frame count when available, and sampled peak process memory. Process memory includes other app work and may omit GPU allocations.

### Compatibility helpers

Existing convenience functions remain available and return `Promise<MediaInfoResult>`. Check `ok` and `error` before reading optional outputs:

```ts
import { exportVideo } from 'react-native-nitro-media-kit';

const result = await exportVideo('/path/to/input.mp4', { preset: 'balanced' });
if (!result.ok || !result.outputUri) {
  throw new Error(result.error?.message ?? 'Export failed');
}
console.log(result.outputUri);
```

| Helper                                | Purpose                                                                     |
| ------------------------------------- | --------------------------------------------------------------------------- |
| `getMediaInfo(uri)`                   | Inspect dimensions, duration, timing, codecs, HDR, and audio layout         |
| `compressMedia(uri, options?)`        | Compress an image or video                                                  |
| `watermarkVideo(uri, text, options?)` | Add a positioned text watermark                                             |
| `trimVideo(uri, segment)`             | Trim to an accurate range                                                   |
| `splitVideo(uri, segments)`           | Export segments in request order; roll back the batch on failure            |
| `removeAudio(uri)`                    | Export video without audio, copying video when compatible                   |
| `mergeVideos(uris)`                   | Concatenate on the first clip's original canvas with fit/letterbox behavior |
| `convertImageToVideo(uri, seconds)`   | Silent 30 FPS H.264 MP4; at most 1920 pixels on the long edge               |
| `extractThumbnail(uri, options?)`     | Extract one oriented JPEG                                                   |

`createVideoJob(uri, options?, onProgress?)` is the compatibility job wrapper. It returns `{ id, result, cancel }`: `result` is a **Promise property**, and `cancel()` returns `Promise<boolean>`. The native `ExportJob` instead exposes a **`result()` method** and synchronous `cancel()`. Compatibility export failures resolve with `ok: false`; native job failures reject.

## Files and resource limits

Inputs accept absolute paths, percent-encoded `file://` URIs, HTTP(S), and readable Android `content://` URIs. Grant provider access before processing. Remote/provider inputs stream into private temporary files; repeated inputs within a job reuse their resolution. Local files are leased while active work uses them.

| `limits` field     | Default          | Accepted range |
| ------------------ | ---------------- | -------------- |
| `timeoutMs`        | 30 minutes       | 1 ms–24 hours  |
| `maxInputBytes`    | 2 GiB per input  | 1 byte–64 GiB  |
| `maxOutputBytes`   | 4 GiB per output | 1 byte–64 GiB  |
| `minFreeDiskBytes` | 64 MiB reserve   | 0–64 GiB       |

Deadlines include queueing, download, processing, and finalization. Streaming byte limits and periodic output/disk checks cancel jobs that exceed budgets. Output limits are checked again before publication; disk usage can briefly exceed a limit between checks. Compatibility helpers without a `limits` option use defaults.

Up to 64 jobs are admitted. Native permits separately bound input I/O, image work, audio analysis, and hardware video exports. Heavy processing runs on native workers; media buffers do not cross into JavaScript.

Outputs are staged privately and published under `nitro-media-kit/output` only on success. Failure or cancellation rolls back that job's files. Outputs remain until deleted or evicted by the OS cache policy. Copy important files to permanent storage.

```ts
import { deleteGeneratedFile } from 'react-native-nitro-media-kit';

await deleteGeneratedFile('/path/returned/by/the/library.mp4');
```

Deletion accepts only library-generated, unleased files. It returns `false` if a file is already absent and rejects invalid or active-file deletion requests. Keep dependent input files until all jobs using them complete.

## Migrating from 0.4

- `high` keeps source dimensions and FPS. Use `balanced` for a 1080p/30 size tradeoff.
- Color preservation is the default. Select `video.colorPolicy: 'sdr'` for SDR compatibility exports.
- Merge keeps the first clip's original canvas instead of imposing a 1920-pixel cap.
- Prefer one `createExport` call to a chain of trim, watermark, and compression calls.
- Thumbnails use precise seeking by default and report the actual selected time.
- Await cancellation before deleting inputs, and set explicit budgets for large jobs.
- Keep Nitro runtime and Nitrogen versions aligned at 0.37.1 when building this source tree. Existing watermark options-object calls remain supported.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community guidelines.

## License

[MIT](LICENSE)
