#!/usr/bin/env python3
"""Real LINZ elevation for the terrain tiles of renderer/examples/triangle_audit.rs.

    python3 tools/triangle-audit.py <plan.txt> <out-dir>

<plan.txt>: lines "z x y" (the example's `plan` output). For each terrain tile it
fetches the LINZ terrain-RGB elevation tiles at the app's source zoom (one zoom
coarser, at most 18) and writes <out-dir>/<z>_<x>_<y>.f32: the 129x129 heights the
app's DemSampling.terrainMesh builds (pixel-centre bilinear over the source tiles).
The LINZ key is read from the app's map-defaults.json and never printed.
"""
import io
import json
import math
import os
import struct
import sys
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = json.load(open(os.path.join(HERE, '..', 'android/app/src/main/assets/map-defaults.json')))['linzApiKey']
cache = {}


def source(z, x, y, folder):
    """A LINZ elevation tile as 256x256 heights (NaN where transparent), cached on disk."""
    key = (z, x, y)
    if key in cache:
        return cache[key]
    path = os.path.join(folder, f'dem_{z}_{x}_{y}.png')
    if not os.path.exists(path):
        url = f'https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/{z}/{x}/{y}.png?pipeline=terrain-rgb&api={KEY}'
        with urllib.request.urlopen(url, timeout=60) as r:
            open(path, 'wb').write(r.read())
    image = Image.open(path).convert('RGBA')
    px = image.load()
    heights = [[float('nan') if px[i, j][3] == 0 else -10000 + (px[i, j][0] * 65536 + px[i, j][1] * 256 + px[i, j][2]) * 0.1 for i in range(256)] for j in range(256)]
    cache[key] = heights
    return heights


def mesh(z, x, y, folder):
    dz = min(max(z - 1, 0), 18)
    factor = 1 << (z - dz)
    n = 1 << dz
    out = []
    for j in range(129):
        py = (y * 256 + j * 2) / factor - 0.5
        iy = math.floor(py)
        fy = py - iy
        rows = [min(max(iy, 0), n * 256 - 1), min(max(iy + 1, 0), n * 256 - 1)]
        for i in range(129):
            px = (x * 256 + i * 2) / factor - 0.5
            ix = math.floor(px)
            fx = px - ix
            cols = [(ix) % (n * 256), (ix + 1) % (n * 256)]

            def h(gx, gy):
                return source(dz, gx // 256, gy // 256, folder)[gy % 256][gx % 256]
            v = (h(cols[0], rows[0]) * (1 - fx) * (1 - fy) + h(cols[1], rows[0]) * fx * (1 - fy)
                 + h(cols[0], rows[1]) * (1 - fx) * fy + h(cols[1], rows[1]) * fx * fy)
            out.append(0.0 if math.isnan(v) else v)
    return out


def main():
    plan, folder = sys.argv[1], sys.argv[2]
    os.makedirs(folder, exist_ok=True)
    tiles = [tuple(map(int, line.split())) for line in open(plan) if line.strip()]
    for z, x, y in tiles:
        heights = mesh(z, x, y, folder)
        with open(os.path.join(folder, f'{z}_{x}_{y}.f32'), 'wb') as f:
            f.write(struct.pack('<16641f', *heights))
    print(f'{len(tiles)} terrain tiles, {len(cache)} LINZ elevation tiles')


if __name__ == '__main__':
    main()
