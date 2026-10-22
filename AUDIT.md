# Nitro Media Kit 0.4 audit

Version 0.4 changes the public watermark API and replaces Android's custom video export implementation.

## Findings and fixes

| Finding                                                                                                                                                        | Change                                                                                                                                                              | Regression coverage                                                             |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Library and example used different Nitro runtimes; generated bindings could disagree with native code.                                                         | Pin Nitrogen and both development/example Nitro runtimes to 0.37.1; restrict the supported Nitro peer range and regenerate native bindings.                         | Package build, Kotlin/C++ and Swift/C++ builds.                                 |
| iOS Simulator watermarking returned success after copying an unchanged source.                                                                                 | Remove the bypass and Core Animation export; composite a rasterized overlay with Core Image on every supported platform.                                            | Real white watermark pixels in landscape and rotation-tagged portrait fixtures. |
| iOS watermark composition omitted source audio while reporting audio tracks.                                                                                   | Keep primary audio through the reader/writer pipeline and inspect output metadata.                                                                                  | Watermark fixture retains mono AAC audio.                                       |
| Android custom codec loops could stall under decoder/encoder backpressure; EGL overlays used transformed texture coordinates for placement and alpha blending. | Replace manual EGL, codec, muxer, timestamp and drain loops with Media3 Transformer. Use separate overlay frame anchors in displayed-video space.                   | Watermark, portrait, trim, merge and compression instrumented tests.            |
| Video compression fed compressed audio into an AAC encoder on iOS.                                                                                             | Read decoded PCM at the source sample rate and supported channel count before AAC encoding. Fail when audio cannot be configured instead of silently dropping it.   | Compress a video containing mono AAC; assert output audio remains present.      |
| Repeated timestamps in output filenames caused collisions; errors left partial outputs and downloads.                                                          | UUID output filenames, per-job scratch/output ownership, streaming downloads and rollback on failure.                                                               | Native operations and protected deletion tests.                                 |
| Entire remote videos were buffered in iOS RAM; oversized still images could decode into huge allocations.                                                      | Download directly to disk, downsample images during decoding, bound default image dimensions, and bound Media3 bitmap decoding to 1920 pixels for image-to-video.   | Native image-to-video and compression paths.                                    |
| File URIs were treated as literal filesystem paths; Android content URIs were unsupported.                                                                     | Decode file URIs; stream readable content-provider inputs into job-owned scratch storage on Android.                                                                | Native file-URI round trips; direct resolver checks.                            |
| Split ranges were silently clamped or snapped to sync frames.                                                                                                  | Require finite, ordered, in-bounds ranges; validate the whole batch before export and roll back the whole batch on failure.                                         | Trim/split duration and invalid-range native tests.                             |
| Merges did not correctly normalize different display transforms or missing audio.                                                                              | Fit each clip to the first clip's canvas, apply individual transforms, and preserve silent gaps.                                                                    | Landscape + portrait + silent image-video concatenation.                        |
| Media3 forwards optional source metadata, including GPS, to its muxer.                                                                                         | Filter optional metadata unless compression explicitly requests preservation. Keep codec configuration and display orientation independently.                       | Location-tagged compression fixture tests.                                      |
| Native jobs could exhaust hardware encoders or leave asynchronous operations pending.                                                                          | Serialize jobs across instances, release resources on failure/cancellation, coordinate writer terminal states, and time-limit Android exports/iOS sample transfers. | Native fixture suite and resource-ownership review.                             |
| Publish glob patterns could include podspec/config files from a renamed dependency backup directory.                                                           | Use an explicit podspec path and exclude dependency directories from the archive.                                                                                   | Inspect the actual packed tarball.                                              |
| Tooling metadata disagreed with the checked-in Yarn executable; formatter/plugin versions were incompatible; CI used an old Xcode version.                     | Align Yarn metadata, update Prettier, scope lint/typecheck/codegen to source files, update Xcode, and add native regression jobs.                                   | JS tests, lint, typecheck, package build and CI configuration.                  |

## New API

- `trimVideo`: one range to one output.
- `removeAudio`: video-only export.
- `extractThumbnail`: oriented JPEG preview with bounded size/quality.
- `deleteGeneratedFile`: idempotent deletion restricted to the library's generated-output namespace.
- `WatermarkOptions`: typed position, pixel font size/margin, RGBA color, opacity and multiline wrapping.

See [README.md](README.md) for migration and file-lifetime rules. Old positional watermark calls must use an options object. Outputs are cache files; copy them to permanent app storage when needed.

## Validation scope

The native suites use synthetic, local media rather than remote sample URLs. They are designed to detect unchanged watermark copies, missing audio, incorrect display dimensions, invalid duration, bad merges, metadata leakage, and deletion of caller-owned input files.

The supported device/codec matrix is broader than the local simulator matrix. Physical-device profiling, HDR/color-reference comparison, non-H.264 format coverage, very large assets, and sustained thermal/memory testing remain release validation work. No benchmark speedup or universal codec support is claimed.

Local checks passed: TypeScript, ESLint, three JavaScript binding tests, Nitrogen/Bob package build, Android Kotlin/C++ and example/test APK compilation, and iOS Swift/C++ compilation including the XCTest target. The packed archive was inspected for native helpers, generated bindings, declarations, and accidental dependency/build artifacts. Native runtime validation remains incomplete. Android test installation encountered an unavailable emulator package service. The iOS test host's duplicate static-pod registration was corrected by inheriting search paths instead of relinking the app's pods; the corrected configuration passed compilation but has not been rerun. The regression-coverage column above describes the added checks, not successful runtime execution.

The external npm advisory scan has not run. Local manifest/lockfile review and native source review do not substitute for that advisory scan.

## Upstream references

- [Nitro Modules 0.37.1 stable release](https://github.com/margelo/nitro/releases/tag/v0.37.1)
- [Nitro asynchronous promises](https://nitro.margelo.com/docs/types/promises)
- [Media3 compositions](https://developer.android.com/media/media3/transformer/composition)
