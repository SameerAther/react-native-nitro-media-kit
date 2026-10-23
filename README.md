# react-native-nitro-media-kit

Native image and video processing for React Native, powered by [Nitro Modules](https://nitro.margelo.com/). Android uses Media3 Transformer; iOS uses AVFoundation and Core Image. Frames, bitmaps, audio and files stay native. JavaScript receives paths, progress and metadata.

## Install

```sh
npm install react-native-nitro-media-kit react-native-nitro-modules@0.37.1
cd ios && pod install
```

Requires React Native 0.77+, Nitro Modules `>=0.37.1 <0.38.0`, iOS 15.1+ (or React Native's higher minimum), and Android API 24+. Android needs compile SDK 36+, JDK 17 and the React Native NDK/CMake toolchain. Rebuild the native app after upgrading. The package cannot run in Expo Go.

## Export once

Combine trimming, resizing, watermarking, frame-rate conversion and audio policy in one export. This avoids intermediate files and repeated lossy encodes.

```ts
import { createVideoJob, deleteGeneratedFile } from 'react-native-nitro-media-kit';

const job = createVideoJob('/path/to/video.mp4', {
  trim: { startMs: 1000, endMs: 6000 },
  watermark: '© My brand',
  watermarkOptions: { position: 'bottom-right', color: '#FFFFFFCC', margin: 24 },
  preset: 'high',
  video: { maxLongEdge: 1920, codec: 'hevc', colorPolicy: 'preserve' },
  audio: 'preserve',
  limits: { timeoutMs: 120_000, maxOutputBytes: 256 * 1024 * 1024 },
}, (event) => {
  console.log(event.stage, event.fraction); // fraction can be undefined
});

// Keep job.cancel() for a cancel button. It works while queued or downloading too.
const result = await job.result;
if (!result.ok || !result.outputUri) {
  throw new Error(result.error?.message ?? 'Export failed');
}
console.log(result.media, result.diagnostics, result.warnings);
// Copy result.outputUri into permanent app storage before retaining it long-term.
await deleteGeneratedFile(result.outputUri);
```

`exportVideo(uri, options?, onProgress?)` is the same operation without a job handle. `createVideoJob` starts immediately, snapshots options and returns `{ id, result, cancel }`. `cancel()` resolves `true` for the first accepted cancellation, and `false` after cancellation/completion or for an unknown ID. Await `result` for cleanup and the terminal outcome. Accepted cancellation and deadlines return `ok: false` with `CANCELLED` or `TIMEOUT`. Cancellation cannot retract an already committed result.

Progress stages are `queued`, `downloading`, `processing`, `validating`, `completed`. Fractions describe the current stage, not a global percentage, and may reset between stages. Updates are throttled to about five per second. Errors/cancellation are reported through `result`; there is no separate failure event. Codec finalization remains cancellable and subject to the deadline.

## Quality and timing

| Video preset | Maximum long edge | Frame timing | Encoding budget |
| --- | --- | --- | --- |
| `preserve` | Source | Source timestamps | Copy compatible streams when unchanged; source-aware bitrate when encoding |
| `high` | Source | Source timestamps | Higher resolution × FPS budget |
| `auto`, `balanced` | 1920 | Cap at 30 FPS | Moderate resolution × FPS budget |
| `small` | 1280 | Cap at 24 FPS | Smaller resolution × FPS budget |

`exportVideo` defaults to `preserve`; `compressMedia` defaults to `auto`. Explicit settings override preset defaults. `high` keeps 4K and 60 FPS when the source has them. No preset enlarges source dimensions. Encoded dimensions are even. Preservation avoids automatic resolution/timing loss; an edited, re-encoded video still uses a lossy codec.

```ts
type CompressVideoOptions = {
  maxLongEdge?: number; // 2..8192, never enlarges the source
  maxFps?: number; // 1..240; fractional values remain fractional
  frameRatePolicy?: 'preserve' | 'cap' | 'constant';
  codec?: 'auto' | 'h264' | 'hevc';
  colorPolicy?: 'preserve' | 'sdr' | 'reject-hdr';
  videoBitrateKbps?: number; // 300..160000
  targetSizeBytes?: number; // estimate; conflicts with videoBitrateKbps
  audioBitrateKbps?: number; // 32..512, forces audio encoding
  keepAudio?: boolean; // compatibility alias; false means mute
};
```

- `preserve` timing keeps variable presentation times. Supplying `maxFps` with this policy is an error.
- `cap` drops excess frames and retains the selected source timestamps. It never increases FPS. An explicit `maxFps` selects this policy unless overridden.
- `constant` requires `maxFps` and produces a regular timestamp grid, repeating/dropping frames as needed. Android stages two input textures in a bounded GPU cache to allow lookahead, then reuses textures for repeated frames. It does not allocate a bitmap per repeated frame.

Bitrate planning accounts for resolution, FPS, codec and duration. HEVC uses a lower nominal bitrate budget. Target size reserves an estimated audio budget and container overhead; encoder variability and copied audio mean the actual size can differ. Impossible targets fail, and overshoot produces `TARGET_SIZE_EXCEEDED`. `maxOutputBytes` is the hard file limit. Compression may produce a larger file; inspect `LIMITED_SAVINGS` and actual size.

### HDR and color

The default color policy is `preserve`. HDR preservation requires HEVC and suitable native decoder/encoder support. Unsupported preservation fails; select `sdr` explicitly to permit tone mapping and 8-bit output. `reject-hdr` rejects HDR inputs. Selecting H.264 while preserving HDR/high-bit-depth input is invalid.

On iOS, composition, reader and writer carry explicit color properties; HDR/high-bit-depth encoding uses a 10-bit path and a half-float Core Image working space. Android requests Media3 HDR retention or explicit OpenGL SDR conversion. Android rejects edited high-bit-depth SDR input when precision cannot be guaranteed. Unchanged compatible video can be copied. HDR output is inspected before publication. Device codec fallback is surfaced in warnings and actual output metadata.

Preservation does not promise identical Dolby Vision dynamic metadata, camera metadata, HDR mastering metadata or bit-exact pixels after encoding. Validate HDR colors on supported physical devices using calibrated reference material. The included neutral HDR ramps check precision/signaling; they are not color calibration references.

### Audio

`audio: 'preserve'` retains the primary audio track and its channel count, copying compatible compressed audio when possible. Video effects alone do not request audio re-encoding. Audio bitrate changes, unsupported container codecs or explicit `stereo` can require encoding. Unsupported channel layouts fail rather than silently downmixing. Merge rejects inputs with different primary channel counts; normalize them through an explicit stereo export first. `mute` removes audio. Conflicting `keepAudio` and `audio` values fail. Additional audio tracks, alternate languages and subtitles are outside the current export contract.

## Other operations

All processing functions resolve to `MediaInfoResult`; check `ok`. Native initialization, invalid Nitro argument conversion and job admission can also throw/reject. `deleteGeneratedFile` rejects invalid or leased-file deletion requests. All public option/result types are exported.

| Function | Behavior |
| --- | --- |
| `getMediaInfo(uri)` | Inspect displayed dimensions, timing, codec, HDR, known bit depth, video bitrate and primary audio layout. |
| `compressMedia(uri, options?)` | Compress images or videos; video uses the shared export planner. |
| `watermarkVideo(uri, text, options?)` | Render text into video frames with the preserve preset. |
| `trimVideo(uri, { startMs, endMs })` | Exact requested range, normalized to a new timeline; native sample boundaries can quantize cuts. |
| `splitVideo(uri, segments)` | Validate all ranges first; publish outputs in request order, with batch rollback on failure. |
| `removeAudio(uri)` | Video-only export, copying video when compatible. |
| `mergeVideos(uris)` | Concatenate onto the first clip's original canvas, fit/letterbox each display transform and retain audio gaps. |
| `convertImageToVideo(uri, seconds)` | Silent H.264 MP4 at 30 FPS, at most 1920 pixels on the long edge; `(0, 86400]` seconds, rounded up to a frame. |
| `extractThumbnail(uri, options?)` | Oriented JPEG with precise selection by default and `actualTimeMs` in the result. |
| `deleteGeneratedFile(uri)` | Delete one unleased library output; return false if already absent. |

Video outputs are MP4. Output metadata is probed from the completed file rather than copied from the source. `media.fps` describes native track/sample timing when known and is not proof of constant spacing for VFR. Unknown FPS is omitted; encoding may use a 30 FPS planning hint while retaining source timestamps. `bitDepth` is reported when native metadata exposes it; codec support depends on the device.

`MediaInfoResult` contains `ok`, `operation`, `type`, optional `inputUri`, `outputUri`, `segments`, `media`, `warnings`, `error`, `actualTimeMs`, and `diagnostics`. New export diagnostics include elapsed time, output bytes, video/audio processing paths, selected codec, estimated bytes, frame count when available, and sampled peak process RSS. RSS includes other app work and can omit GPU allocations; it is not an isolated allocation count. An encoded frame count is not a full decoded-output verification.

### Watermarks, ranges and thumbnails

`WatermarkOptions` supports `position` (`top-left`, `top-right`, `bottom-left`, `bottom-right`, `center`), `fontSize` (1..512 output pixels), `color` (`#RRGGBB`/`#RRGGBBAA`), `opacity` (0..1), and `margin` (output pixels). Defaults are bottom-right, white, 5% shorter-side font size and 2.5% shorter-side margin. Alpha and opacity multiply. Text wraps/shrinks to fit, must contain 1..4096 UTF-16 code units and must fit the image memory budget. Font rendering differs by platform.

Ranges require finite `0 <= startMs < endMs <= durationMs`. Invalid ranges fail rather than being clamped to earlier keyframes. `ThumbnailOptions` supports `timeMs` (default 0, less than duration), `maxLongEdge` (default 1280, 1..8192), `quality` (0..1, default 0.9), and `precision` (`precise` or faster `fast` sync-frame seeking). Always inspect `actualTimeMs`; requested times may fall between frames.

### Images

`compressMedia` accepts `image: { format, quality, maxLongEdge }` and `preserveMetadata` (default false). Formats are `auto`, `jpeg`, `png`; auto uses PNG for alpha. PNG ignores quality with a warning. Image decoding uses oriented downsampling, a 16-megapixel ceiling and platform memory limits. Explicit dimensions exceeding the memory budget fail; automatic sizes can be reduced. Image presets have separate long-edge/quality budgets. Optional metadata preservation is partial and can retain EXIF/GPS; orientation is applied to pixels and normalized in output metadata.

## Resources and file ownership

Inputs accept absolute paths, percent-encoded `file://` URIs, HTTP(S), and readable Android `content://` URIs. Grant provider access first. Remote/provider inputs stream to private temporary files with bounded buffers; repeated URLs within a job share their download. Local files are leased for the job, preventing `deleteGeneratedFile` from racing an active export.

```ts
type ProcessingLimits = {
  timeoutMs?: number;       // default 30 minutes; 1 ms..24 hours
  maxInputBytes?: number;   // default 2 GiB per input; up to 64 GiB
  maxOutputBytes?: number;  // default 4 GiB per output; up to 64 GiB
  minFreeDiskBytes?: number; // default 64 MiB reserve; 0..64 GiB
};
```

The deadline starts before queueing and remains active through download, processing, validation and finalization. Streaming byte limits and periodic output/disk checks cancel jobs exceeding budgets. Output limits are checked again at commit; disk usage may briefly exceed a limit between checks. Legacy operations use default limits; choose `exportVideo` for custom video limits.

At most 64 jobs are admitted. Native permits independently bound downloads/provider copies (2), still-image work (1) and hardware video exports (1) across HybridObject instances. Downloads and probes do not hold the encoder permit. JavaScript callbacks carry small progress values, never media buffers.

Each job stages output privately, validates it, then renames it into `nitro-media-kit/output` with UUID filenames. Failure/cancellation rolls back that job's files. Abandoned private job directories older than 24 hours are reaped on later operations. Successful outputs remain until explicit deletion or OS cache eviction. Copy important files into permanent app storage. A split batch returns paths only after all outputs commit successfully.

## Migrating to 0.5

- `high` video quality now keeps source dimensions/FPS. Use `balanced` for the former 1080p/30 size tradeoff.
- Export/legacy editing defaults preserve HDR instead of Android's earlier automatic SDR conversion. Request `video.colorPolicy: 'sdr'` explicitly for compatibility exports.
- Merge retains the first clip's original canvas rather than imposing a 1920-pixel cap.
- Prefer `exportVideo`/`createVideoJob` to chains of trim, watermark and compression.
- Thumbnails default to precise seeking and expose the selected timestamp.
- Jobs enforce file/deadline/memory budgets. Use explicit export limits for large inputs, and await cancellation before deleting inputs.
- Keep Nitro runtime and Nitrogen at matching 0.37.1 versions, regenerate bindings and rebuild native apps. Existing 0.4 watermark options-object calls remain supported.

## Development and validation

Use the checked-in Yarn 3.6.1 and Node from `.nvmrc`.

```sh
yarn install --immutable
yarn nitrogen
yarn typecheck
yarn lint
yarn test --runInBand
yarn test:host:android # JDK 17, local JVM; no emulator
yarn test:host:ios     # macOS Swift host executable; no simulator
yarn test:validator    # Python + FFmpeg/ffprobe, offline checker self-tests
yarn prepare
```

Swift host checks compile the production planner, job controls, downloader and file ownership code against small boundary value types. Native builds separately validate the generated Nitro ABI. The example Podfile retains the scoped glog 0.3.5 module-map compatibility fix for source React Native with Xcode 26.

Runtime regression tests are opt-in in CI through `workflow_dispatch.run_native_runtime`. Normal CI compiles native targets and runs host checks without booting an emulator/simulator. Native tests cover every expected watermark frame, combined CFR export, VFR timing, mono audio, orientation, trimming, merge, metadata and deletion. Adding/compiling these tests does not establish that they passed on devices.

Validate a saved native output offline:

```sh
python3 scripts/validate-media.py output.mp4 --expected-fps 30 \
  --expected-duration-ms 2000 --expected-codec h264 --expected-color sdr \
  --watermark-roi 8 8 100 25 --min-white-pixels 50
# Add --reference aligned.mp4 for SSIM/PSNR; identical display size/frame timing required.
# Add --diagnostics native-diagnostics.json to attach saved native measurements.
```

The validator fully decodes outputs, checks every frame's presentation timestamp and optional watermark region, and can compare aligned references. Its synthetic self-tests include deliberately missing alternating watermarks. They validate the checker, not the native exporters. See [test-fixtures/README.md](test-fixtures/README.md) for temporal/color inputs and [AUDIT.md](AUDIT.md) for actual validation scope.

For repeatable performance measurements, save native results as a JSON array of `{ platform, device, fixture, settingsId, result }`, with `settingsId` identifying the complete options used. Run `python3 scripts/summarize-benchmarks.py records.json`. It groups by device/fixture/settings/codec/path and reports median time, bytes, RSS and elapsed/media-duration ratio. Warm up separately, use multiple runs, retain failures and offline quality reports, and record OS/thermal state. No measured speedup is claimed without device results.

## License

MIT
