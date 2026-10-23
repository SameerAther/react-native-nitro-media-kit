# Nitro Media Kit 0.5 pipeline audit

The pipeline now separates export planning, job controls, file ownership and native encoding. A combined export applies trim, watermark, resizing, timing, audio policy and encoding in one operation. Legacy video editing calls share that path.

## Findings and changes

| Finding | Implemented change | Evidence/coverage |
| --- | --- | --- |
| Chaining edits created intermediate files and repeated lossy encodes. | Add `exportVideo` and `createVideoJob`; route legacy editing through one planner/encoder path. | Generated native ABI, JS snapshot/receiver tests, compiled combined native regression cases. |
| High quality reduced 4K/60 FPS; fixed bitrate limits ignored resolution and duration. | Add preserve preset; high/preserve keep source canvas and timing; merges share the source-aware encoder budget; scale bitrate with resolution/FPS/codec and support target-size estimates. | Pure Kotlin and Swift planning checks, including 4K/fractional rates/audio-size budgets. |
| Capture frame rate and rounded caps could disagree with actual playback timing. | Read track/sample timing, preserve VFR, expose explicit cap/constant policies and retain fractional rates. | Host frame-scheduler tests; compiled VFR/CFR native regression cases. |
| Media3's default nearest-interval frame dropper can retain 30 FPS for a 24 FPS target. | Use a zero-copy time-bucket cap matching iOS's selection policy. | JVM checks for 30→24, 60→23.5, original timestamps, flush and texture ownership. |
| Constant FPS required real repeats and an exact grid, not a nominal metadata setting. | Android stages two GPU textures to avoid upstream lookahead deadlock, then reuses textures for repeats; iOS uses composition frame timing. | JVM repeat, EOS/backpressure, fractional-grid tests; compiled native combined-export checks. |
| Android silently tone-mapped HDR; high-bit-depth SDR could be downgraded. | Explicit preserve/SDR/reject policy, HEVC selection, 10-bit iOS buffers, output HDR inspection and explicit unsupported-path failure. | Planner checks, native compilation, offline Main10 HLG/PQ fixture validation. Physical-device color comparison remains pending. |
| Video effects unnecessarily re-encoded or downmixed audio. | Preserve primary compatible compressed audio; explicit stereo/mute; decoded PCM only when encoding is needed. Preserve track offsets on iOS. | Native audio configuration review, compiled mono-audio cases and delayed-audio fixture checks, fixture metadata checks. |
| Downloads and probing occupied the sole encoder slot. | Separate bounded I/O/image/encoder permits and admission limit; acquire encoder permit only around native processing. | Host queue cancellation/permit recovery checks and source review. |
| Deadlines ended before muxer finalization; cancellation could race startup or output publication. | Whole-job controls start before queueing, interrupt I/O/native exporters, stay active through finalization, and seal at commit. iOS waits for exporter completion before releasing staging/permits. | Swift cancellation/deadline/sealing checks; native compilation; no runtime cancellation benchmark claimed. |
| iOS remote downloads buffered entire assets; content providers could block opening on Android. | Chunked disk streaming with declared/actual byte budgets; provider cancellation signal, stream close/disconnect hooks and independent download permits. | Production Swift downloader compiled in host checks; native compilation. Live-network/provider stress testing remains pending. |
| Large images, thumbnails and watermark rasters could exhaust memory. | Bounded oriented decode, one concurrent still-image operation, pixel/heap budgets and pre-allocation watermark limits. | Kotlin budget tests; native compilation. GPU/thermal profiling remains pending. |
| Failed jobs and interrupted downloads exposed partial files; deletion raced active exports. | Private staging, per-file rename publication, batch rollback, input leases, restricted deletion and abandoned-job cleanup. | Host Swift staging/commit/budget/lease/idempotent-deletion checks. |
| Output metadata was sometimes substituted from source metadata. | Probe completed outputs; expose codec/HDR/depth/audio details, actual thumbnail timestamps and encoder fallback warnings. | Generated ABI and native compilation; offline validator decodes output independently. |
| One-frame watermark checks missed intermittent flicker. | Check every expected native fixture frame; add a full offline decoder/ROI/timestamp checker and aligned SSIM/PSNR support. | Checker rejects alternating missing watermarks; native regression targets compile but were not executed. |
| The portrait test input had no rotation matrix despite its name. | Generate a real display matrix; validate oriented dimensions independently. | ffprobe display matrix plus offline checker assertions. |
| Performance lacked attributable processing-path and memory observations. | Add native elapsed/size/codec/frame/path/RSS diagnostics and a grouped median benchmark summarizer. | Host/native compilation and sample aggregation check. No device speedup claim. |

## Public behavior changes

Version is now 0.5.0. `high` keeps source video dimensions/FPS. Export defaults preserve HDR, merge keeps the first clip's original canvas, and thumbnails use precise selection by default. Video jobs enforce default input/output/deadline/disk budgets. Invalid policy conflicts fail instead of silently choosing a downgrade. See [README.md](README.md) for the API, limits, migration notes and ownership contract.

Compatible unchanged video/audio can be copied. Edited video uses lossy native encoding; preserve is not a lossless codec guarantee. Dolby Vision dynamic metadata and original camera metadata are not guaranteed after processing. Unsupported preservation can fail; SDR/stereo conversions require explicit policy.

## Validation scope

Host checks cover TypeScript bindings, planning, queue/control state, file ownership, frame scheduling/texture ownership and the offline validator. Native compilation checks Swift/C++, Kotlin/C++, the iOS regression target and the Android regression APK. The host Swift boundary types mirror generated types; native builds separately check the real generated ABI.

Local checks passed: TypeScript, ESLint, 6 JavaScript tests, 18 JVM planning/frame-effect tests, the Swift host planning/control/file-ownership checks (including queue admission and partial-batch rollback), 5 offline validator self-tests, the benchmark grouping sample, Nitro/Bob package build, Android library/test-APK compilation and iOS Swift/C++ app/regression-target compilation. The package archive includes the new native helpers, generated bindings and job declarations, and excludes tests, dependencies and build artifacts.

No UI, emulator or simulator runtime tests were run for this upgrade. Native regression source/compilation is coverage preparation, not a passing device test result. The synthetic FFmpeg inputs and checker self-tests do not substitute for native output validation. Physical-device codec coverage, HDR reference comparison, cancellation during hardware finalization, multichannel layouts, large inputs and sustained thermal/memory behavior still need device validation before broad release. Normal CI compiles native targets and runs host checks; native runtime jobs require explicit manual opt-in.

The external npm advisory scan has not run. Local manifest/lockfile review does not substitute for an advisory scan.

## Retained 0.4 foundation

Nitro runtime/Nitrogen remain aligned at 0.37.1. Android uses Media3 1.10.1 instead of custom EGL/codec drain loops. The simulator-specific unchanged-copy watermark bypass remains removed; iOS composites real video frames through Core Image. UUID cache outputs, explicit watermark options, strict range validation, optional metadata filtering and the scoped glog module-map compatibility fix remain in place.

## References

- [Nitro asynchronous promises](https://nitro.margelo.com/docs/types/promises)
- [Media3 export compositions](https://developer.android.com/media/media3/transformer/composition)
- [Media3 HDR editing](https://developer.android.com/media/media3/transformer/transformations)
- [Apple HDR video pipeline guidance](https://developer.apple.com/av-foundation/Incorporating-HDR-video-with-Dolby-Vision-into-your-apps.pdf)
