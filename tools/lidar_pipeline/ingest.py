"""Download authentic LAZ and retain exact original records inside the ROI.

Run from repository root: python -m tools.lidar_pipeline.ingest --root PATH
Remote checkpoints are uploaded by the agent through connected Drive tools.
The uploader must verify a complete Drive SHA-256 readback before acknowledgement.
"""
from __future__ import annotations
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

import geopandas as gpd
import laspy
import numpy as np
from shapely.geometry import box

from .state import State, atomic_json, digest, key


def download(url, destination):
    """Resume HTTP ranges; never append a server's ignored-range full response."""
    destination = Path(destination)
    temp = destination.with_suffix(destination.suffix + '.part')
    destination.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(5):
        offset = temp.stat().st_size if temp.exists() else 0
        try:
            req = urllib.request.Request(url, headers={'Range': f'bytes={offset}-'} if offset else {})
            with urllib.request.urlopen(req, timeout=90) as response:
                if offset and (response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {offset}-')):
                    offset = 0
                expected = response.headers.get('Content-Length')
                received = 0
                with temp.open('ab' if offset else 'wb') as f:
                    while data := response.read(1 << 20):
                        f.write(data)
                        received += len(data)
                    f.flush()
                    import os
                    os.fsync(f.fileno())
                if expected and received != int(expected):
                    raise IOError('Truncated source HTTP body')
            temp.replace(destination)
            return destination
        except (OSError, urllib.error.URLError):
            if attempt == 4: raise
            time.sleep(min(2 ** attempt, 10))


def discover(state):
    config = state.config
    index = state.root / 'data' / 'source-index.zip'
    if not index.exists(): download(config['source_index_url'], index)
    frame = gpd.read_file(index)
    if frame.crs is None: raise ValueError('Source index has no CRS; refusing an assumed CRS')
    frame = frame.to_crs(2193)
    selected = frame[frame.intersects(box(*config['bounds']))].sort_values('file_name')
    if not len(selected): raise ValueError('No original point clouds cover the requested ROI')
    roi = box(*config['bounds'])
    coverage = selected.geometry.union_all().intersection(roi).area / roi.area
    # Index polygons follow first/last quantized XY positions, leaving 1 cm
    # strips between delivery tiles. Retain the measured coverage fraction;
    # do not mislabel these index-edge strips as a missing survey block.
    if coverage < .9999: raise ValueError(f'Incomplete source index coverage: {coverage}')
    sources = []
    for _, row in selected.iterrows():
        url = row['URL']
        if not url.startswith('https://opentopography.s3.sdsc.edu/pc-bulk/' + config['source_collection'] + '/'):
            raise ValueError('Unexpected source host or collection')
        sources.append(dict(name=row['file_name'], url=url, expected_points=int(row['num_points'])))
    output = state.root / 'data' / 'sources.json'
    atomic_json(output, dict(schema=1, sources=sources, index_sha256=digest(index),
                            coverage_fraction=coverage,
                            coverage_note='Index extent coverage; centimetre edge strips remain. Actual surface coverage is measured after reconstruction.',
                            config=config))
    signature = key(dict(stage='discover-v1', config=config, index=digest(index)))
    if not state.valid('discover', signature):
        state.stage('discover', signature, [index, output], dict(files=len(sources), coverage=coverage))
    return sources


def ingest_one(state, source):
    config = state.config
    name = 'raw-' + Path(source['name']).stem
    signature = key(dict(stage='ingest-v1', source=source, bounds=config['bounds']))
    with state.lock(name):
        if state.valid(name, signature): return dict(task=name, skipped=True)
        state.start(name, signature)
        try:
            raw = state.root / 'downloads' / source['name']
            if not raw.exists(): download(source['url'], raw)
            raw_hash = digest(raw)
            cropped = state.root / 'data' / 'raw' / source['name']
            cropped.parent.mkdir(parents=True, exist_ok=True)
            temp = cropped.with_suffix('.partial.laz')
            classes, kept, total = Counter(), 0, 0
            withheld, invalid = 0, 0
            xmin, ymin, xmax, ymax = config['bounds']
            ranges = [np.full(3, np.inf), np.full(3, -np.inf)]
            with laspy.open(raw) as reader:
                header = reader.header
                crs = header.parse_crs()
                if crs is None or 'NZVD2016' not in str(crs) or '2193' not in str(crs):
                    raise ValueError('Original cloud has unverified horizontal/vertical CRS')
                if header.point_count != source['expected_points']:
                    raise ValueError('Original cloud count disagrees with tile-index metadata')
                with laspy.open(temp, mode='w', header=header) as writer:
                    for chunk in reader.chunk_iterator(500000):
                        total += len(chunk)
                        xyz = np.column_stack((chunk.x, chunk.y, chunk.z))
                        mask = (xyz[:,0] >= xmin) & (xyz[:,0] <= xmax) & (xyz[:,1] >= ymin) & (xyz[:,1] <= ymax)
                        xyz = xyz[mask]
                        if not len(xyz): continue
                        # Do not silently drop a nonfinite source observation.
                        if not np.isfinite(xyz).all():
                            invalid += int((~np.isfinite(xyz).all(axis=1)).sum())
                        writer.write_points(chunk[mask])
                        kept += len(xyz)
                        classes.update(map(int, np.asarray(chunk.classification)[mask]))
                        withheld += int(np.asarray(chunk.withheld)[mask].sum())
                        ranges[0] = np.minimum(ranges[0], xyz.min(axis=0))
                        ranges[1] = np.maximum(ranges[1], xyz.max(axis=0))
            with laspy.open(temp) as check:
                if check.header.point_count != kept: raise ValueError('Cropped LAZ count readback failed')
            temp.replace(cropped)
            audit = dict(source=source, original_sha256=raw_hash, crop_sha256=digest(cropped),
                         crop_points=kept, original_points_read=total, classification=dict(classes),
                         withheld=withheld, nonfinite=invalid, bounds=[r.tolist() for r in ranges],
                         crs=str(crs), scales=header.scales.tolist(), offsets=header.offsets.tolist(),
                         retained_fields='All original LAS point-record fields; only spatial cropping applied',
                         original_url=source['url'], license='CC BY 4.0; LINZ and survey contributors')
            audit_path = cropped.with_suffix('.audit.json')
            atomic_json(audit_path, audit)
            result = state.stage(name, signature, [cropped, audit_path], dict(points=kept, bytes=cropped.stat().st_size))
            print(json.dumps(dict(event='checkpoint_ready', **result)), flush=True)
            return result
        except Exception as error:
            state.fail(name, error)
            raise


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--root', required=True)
    p.add_argument('--config', default=str(Path(__file__).with_name('aoraki.json')))
    p.add_argument('--workers', type=int, default=2)
    p.add_argument('--limit', type=int, default=0)
    args = p.parse_args()
    config = json.loads(Path(args.config).read_text())
    state = State(args.root, config)
    sources = discover(state)
    if args.limit: sources = sources[:args.limit]
    with ThreadPoolExecutor(max_workers=min(2, max(1,args.workers))) as pool:
        list(pool.map(lambda source: ingest_one(state, source), sources))
    print(json.dumps(dict(event='ingest_staged', counts=state.manifest()['counts'])), flush=True)


if __name__ == '__main__': main()
