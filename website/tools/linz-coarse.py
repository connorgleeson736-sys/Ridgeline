#!/usr/bin/env python3
"""The website's zoomed-out aerial imagery (zooms 4-8), from LINZ's current zoom-9 tiles.

    python3 -I tools/linz-coarse.py <work-dir> <out-dir>

LINZ draws a satellite mosaic at zooms 8 and coarser and its aerial photography from
zoom 9; this averages the zoom-9 photography down (premultiplied 2x2 means), so the
whole country matches what is drawn closer in, with no change of colour between zooms.
Only LINZ (CC BY 4.0). The LINZ key is read from the app's map-defaults.json and never
printed. Writes <out-dir>/<z>/<x>/<y>.webp (lossy, quality 88).
"""
import io, json, math, os, subprocess, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = json.load(open(os.path.join(HERE, '..', '..', 'native-wgpu/android/app/src/main/assets/map-defaults.json')))['linzApiKey']
WEST, EAST, SOUTH, NORTH = 165.0, 179.5, -48.0, -33.8

def tile(lon, lat, z):
    n = 2 ** z; s = math.sin(math.radians(lat))
    return int((lon + 180) / 360 * n), int((0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n)

def fetch(z, x, y, work):
    path = os.path.join(work, f'{z}_{x}_{y}.webp')
    if not os.path.exists(path):
        url = f'https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api={KEY}'
        data = subprocess.run(['curl', '-sS', '-f', '--retry', '3', url], capture_output=True).stdout
        if not data: return None
        open(path, 'wb').write(data)
    return Image.open(path).convert('RGBA')

def main():
    work, out = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    x0, y0 = tile(WEST, NORTH, 9); x1, y1 = tile(EAST, SOUTH, 9)
    level = {}
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            im = fetch(9, x, y, work)
            if im is not None: level[(x, y)] = im
    print(f'zoom 9: {len(level)} LINZ tiles')
    for z in range(8, 3, -1):
        coarser = {}
        for (x, y) in {(x // 2, y // 2) for (x, y) in level}:
            canvas = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
            for dx in (0, 1):
                for dy in (0, 1):
                    child = level.get((x * 2 + dx, y * 2 + dy))
                    if child is not None: canvas.paste(child, (dx * 256, dy * 256))
            # Premultiplied area mean: transparent outside LINZ's coverage stays so.
            pm = canvas.convert('RGBa').resize((256, 256), Image.Resampling.BOX).convert('RGBA')
            coarser[(x, y)] = pm
            os.makedirs(os.path.join(out, str(z), str(x)), exist_ok=True)
            # Beyond the zoom-9 tiles fetched (open sea): LINZ's own tile of this zoom.
            shown = pm
            if pm.getextrema()[3][0] < 255:
                own = fetch(z, x, y, work)
                if own is not None: shown = Image.alpha_composite(own, pm)
            shown.convert('RGB').save(os.path.join(out, str(z), str(x), f'{y}.webp'), 'WEBP', quality=88, method=6)
        print(f'zoom {z}: {len(coarser)} tiles')
        level = coarser

if __name__ == '__main__':
    main()
