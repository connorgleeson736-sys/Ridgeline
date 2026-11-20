"""Compare provider PNG/WebP alpha for mask-only survey inputs; never log API keys.

Exploratory audit, not a production URL change. Requires Pillow and numpy.
Pass a map-defaults.json with linzApiKey; raw fixtures remain outside Git.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

import numpy as np
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--key-file', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
key = json.loads(Path(args.key_file).read_text())['linzApiKey']
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
cases = [
    (17, 126961, 83596, 'otago-2017-2019-0.3m'),
    (17, 126961, 83598, 'otago-2017-2019-0.3m'),
    (17, 126962, 83598, 'otago-2017-2019-0.3m'),
    (17, 126961, 83599, 'otago-2017-2019-0.3m'),
    (17, 126963, 83598, 'otago-2017-2019-0.3m'),
    (17, 127471, 83202, 'canterbury-2015-2016-0.3m'),
    (16, 63480, 41798, 'otago-2017-2019-0.3m'),
    (13, 7935, 5224, 'otago-2017-2019-0.3m'),
]


def fetch(case, fmt):
    z, x, y, survey = case
    path = out / f'{z}-{x}-{y}-{survey}.{fmt}'
    started = time.monotonic()
    try:
        url = (f'https://basemaps.linz.govt.nz/v1/tiles/{survey}/'
               f'WebMercatorQuad/{z}/{x}/{y}.{fmt}?api={key}')
        with urllib.request.urlopen(url, timeout=25) as response:
            data = response.read()
            status = response.status
        path.write_bytes(data)
        rgba = np.array(Image.open(io.BytesIO(data)).convert('RGBA'))
        return dict(format=fmt, status=status, bytes=len(data),
                    alphaSha256=hashlib.sha256(rgba[:, :, 3].tobytes()).hexdigest(),
                    alphaValues=np.unique(rgba[:, :, 3]).tolist(),
                    elapsedMs=round((time.monotonic()-started)*1000, 1)), rgba[:, :, 3]
    except urllib.error.HTTPError as error:
        return dict(format=fmt, status=error.code), None
    except Exception as error:
        return dict(format=fmt, error=type(error).__name__), None


def compare(case):
    # Vary format order to avoid always assigning a cold first request to PNG.
    order = ['png', 'webp'] if case[2] % 2 == 0 else ['webp', 'png']
    values = {fmt: fetch(case, fmt) for fmt in order}
    row = dict(tile=list(case[:3]), survey=case[3],
               responses=[values[fmt][0] for fmt in ['png', 'webp']])
    a, b = (values[fmt][1] for fmt in ['png', 'webp'])
    if a is not None and b is not None:
        row['differentAlphaPixels'] = int(np.count_nonzero(a != b))
        # Server-side opportunity only: preserve the exact alpha and discard
        # colours before transmission. No such provider endpoint is assumed.
        mask = Image.new('RGBA', (a.shape[1], a.shape[0]), 'white')
        mask.putalpha(Image.fromarray(a))
        encoded = io.BytesIO()
        mask.save(encoded, format='PNG', optimize=True)
        row['hypotheticalAlphaOnlyPngBytes'] = len(encoded.getvalue())
    return row


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    rows = list(pool.map(compare, cases))
good = [r for r in rows if r.get('differentAlphaPixels') == 0]
png_bytes = sum(r['responses'][0]['bytes'] for r in good)
webp_bytes = sum(r['responses'][1]['bytes'] for r in good)
result = dict(purpose='Mask-only inputs: RGB is discarded; exact alpha required',
              cases=rows, identicalAlphaPairs=len(good), totalPairs=len(rows),
              pngBytes=png_bytes, webpBytes=webp_bytes,
              hypotheticalAlphaOnlyPngBytes=sum(r['hypotheticalAlphaOnlyPngBytes'] for r in good),
              bandwidthOnlySavingAt5MbpsMs=round((png_bytes-webp_bytes)*8/5000, 1),
              timingCaveat='Cloud requests, uncontrolled CDN state; not phone latency')
(out / 'survey-mask-results.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
