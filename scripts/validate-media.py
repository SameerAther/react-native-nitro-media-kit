#!/usr/bin/env python3
"""Inspect native-generated outputs offline; never substitutes FFmpeg exports for native validation."""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import sys


def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True)


def inspect(path):
    result = json.loads(
        run(
            [
                'ffprobe',
                '-v',
                'error',
                '-show_streams',
                '-show_format',
                '-of',
                'json',
                str(path),
            ]
        ).stdout
    )
    video = next(s for s in result['streams'] if s['codec_type'] == 'video')
    frames = json.loads(
        run(
            [
                'ffprobe',
                '-v',
                'error',
                '-select_streams',
                'v:0',
                '-show_frames',
                '-show_entries',
                'frame=best_effort_timestamp_time',
                '-of',
                'json',
                str(path),
            ]
        ).stdout
    )['frames']
    times = [float(f['best_effort_timestamp_time']) for f in frames]
    if (
        not times
        or any(not math.isfinite(t) for t in times)
        or any(b <= a for a, b in zip(times, times[1:]))
    ):
        raise ValueError(
            'Missing, nonfinite, repeated or decreasing presentation timestamps'
        )
    run(
        [
            'ffmpeg',
            '-v',
            'error',
            '-xerror',
            '-err_detect',
            'explode',
            '-i',
            str(path),
            '-f',
            'null',
            '-',
        ]
    )
    duration = float(result['format']['duration']) * 1000
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError('Invalid container duration')
    rotation = (
        next(
            (
                float(d['rotation'])
                for d in video.get('side_data_list', [])
                if 'rotation' in d
            ),
            0,
        )
        % 360
    )
    width, height = video['width'], video['height']
    if abs(rotation - 90) < 0.01 or abs(rotation - 270) < 0.01:
        width, height = height, width
    audio = next((s for s in result['streams'] if s['codec_type'] == 'audio'), {})
    return {
        'width': width,
        'height': height,
        'codec': video['codec_name'],
        'pixelFormat': video['pix_fmt'],
        'colorPrimaries': video.get('color_primaries'),
        'colorTransfer': video.get('color_transfer'),
        'frameCount': len(times),
        'durationMs': duration,
        'sizeBytes': Path(path).stat().st_size,
        'firstTimestampMs': times[0] * 1000,
        'lastTimestampMs': times[-1] * 1000,
        'audioChannels': audio.get('channels', 0),
        'audioSampleRate': audio.get('sample_rate'),
        'audioStartMs': float(audio.get('start_time', 0)) * 1000,
        'audioTracks': sum(s['codec_type'] == 'audio' for s in result['streams']),
        'timestamps': times,
    }


def watermark_frames(path, roi, minimum):
    x, y, w, h = roi
    if (
        min(x, y) < 0
        or min(w, h) <= 0
        or w * h > 4_194_304
        or minimum <= 0
        or minimum > w * h
    ):
        raise ValueError('Invalid watermark ROI')
    command = [
        'ffmpeg',
        '-v',
        'error',
        '-xerror',
        '-i',
        str(path),
        '-vf',
        f'crop={w}:{h}:{x}:{y},format=gray',
        '-fps_mode',
        'passthrough',
        '-f',
        'rawvideo',
        '-',
    ]
    with subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    ) as process:
        index = 0
        while True:
            pixels = process.stdout.read(w * h)
            if not pixels:
                break
            if len(pixels) != w * h:
                process.kill()
                raise ValueError('Incomplete decoded frame')
            if sum(p > 180 for p in pixels) < minimum:
                process.kill()
                raise ValueError(f'Watermark is missing/dim at decoded frame {index}')
            index += 1
        if process.wait() != 0:
            raise ValueError(process.stderr.read().decode())
        if index == 0:
            raise ValueError('No watermark frames decoded')
        return index


