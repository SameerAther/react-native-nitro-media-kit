#!/usr/bin/env python3
"""Exercise the offline checker itself with deliberately good/bad temporal fixtures."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('validator', Path(__file__).with_name('validate-media.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class MediaValidationTests(unittest.TestCase):
    def make_video(self, directory, name, flicker=False):
        path = Path(directory) / name
        command = ['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=black:s=160x96:r=10:d=1',
            '-vf', "drawbox=x=8:y=8:w=30:h=12:color=white:t=fill" + (":enable='not(mod(n,2))'" if flicker else ''),
            '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(path)]
        subprocess.run(command, check=True)
        return path

    def test_every_frame_checked_and_reference_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_video(directory, 'steady.mp4')
            result = validator.validate(path, fps=10, duration=1000, roi=[8, 8, 30, 12], minimum=200, reference=path)
            self.assertEqual(result['watermarkFramesChecked'], 10)
            self.assertEqual(float(result['ssim']), 1.0)

    def test_single_frame_check_would_miss_intermittent_flicker(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_video(directory, 'flicker.mp4', True)
            with self.assertRaisesRegex(ValueError, 'frame 1'):
                validator.validate(path, roi=[8, 8, 30, 12], minimum=200)

    def test_wrong_timestamp_grid_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_video(directory, 'steady.mp4')
            with self.assertRaisesRegex(ValueError, 'timestamp grid'):
                validator.validate(path, fps=24)

    def test_invalid_expectations_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_video(directory, 'steady.mp4')
            for value in [0, float('nan'), float('inf')]:
                with self.assertRaises(ValueError):
                    validator.validate(path, fps=value)
                with self.assertRaises(ValueError):
                    validator.validate(path, duration=value)

    def test_temporal_and_color_fixture_properties(self):
        fixtures = Path(__file__).resolve().parent.parent / 'test-fixtures'
        fractional = validator.validate(fixtures / 'motion-fractional.mp4', fps=30000 / 1001)
        self.assertEqual(fractional['frameCount'], 60)
        with self.assertRaisesRegex(ValueError, 'timestamp grid'):
            validator.validate(fixtures / 'motion-vfr.mp4', fps=30)
        delayed = validator.validate(fixtures / 'delayed-audio.mp4', audio_channels=1)
        self.assertGreater(delayed['audioStartMs'], 300)
        for transfer in ['hlg', 'pq']:
            result = validator.validate(fixtures / f'hdr-{transfer}.mp4', codec='hevc', hdr=True)
            self.assertEqual(result['pixelFormat'], 'yuv420p10le')
        portrait = validator.validate(fixtures / 'portrait.mp4')
        self.assertEqual((portrait['width'], portrait['height']), (180, 320))


if __name__ == '__main__':
    unittest.main()
