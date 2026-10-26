"""Download a small, reproducible authentic source fixture; never print the key.

Usage: python tools/fetch-three-check-data.py /absolute/scratch/output
Requires numpy and Pillow. This script is QA only and is not deployed.
"""
import concurrent.futures
import hashlib
import json
import math
import pathlib
import re
import sys
import urllib.request

import numpy as np
from PIL import Image

destination = pathlib.Path(sys.argv[1]).resolve()
destination.mkdir(parents=True, exist_ok=True)
key = re.search(r"const linzApiKey='([^']+)'", pathlib.Path('public/app.js').read_text())[1]
jobs = []
for zoom in (16, 18):
    n = 2 ** zoom
    x = int((170.141 + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(-43.595))) / math.pi) / 2 * n)
    jobs.extend((zoom, x + dx, y + dy) for dy in range(-1, 3) for dx in range(-1, 3))


def fetch_tile(tile):
    zoom, x, y = tile
    file = destination / f'{zoom}-{x}-{y}.png'
    if not file.exists():
        url = f'https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/{zoom}/{x}/{y}.png?pipeline=terrain-rgb&api={key}'
        data = urllib.request.urlopen(url, timeout=35).read()
        temporary = file.with_suffix('.pending')
        temporary.write_bytes(data)
        temporary.replace(file)
    rgba = np.asarray(Image.open(file).convert('RGBA'))
    heights = (-10000 + (rgba[:, :, 0].astype('float64') * 65536 + rgba[:, :, 1].astype('float64') * 256 + rgba[:, :, 2]) * .1).astype('<f4')
    heights[rgba[:, :, 3] == 0] = np.nan
    file.with_suffix('.f32').write_bytes(heights.tobytes())
    return dict(z=zoom, x=x, y=y, bytes=file.stat().st_size, sha256=hashlib.sha256(file.read_bytes()).hexdigest(), valid=int(np.isfinite(heights).sum()), min=float(np.nanmin(heights)), max=float(np.nanmax(heights)))


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    results = list(executor.map(fetch_tile, jobs))
(destination / 'tiles.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps(dict(authenticTiles=len(results), validSamples=sum(t['valid'] for t in results))))
