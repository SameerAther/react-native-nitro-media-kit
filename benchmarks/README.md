# Host performance comparison

The latest measured comparison is [0.5.0 versus the current changes on Apple M2](results/2026-10-23-apple-m2.md). Raw timings, source hashes, output checksums, run ranges and confidence intervals are in the accompanying JSON.

Run the same production Swift planner from a Git revision and the working tree:

```sh
python3 scripts/benchmark-host.py --baseline v0.5.0 \
  --runs 10 --iterations 500000 --loudness-runs 5 \
  --output /tmp/nmk-host-results.json
```

Requires macOS, Python 3.12+, Swift and FFmpeg. The baseline is exported into a temporary directory. The working tree is preserved. The script compiles both versions with `-O -whole-module-optimization`, warms each case, alternates version order, checks identical plan outputs and reports every run. Run it with other builds and tests idle. Planner percentages describe CPU planning cost only; they do not measure video export time. Each revision uses its own host boundary types, including its own options layout.

Loudness timings measure streaming PCM analysis using the production Swift meter. They exclude decoding, encoding, disk I/O and the Nitro boundary. The release has no corresponding loudness feature, so these are current-version measurements without a speedup comparison. Peak RSS is for the entire isolated benchmark process, including the harness and runtime; it is not the library's incremental memory use in an app.

Native pipeline comparisons require physical-device runs using identical input bytes, options, release builds, OS, device and thermal conditions. The existing `scripts/summarize-benchmarks.py` aggregates saved native diagnostics by device, fixture, settings, codec and processing path. Retain failures and use `scripts/validate-media.py` to check timestamps, color, audio and watermark frames. The host benchmark does not establish native export speed, app responsiveness, energy use, HDR fidelity or absence of flicker.
