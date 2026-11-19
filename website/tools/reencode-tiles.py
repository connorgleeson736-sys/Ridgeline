"""Re-saves a folder of (lossless) WebP tiles at a lossy quality, keeping the
folder layout: e.g. the corrected overview at quality 95 (about a third of the
size, ~43 dB), uploaded under its own prefix since tiles are cached as immutable.

Usage: python3 -I tools/reencode-tiles.py SRC DEST [QUALITY=95]
"""
import os, sys
from concurrent.futures import ProcessPoolExecutor
from PIL import Image

SRC, DEST = sys.argv[1], sys.argv[2]
Q = int(sys.argv[3]) if len(sys.argv) > 3 else 95


def one(rel):
    out = os.path.join(DEST, rel)
    if os.path.exists(out):
        return os.path.getsize(os.path.join(SRC, rel)), os.path.getsize(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img = Image.open(os.path.join(SRC, rel))
    img.load()
    img.save(out + '.tmp', 'WEBP', quality=Q, method=6)
    os.replace(out + '.tmp', out)
    return os.path.getsize(os.path.join(SRC, rel)), os.path.getsize(out)


if __name__ == '__main__':
    files = [os.path.relpath(os.path.join(d, f), SRC) for d, _, fs in os.walk(SRC) for f in fs if f.endswith('.webp')]
    with ProcessPoolExecutor() as ex:
        sizes = list(ex.map(one, files, chunksize=64))
    a, b = sum(s[0] for s in sizes), sum(s[1] for s in sizes)
    print(f'{len(files)} tiles, {a / 2**20:.0f} MB -> {b / 2**20:.0f} MB')
