"""Takes LINZ's shading out of the overview imagery itself (zooms 0-12), so the
site can draw those tiles directly, with no correction file or processing.

The overview tiles were averaged from LINZ's shaded zoom-13 tiles; each is divided
by its zoom's correction (tools/shade-corrections.py: keep*255 in 4x4-tile
blocks), exactly as src/js/workers/imagery.js does, and saved as lossless WebP like the
originals (QUALITY=<1-100> for lossy: 92 is a third of the size at about 41 dB):

    OUT/ctiles/<pack>/<offset>.webp   the hosted tiles (both colourings), same keys
                                      as /tiles/, uploaded by tools/upload-shade.mjs
    website/imagery-corrected/<name>  the coarse tiles (zooms 0-8) served with the site

Usage: python3 -I tools/correct-overview.py OVERVIEW_JSON SHADE_DIR OUT
OVERVIEW_JSON is dist/data/<version>/overview.json; tiles are read from the
overview host (OVERVIEW_URL, default the test site). Resumable.
"""
import io, json, os, ssl, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
HOST = os.environ.get('OVERVIEW_URL', 'https://bushline-maps.connorgleeson736.workers.dev').rstrip('/')
CTX = ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt' if os.path.exists('/root/.ccr/ca-bundle.crt') else None)
QUALITY = int(os.environ.get('QUALITY', '0'))  # 0: lossless
B = 4


def get(url):
    for attempt in range(8):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'correct-overview/1'}), timeout=60, context=CTX) as r:
                return r.read()
        except Exception as e:
            err = e
            time.sleep(min(30, 2 ** attempt))
    raise RuntimeError(f'{url}: {err}')


class Blocks:
    def __init__(self, root):
        self.root, self.cache = root, {}

    def keep(self, z, x, y):
        """keep*255 for one tile (uint8 256x256), or None where nothing is shaded."""
        key = (z, x // B, y // B)
        if key not in self.cache:
            p = os.path.join(self.root, str(z), str(key[1]), f'{key[2]}.webp')
            self.cache[key] = np.asarray(Image.open(p).convert('L')) if os.path.exists(p) else None
            if len(self.cache) > 64:
                self.cache.pop(next(iter(self.cache)))
        block = self.cache[key]
        if block is None:
            return None
        v = block[(y % B) * 256:(y % B + 1) * 256, (x % B) * 256:(x % B + 1) * 256]
        return None if (v == 255).all() else v


def correct(data, keep):
    """The tile's bytes corrected (WebP), or None where it needs no correction."""
    if keep is None:
        return None
    img = Image.open(io.BytesIO(data))
    img.load()
    mode = 'RGBA' if img.mode in ('RGBA', 'LA', 'P') and 'A' in img.getbands() or img.info.get('transparency') is not None else 'RGB'
    a = np.asarray(img.convert(mode)).astype(np.float32)
    gain = 255.0 / np.maximum(keep.astype(np.float32), 1.0)
    a[..., :3] = np.clip(np.rint(a[..., :3] * gain[..., None]), 0, 255)
    out = io.BytesIO()
    Image.fromarray(a.astype(np.uint8), mode).save(out, 'WEBP', **({'quality': QUALITY, 'method': 6} if QUALITY else {'lossless': True, 'method': 4}))
    return out.getvalue()


def main():
    overview_json, shade_dir, out = sys.argv[1:4]
    o = json.load(open(overview_json))
    blocks = Blocks(shade_dir)
    jobs = {}  # pack/offset -> (z, x, y): a hosted file is one tile
    for z, rows in o['zooms'].items():
        x = 0
        for i in range(0, len(rows), 6):
            x += rows[i]
            y = rows[i + 1]
            for f, off in ((rows[i + 2], rows[i + 3]), (rows[i + 4], rows[i + 5])):
                jobs[f"{o['files'][f]}/{off}"] = (int(z), x, y)
    todo = [(k, t) for k, t in jobs.items() if not os.path.exists(os.path.join(out, 'ctiles', k + '.webp'))]
    print(f'{len(jobs)} hosted tiles, {len(todo)} to do', flush=True)
    stats = {'corrected': 0, 'unchanged': 0, 'in': 0, 'out': 0}

    def one(item):
        k, (z, x, y) = item
        data = get(f'{HOST}/tiles/{k}.webp')
        fixed = correct(data, blocks.keep(z, x, y))
        dest = os.path.join(out, 'ctiles', k + '.webp')
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest + '.tmp', 'wb') as fh:
            fh.write(fixed or data)
        os.replace(dest + '.tmp', dest)
        return len(data), len(fixed or data), fixed is not None

    start = time.time()
    with ThreadPoolExecutor(int(os.environ.get('THREADS', '16'))) as ex:
        for n, (a, b, changed) in enumerate(ex.map(one, todo), 1):
            stats['in'] += a; stats['out'] += b; stats['corrected' if changed else 'unchanged'] += 1
            if n % 1000 == 0:
                print(f'{n}/{len(todo)} ({n / (time.time() - start):.1f}/s)', flush=True)
    # The coarse tiles (zooms 0-8), shipped with the site.
    site = os.path.join(HERE, '..')
    coarse_out = os.path.join(site, 'imagery-corrected')
    os.makedirs(coarse_out, exist_ok=True)
    dist = os.path.join(site, 'dist')
    for k, urls in o['coarse'].items():
        z, x, y = map(int, k.split('/'))
        keep = blocks.keep(z, x, y)
        for url in urls:
            name = url.split('/')[-1]
            data = open(os.path.join(dist, url.lstrip('/')), 'rb').read()
            with open(os.path.join(coarse_out, name), 'wb') as fh:
                fh.write(correct(data, keep) or data)
    print(json.dumps({**stats, 'coarse': len(o['coarse']), 'seconds': round(time.time() - start)}), flush=True)


if __name__ == '__main__':
    main()
