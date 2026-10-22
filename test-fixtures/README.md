Synthetic fixtures generated locally with FFmpeg; no third-party media.

- `landscape.mp4`: black 320×180 H.264 video, 10 FPS, two seconds, mono AAC sine-wave audio and a synthetic ISO 6709 location tag.
- `portrait.mp4`: same encoded video with a 90-degree display rotation tag (180×320 displayed).
- `photo.png`: opaque red 320×180 image.

Regenerate from the repository root:

```sh
ffmpeg -y -f lavfi -i 'color=c=black:s=320x180:r=10:d=2' -f lavfi -i 'sine=frequency=440:duration=2' -c:v libx264 -pix_fmt yuv420p -c:a aac -ac 1 -shortest -metadata location="+37.3318-122.0312/" -movflags +faststart test-fixtures/landscape.mp4
ffmpeg -y -i test-fixtures/landscape.mp4 -c copy -metadata:s:v:0 rotate=90 test-fixtures/portrait.mp4
ffmpeg -y -f lavfi -i 'color=c=red:s=320x180' -frames:v 1 test-fixtures/photo.png
```