def validate(
    path,
    fps=None,
    duration=None,
    roi=None,
    minimum=20,
    reference=None,
    diagnostics=None,
    *,
    codec=None,
    hdr=None,
    audio_channels=None,
):
    report = inspect(path)
    times = report.pop('timestamps')
    if fps is not None:
        if not math.isfinite(fps) or fps <= 0:
            raise ValueError('FPS must be finite and positive')
        if any(abs((t - times[0]) - i / fps) > 0.00001 for i, t in enumerate(times)):
            raise ValueError(
                'Output does not follow the expected constant-rate timestamp grid'
            )
    if duration is not None:
        if not math.isfinite(duration) or duration <= 0:
            raise ValueError('Expected duration must be finite and positive')
        if abs(report['durationMs'] - duration) > 100:
            raise ValueError('Unexpected output duration')
    if codec is not None and report['codec'] != codec:
        raise ValueError('Unexpected output codec')
    is_hdr = report['colorTransfer'] in ('arib-std-b67', 'smpte2084')
    if hdr is not None and is_hdr != hdr:
        raise ValueError('Unexpected HDR/SDR transfer signaling')
    if audio_channels is not None and report['audioChannels'] != audio_channels:
        raise ValueError('Unexpected primary audio channel count')
    if roi:
        if roi[0] + roi[2] > report['width'] or roi[1] + roi[3] > report['height']:
            raise ValueError('ROI exceeds video dimensions')
        report['watermarkFramesChecked'] = watermark_frames(path, roi, minimum)
    if reference:
        ref = inspect(reference)
        if (ref['width'], ref['height'], ref['frameCount']) != (
            report['width'],
            report['height'],
            report['frameCount'],
        ):
            raise ValueError(
                'Quality reference must have matching dimensions and frame count'
            )
        if any(
            abs((a - times[0]) - (b - ref['timestamps'][0])) > 0.00001
            for a, b in zip(times, ref['timestamps'])
        ):
            raise ValueError(
                'Quality reference must be aligned to output presentation timestamps'
            )
        for metric in ['ssim', 'psnr']:
            output = run(
                [
                    'ffmpeg',
                    '-v',
                    'info',
                    '-i',
                    str(path),
                    '-i',
                    str(reference),
                    '-lavfi',
                    f'[0:v]setpts=PTS-STARTPTS[a];[1:v]setpts=PTS-STARTPTS[b];[a][b]{metric}',
                    '-an',
                    '-f',
                    'null',
                    '-',
                ]
            ).stderr
            matches = re.findall(
                r'All:([\d.]+)' if metric == 'ssim' else r'average:([\d.]+|inf)', output
            )
            if not matches:
                raise ValueError(f'Unable to compute {metric}')
            report[metric] = matches[-1]
    if diagnostics:
        report['nativeDiagnostics'] = json.loads(Path(diagnostics).read_text())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--expected-fps', type=float)
    parser.add_argument('--expected-duration-ms', type=float)
    parser.add_argument(
        '--watermark-roi', type=int, nargs=4, metavar=('X', 'Y', 'W', 'H')
    )
    parser.add_argument('--min-white-pixels', type=int, default=20)
    parser.add_argument('--expected-codec', choices=['h264', 'hevc'])
    parser.add_argument('--expected-color', choices=['hdr', 'sdr'])
    parser.add_argument('--expected-audio-channels', type=int)
    parser.add_argument(
        '--reference',
        type=Path,
        help='Already aligned reference with identical timing/dimensions',
    )
    parser.add_argument(
        '--diagnostics',
        type=Path,
        help='Saved ExportDiagnostics JSON from the native job',
    )
    args = parser.parse_args()
    try:
        print(
            json.dumps(
                validate(
                    args.file,
                    args.expected_fps,
                    args.expected_duration_ms,
                    args.watermark_roi,
                    args.min_white_pixels,
                    args.reference,
                    args.diagnostics,
                    codec=args.expected_codec,
                    hdr=(
                        None
                        if args.expected_color is None
                        else args.expected_color == 'hdr'
                    ),
                    audio_channels=args.expected_audio_channels,
                ),
                indent=2,
            )
        )
    except (
        ValueError,
        subprocess.CalledProcessError,
        KeyError,
        StopIteration,
    ) as error:
        print(f'Invalid media: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
