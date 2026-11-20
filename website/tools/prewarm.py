"""Requests every hosted tile once through the tiles domain, so Cloudflare's shared
(Tiered) cache next to the bucket holds them before visitors ask: with Smart
Tiered Caching on, any Cloudflare location then fills from that cache, not R2.

Requests carry the site's Origin (ORIGIN, default the domain without "tiles."):
the bucket's CORS makes Cloudflare cache a separate copy per Origin, and the map
fetches with one.

Usage: python3 -I tools/prewarm.py https://tiles.razorbackmaps.co.nz PREFIX=DIR [PREFIX=DIR ...]
  e.g. ctiles-q95=OUT/ctiles-q95 shade/v1=SHADE_OUT  (local copies give the keys)
"""
import os, ssl, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

host = sys.argv[1].rstrip('/')
origin = os.environ.get('ORIGIN') or host.replace('://tiles.', '://')
CTX = ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt' if os.path.exists('/root/.ccr/ca-bundle.crt') else None)
urls = []
for arg in sys.argv[2:]:
    prefix, root = arg.split('=', 1)
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.webp'):
                urls.append(f'{host}/{prefix}/{os.path.relpath(os.path.join(d, f), root)}')


def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'prewarm/1', 'Origin': origin}), timeout=60, context=CTX) as r:
                r.read()
                return r.headers.get('cf-cache-status') or '?'
        except Exception:
            time.sleep(2 ** attempt)
    return 'failed'


start, counts = time.time(), {}
with ThreadPoolExecutor(16) as ex:
    for n, status in enumerate(ex.map(get, urls), 1):
        counts[status] = counts.get(status, 0) + 1
        if n % 2000 == 0:
            print(f'{n}/{len(urls)} {counts} ({n / (time.time() - start):.0f}/s)', flush=True)
print(f'{len(urls)} requested in {time.time() - start:.0f}s: {counts}', flush=True)
