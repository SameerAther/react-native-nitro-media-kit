#!/usr/bin/env python3
"""Aggregate saved native results; never mixes devices, fixtures, presets or processing paths."""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
from statistics import median


def summarize(records):
    groups = defaultdict(list)
    for record in records:
        result = record['result']
        if not result.get('ok'):
            raise ValueError('Failed jobs cannot be included in performance results')
        diagnostic = result['diagnostics']
        elapsed = diagnostic['elapsedMs']
        size = diagnostic['outputSizeBytes']
        if not all(isinstance(x, (int, float)) and math.isfinite(x) and x > 0 for x in [elapsed, size]):
            raise ValueError('Elapsed time and output size must be finite and positive')
        # settingsId must identify the entire options object used for this run.
        key = tuple(record[k] for k in ['platform', 'device', 'fixture', 'settingsId']) + (diagnostic['videoPath'], diagnostic['audioPath'], diagnostic.get('codec'))
        groups[key].append(result)
    reports = []
    for key, results in sorted(groups.items(), key=lambda item: str(item[0])):
        rows = [r['diagnostics'] for r in results]
        report = dict(zip(['platform', 'device', 'fixture', 'settingsId', 'videoPath', 'audioPath', 'codec'], key))
        report.update(runs=len(rows), medianElapsedMs=median(r['elapsedMs'] for r in rows),
            medianOutputBytes=median(r['outputSizeBytes'] for r in rows))
        for field, name in [('peakProcessMemoryBytes', 'medianPeakProcessMemoryBytes'), ('frameCount', 'medianFrameCount')]:
            values = [r[field] for r in rows if r.get(field) is not None]
            if len(values) == len(rows):
                report[name] = median(values)
        ratios = [r['diagnostics']['elapsedMs'] / r['media']['durationMs'] for r in results if r.get('media', {}).get('durationMs', 0) > 0]
        if len(ratios) == len(rows):
            report['medianElapsedToMediaRatio'] = median(ratios)
        reports.append(report)
    return reports


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('records', type=Path, help='JSON array of {platform, device, fixture, settingsId, result}')
    args = parser.parse_args()
    print(json.dumps(summarize(json.loads(args.records.read_text())), indent=2))
