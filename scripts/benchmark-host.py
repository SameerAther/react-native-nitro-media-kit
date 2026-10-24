#!/usr/bin/env python3
"""Compare optimized native host code. These are not device/export benchmarks."""
import argparse
import hashlib
import io
import json
import platform
from pathlib import Path
import random
import re
import statistics
import subprocess
import tarfile
import tempfile
import time


ROOT = Path(__file__).resolve().parent.parent
HARNESS = ROOT / 'benchmarks/host/HostBenchmark.swift'


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def compile_host(root, destination):
    sources = [root / 'ios/ExportPlanner.swift', root / 'tests/host/PlannerTypes.swift']
    for name in ['EditingPlan', 'LoudnessMeter']:
        source = root / f'ios/{name}.swift'
        if source.exists():
            sources.append(source)
    digest = hashlib.sha256()
    for path in [*sources, HARNESS]:
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    flags = ['-D', 'NMK_AFTER'] if (root / 'ios/LoudnessMeter.swift').exists() else []
    subprocess.run(
        [
            'swiftc',
            '-O',
            '-whole-module-optimization',
            '-parse-as-library',
            '-module-cache-path',
            str(destination.parent / 'modules'),
            *flags,
            *map(str, sources),
            str(HARNESS),
            '-o',
            str(destination),
        ],
        check=True,
    )
    return digest.hexdigest()


def timed(binary, args):
    # Darwin time reports the peak RSS of this child, not an accumulated child-process maximum.
    result = subprocess.run(
        ['/usr/bin/time', '-l', str(binary), *args],
        text=True,
        capture_output=True,
        check=True,
    )
    match = re.search(r'(\d+)\s+maximum resident set size', result.stderr)
    if not match:
        raise RuntimeError(f'Cannot read child peak RSS: {result.stderr}')
    return {
        'measurements': json.loads(result.stdout),
        'processPeakResidentBytes': int(match[1]),
    }


def summary(values):
    return {'median': statistics.median(values), 'min': min(values), 'max': max(values)}


