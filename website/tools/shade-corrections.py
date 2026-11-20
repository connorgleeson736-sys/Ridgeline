"""Builds the aerial imagery's shading corrections (see src/js/workers/imagery.js).

LINZ darkens its aerial tiles to zoom 14 with two layers, black with alpha: the
8 m DEM hillshade and texture shade (GeographX, CC BY 4.0). Drawing black at alpha
a over a colour c gives c*(1-a), so a tile's own colour is the tile divided by
keep = (1-a1)(1-a2). This fetches both layers once, for every tile they cover
(found zoom by zoom from the tiles above), and stores keep*255 as greyscale
blocks of 4x4 tiles (1024 px, WebP quality 95: within 0.3% on average, 255
where nothing is shaded):

    <out>/<z>/<bx>/<by>.webp   tiles x = bx*4 .. bx*4+3, y = by*4 .. by*4+3
    <out>/index.json           {"zooms": {"<z>": ["bx/by", ...]}}

Usage: python3 -I tools/shade-corrections.py OUT_DIR MAX_ZOOM
The LINZ key comes from LINZ_API_KEY or the app's map-defaults.json; it is never
printed. Resumable: finished blocks and zooms are kept.
"""
import io, json, os, ssl, sys, time, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

LAYERS = ['geographx-nz-dem-2012-8m', 'geographx-nz-texture-shade-2012-8m']
LINZ = 'https://basemaps.linz.govt.nz/v1/tiles'
B = 4  # tiles per block side
HERE = os.path.dirname(os.path.abspath(__file__))


def api_key():
    k = os.environ.get('LINZ_API_KEY')
    if k:
        return k
    p = os.path.join(HERE, '../../native-wgpu/android/app/src/main/assets/map-defaults.json')
    return json.load(open(p))['linzApiKey']


KEY = api_key()
CTX = ssl.create_default_context(cafile=os.environ.get('SSL_CERT_FILE') or (os.path.exists('/root/.ccr/ca-bundle.crt') and '/root/.ccr/ca-bundle.crt') or None)


def alpha(layer, z, x, y):
    """The layer's alpha (uint8 256x256), or None where it draws nothing."""
    url = f'{LINZ}/{layer}/WebMercatorQuad/{z}/{x}/{y}.png?api={KEY}'
    for attempt in range(8):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'shade-corrections/1'})
            with urllib.request.urlopen(req, timeout=60, context=CTX) as r:
                if r.status == 204:
                    return None
                body = r.read()
            if not body:
                return None
            a = np.asarray(Image.open(io.BytesIO(body)).convert('RGBA'))[..., 3]
            if a.shape != (256, 256):
                a = np.asarray(Image.fromarray(a).resize((256, 256), Image.BILINEAR))
            return a if a.any() else None
        except urllib.error.HTTPError as e:
            if e.code in (204, 404):
                return None
            err = f'HTTP {e.code}'
        except Exception as e:  # network: retry
            err = type(e).__name__
        time.sleep(min(60, 2 ** attempt))
    raise RuntimeError(f'{layer} {z}/{x}/{y}: {err}')


def keep(t):
    z, x, y = t
    k = None
    for layer in LAYERS:
        a = alpha(layer, z, x, y)
        if a is not None:
            f = 1 - a.astype(np.float32) / 255
            k = f if k is None else k * f
    if k is None:
        return t, None
    v = np.clip(np.rint(k * 255), 0, 255).astype(np.uint8)
    return t, (None if (v == 255).all() else v)


def main():
    out, max_zoom = sys.argv[1], int(sys.argv[2])
    os.makedirs(out, exist_ok=True)
    index_path = os.path.join(out, 'index.json')
    index = json.load(open(index_path)) if os.path.exists(index_path) else {'zooms': {}}
    state_path = os.path.join(out, 'covered.json')  # shaded tiles per zoom, for the next zoom's candidates
    covered = json.load(open(state_path)) if os.path.exists(state_path) else {}
    pool = ThreadPoolExecutor(int(os.environ.get('SHADE_THREADS', '24')))
    for z in range(0, max_zoom + 1):
        if str(z) in index['zooms']:
            continue
        if z == 0:
            cands = [(0, 0, 0)]
        else:
            cands = [(z, 2 * x + dx, 2 * y + dy) for x, y in covered[str(z - 1)] for dx in (0, 1) for dy in (0, 1)]
        blocks = {}
        for t in cands:
            blocks.setdefault((t[1] // B, t[2] // B), []).append(t)
        start, n, written = time.time(), 0, 0
        todo = sorted(blocks)
        pending = [k for k in todo if not any(os.path.exists(os.path.join(out, str(z), str(k[0]), f'{k[1]}{e}')) for e in ('.webp', '.webp.empty'))]
        # Several blocks at a time, so one slow tile doesn't hold up the rest.
        for c in range(0, len(pending), 8):
            chunk = pending[c:c + 8]
            futures = {k: [pool.submit(keep, t) for t in blocks[k]] for k in chunk}
            for (bx, by), fs in futures.items():
                path = os.path.join(out, str(z), str(bx), f'{by}.webp')
                img = np.full((B * 256, B * 256), 255, np.uint8)
                any_ = False
                for f in fs:
                    (_, x, y), v = f.result()
                    n += 1
                    if v is None:
                        continue
                    any_ = True
                    img[(y - by * B) * 256:(y - by * B + 1) * 256, (x - bx * B) * 256:(x - bx * B + 1) * 256] = v
                os.makedirs(os.path.dirname(path), exist_ok=True)
                if any_:
                    Image.fromarray(img, 'L').save(path + '.tmp', 'WEBP', quality=95, method=6)
                    os.replace(path + '.tmp', path)
                    written += 1
                else:
                    open(path + '.empty', 'w').close()
            if (c // 8) % 10 == 0:
                rate = n / max(1e-6, time.time() - start)
                print(f'z{z} blocks {c + len(chunk)}/{len(pending)} tiles {n} ({rate:.1f}/s)', flush=True)
        # This zoom's shaded tiles, from its blocks (so a resumed run counts earlier ones).
        shaded, names = [], []
        for (bx, by) in todo:
            path = os.path.join(out, str(z), str(bx), f'{by}.webp')
            if not os.path.exists(path):
                continue
            names.append(f'{bx}/{by}')
            img = np.asarray(Image.open(path).convert('L'))
            for _, x, y in blocks[(bx, by)]:
                if (img[(y - by * B) * 256:(y - by * B + 1) * 256, (x - bx * B) * 256:(x - bx * B + 1) * 256] != 255).any():
                    shaded.append((x, y))
        covered[str(z)] = sorted(shaded)
        index['zooms'][str(z)] = sorted(names)
        json.dump(covered, open(state_path, 'w'))
        json.dump(index, open(index_path, 'w'), separators=(',', ':'))
        print(f'z{z} done: {len(cands)} candidates, {len(shaded)} shaded tiles, {len(index["zooms"][str(z)])} blocks, {time.time() - start:.0f}s', flush=True)


if __name__ == '__main__':
    main()
