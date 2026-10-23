#!/usr/bin/env python3
"""Generate tiny, deterministic temporal/color inputs with FFmpeg; no third-party media."""
from array import array
from pathlib import Path
import sys
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / 'test-fixtures'


def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)], check=True)


def main():
    DEST.mkdir(exist_ok=True)
    ffmpeg('-f', 'lavfi', '-i', 'color=c=black:s=320x180:r=10:d=2', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
        '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-ac', '1', '-shortest',
        '-metadata', 'location=+37.3318-122.0312/', '-movflags', '+faststart', DEST / 'landscape.mp4')
    ffmpeg('-display_rotation:v:0', '90', '-i', DEST / 'landscape.mp4', '-c', 'copy', DEST / 'portrait.mp4')
    ffmpeg('-f', 'lavfi', '-i', 'color=c=red:s=320x180', '-frames:v', '1', DEST / 'photo.png')
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / 'motion.rgb'
        # Top-left stays black. Lower half contains motion and a six-bit frame counter.
        with raw.open('wb') as output:
            for frame in range(60):
                pixels = bytearray(160 * 96 * 3)
                for y in range(50, 90):
                    for x in range(160):
                        color = (210, 45, 20) if (x - frame * 3) % 160 < 16 else (15, 25, 35)
                        if y >= 76 and x < 72:
                            color = (230, 230, 230) if frame & (1 << (x // 12)) else (25, 25, 25)
                        start = (y * 160 + x) * 3
                        pixels[start:start + 3] = bytes(color)
                output.write(pixels)
        motion = DEST / 'motion.mp4'
        ffmpeg('-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '160x96', '-r', '30', '-i', raw,
            '-c:v', 'libx264', '-crf', '12', '-pix_fmt', 'yuv420p', '-video_track_timescale', '30000', motion)
        ffmpeg('-i', motion, '-vf', "select='if(lt(n,30),not(mod(n,2)),1)'", '-fps_mode', 'vfr',
            '-c:v', 'libx264', '-crf', '12', '-video_track_timescale', '30000', DEST / 'motion-vfr.mp4')
        ffmpeg('-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', '160x96', '-r', '30000/1001', '-i', raw,
            '-c:v', 'libx264', '-crf', '12', '-pix_fmt', 'yuv420p', '-video_track_timescale', '30000', DEST / 'motion-fractional.mp4')
        ffmpeg('-i', motion, '-itsoffset', '0.35', '-f', 'lavfi', '-i', 'sine=frequency=660:duration=1.5',
            '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy', '-c:a', 'aac', '-ac', '1', '-t', '2', DEST / 'delayed-audio.mp4')
        # Neutral 10-bit code-value ramps exercise precision and transfer signaling.
        # These are diagnostic patterns, not calibrated perceptual/color references.
        hdr = Path(directory) / 'hdr.yuv'
        with hdr.open('wb') as output:
            for frame in range(60):
                y = array('H', [768 if (x - frame * 3) % 160 < 16 and row > 48 else 64 + x * 876 // 159
                    for row in range(96) for x in range(160)])
                uv = array('H', [512] * (160 * 96 // 2))
                if sys.byteorder != 'little':
                    y.byteswap(); uv.byteswap()
                output.write(y.tobytes()); output.write(uv.tobytes())
        for name, transfer in [('hlg', 'arib-std-b67'), ('pq', 'smpte2084')]:
            ffmpeg('-f', 'rawvideo', '-pix_fmt', 'yuv420p10le', '-s', '160x96', '-r', '30', '-i', hdr,
                '-c:v', 'libx265', '-preset', 'fast', '-crf', '16', '-x265-params', f'pools=1:frame-threads=1:log-level=error:colorprim=9:transfer={18 if name == "hlg" else 16}:colormatrix=9',
                '-tag:v', 'hvc1', '-color_primaries', 'bt2020', '-color_trc', transfer, '-colorspace', 'bt2020nc', DEST / f'hdr-{name}.mp4')
    print('Generated motion, VFR, fractional FPS, delayed mono audio, HLG and PQ fixtures.')


if __name__ == '__main__':
    main()