def ratio_interval(before, after):
    # Paired resampling retains the alternating-run pairing; this describes run variability only.
    rng = random.Random(1770)
    ratios = []
    for _ in range(5000):
        picks = rng.choices(range(len(before)), k=len(before))
        ratios.append(
            statistics.median(after[i] for i in picks)
            / statistics.median(before[i] for i in picks)
        )
    ratios.sort()
    return [ratios[int(len(ratios) * q)] for q in [0.025, 0.975]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='v0.5.0')
    parser.add_argument('--runs', type=int, default=10)
    parser.add_argument('--iterations', type=int, default=500000)
    parser.add_argument('--loudness-runs', type=int, default=5)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Darwin':
        parser.error(
            'This harness measures the production Swift implementation on macOS.'
        )
    if min(args.runs, args.iterations, args.loudness_runs) < 1:
        parser.error('Run and iteration counts must be positive.')
    baseline_sha = command(
        ['git', 'rev-parse', f'{args.baseline}^{{commit}}'], cwd=ROOT
    )
    report = {
        'scope': 'macOS host CPU only; no codec/GPU/Nitro bridge, native output quality or device performance measured',
        'createdUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'baselineRef': args.baseline,
        'baselineCommit': baseline_sha,
        'afterBaseCommit': command(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
        'host': {
            'model': command(['sysctl', '-n', 'hw.model']),
            'cpu': command(['sysctl', '-n', 'machdep.cpu.brand_string']),
            'memoryBytes': int(command(['sysctl', '-n', 'hw.memsize'])),
            'os': command(['sw_vers']),
            'swift': command(['swiftc', '--version']),
        },
        'method': {
            'optimization': '-O -whole-module-optimization',
            'runs': args.runs,
            'iterationsPerCasePerRun': args.iterations,
            'warmupIterationsPerCase': 10000,
            'order': 'before/after on even runs, after/before on odd runs',
            'timer': 'DispatchTime.uptimeNanoseconds inside child; compile, startup and fixture creation excluded',
            'boundaryTypes': 'each revision uses its own tests/host/PlannerTypes.swift',
        },
    }
    with tempfile.TemporaryDirectory(prefix='nmk-host-benchmark-') as temp:
        temp = Path(temp)
        before = temp / 'before'
        before.mkdir()
        archive = subprocess.check_output(['git', 'archive', baseline_sha], cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(before, filter='data')
        binaries = {key: temp / key for key in ['before-bench', 'after-bench']}
        report['sourceSha256'] = {
            'before': compile_host(before, binaries['before-bench']),
            'after': compile_host(ROOT, binaries['after-bench']),
        }
        binaries = {key.replace('-bench', ''): value for key, value in binaries.items()}
        raw = {'before': [], 'after': []}
        # Untimed full-process warmups; no other tests/builds should run concurrently.
        for binary in binaries.values():
            timed(binary, ['planner', '10000'])
        for run in range(args.runs):
            for revision in (
                ['before', 'after'] if run % 2 == 0 else ['after', 'before']
            ):
                raw[revision].append(
                    timed(binaries[revision], ['planner', str(args.iterations)])
                )
            print(f'Planner pair {run + 1}/{args.runs}', flush=True)
        report['plannerRawRuns'] = raw
        comparisons = []
        for case in range(len(raw['before'][0]['measurements'])):
            rows = {
                key: [run['measurements'][case] for run in runs]
                for key, runs in raw.items()
            }
            reference = rows['before'][0]
            for row in rows['before'] + rows['after']:
                if (
                    row['output'] != reference['output']
                    or row['checksum'] != reference['checksum']
                ):
                    raise RuntimeError(
                        f'Planner output changed for {reference["name"]}; timings are not comparable'
                    )
            values = {
                key: [row['nsPerOperation'] for row in entries]
                for key, entries in rows.items()
            }
            b, a = (statistics.median(values[key]) for key in ['before', 'after'])
            comparisons.append(
                {
                    'name': reference['name'],
                    'beforeNs': summary(values['before']),
                    'afterNs': summary(values['after']),
                    'medianDeltaPercent': (a / b - 1) * 100,
                    'medianDeltaNs': a - b,
                    'pairedBootstrapRatio95': ratio_interval(
                        values['before'], values['after']
                    ),
                    'identicalOutput': True,
                }
            )
        report['plannerComparison'] = comparisons
        report['plannerProcessPeakResidentBytes'] = {
            key: summary([run['processPeakResidentBytes'] for run in runs])
            for key, runs in raw.items()
        }
        loudness = []
        for rate, channels, seconds in [
            (48000, 1, 60),
            (48000, 2, 60),
            (192000, 2, 10),
        ]:
            runs = [
                timed(
                    binaries['after'],
                    ['loudness', str(rate), str(channels), str(seconds)],
                )
                for _ in range(args.loudness_runs)
            ]
            loudness.append(
                {
                    'name': runs[0]['measurements']['name'],
                    'baseline': 'feature absent in 0.5.0',
                    'elapsedMs': summary(
                        [r['measurements']['elapsedMs'] for r in runs]
                    ),
                    'realtimeMultiple': summary(
                        [r['measurements']['realtimeMultiple'] for r in runs]
                    ),
                    'processPeakResidentBytes': summary(
                        [r['processPeakResidentBytes'] for r in runs]
                    ),
                    'runs': runs,
                }
            )
            print(
                f'Loudness {rate} Hz / {channels} channels / {seconds} seconds complete',
                flush=True,
            )
        report['loudnessAfterOnly'] = loudness
        pcm = temp / 'tone.f32le'
        command([str(binaries['after']), 'tone', str(pcm)])
        reference = subprocess.run(
            [
                'ffmpeg',
                '-hide_banner',
                '-nostats',
                '-f',
                'f32le',
                '-ar',
                '48000',
                '-ac',
                '2',
                '-i',
                str(pcm),
                '-af',
                'ebur128=peak=true',
                '-f',
                'null',
                '-',
            ],
            text=True,
            capture_output=True,
            check=True,
        )
        native = timed(binaries['after'], ['loudness', '48000', '2', '3'])[
            'measurements'
        ]
        report['loudnessReference'] = {
            'fixture': '3s 1kHz, 48kHz stereo, -20dBFS sine',
            'swift': native,
            'ffmpegVersion': command(['ffmpeg', '-version']).splitlines()[0],
            'ffmpegSummary': reference.stderr[reference.stderr.rfind('Summary:') :],
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Results saved to {args.output}')


if __name__ == '__main__':
    main()
