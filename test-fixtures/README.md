# Diagnostic media fixtures

All inputs are generated locally; no third-party media is included.

| File | Purpose |
| --- | --- |
| `landscape.mp4` | Black 320×180 H.264, 10 FPS, two seconds, mono AAC and a synthetic ISO 6709 location tag. White watermarks can be checked without source-pixel false positives. |
| `portrait.mp4` | Same encoded source with a real 90-degree display matrix: 180×320 displayed. |
| `photo.png` | Opaque red 320×180 still image. |
| `motion.mp4` | 160×96, 30 FPS, 60 frames with a moving bar and six-bit frame counter in the lower half. |
| `motion-vfr.mp4` | Moving source with alternating frames removed in the first second; 15 FPS then 30 FPS. Detects timestamp flattening and missing frames. |
| `motion-fractional.mp4` | 60 moving frames at 30000/1001 FPS with an exact 30000 time base. |
| `delayed-audio.mp4` | Moving video with mono AAC starting about 350 ms late; tests track offsets and trimming before audio begins. AAC priming affects the exact start. |
| `hdr-hlg.mp4`, `hdr-pq.mp4` | HEVC Main10 neutral code-value ramps and motion, BT.2020 matrix/primaries with HLG or PQ transfer signaling. Diagnostic inputs, not calibrated perceptual/color references. |

Regenerate from the repository root using FFmpeg with libx264, libx265 and the `display_rotation` input option:

```sh
yarn fixtures:generate
yarn test:validator
```

The generator writes explicit x265 VUI color parameters. Merely setting generic output color options did not retain transfer/primaries with the local FFmpeg build. Likewise, the former `-metadata:s:v rotate=90` command produced no display matrix; `display_rotation` is used and the checker asserts displayed dimensions.

The offline tests verify decoded timing, frame counts, display rotation, mono audio offsets and Main10/HDR signaling. They also synthesize alternating missing watermarks to prove that checking only one frame misses flicker. Run the validator on saved **native outputs** to assess the export pipeline. Passing fixture/checker tests alone does not establish native device correctness.
