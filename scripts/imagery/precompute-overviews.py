"""Resumable Cook-only imagery overviews. No browser-side pyramid construction.

Outputs and original photo snapshots belong to the site's source repository.
The caller checkpoints milestones with the Sites workflow, not manual git.
"""
from pathlib import Path
from PIL import Image, __version__ as pillow_version
from concurrent.futures import ThreadPoolExecutor, as_completed
from io import BytesIO
import argparse, datetime, hashlib, json, math, os, re, subprocess, time, urllib.request
import numpy as np

REPO = Path(__file__).resolve().parents[2]
CONFIG_PATH = Path(__file__).with_name('overview-config.json')
CONFIG = json.loads(CONFIG_PATH.read_text())
QA = REPO / 'qa/alpine-overviews-2026-10-18'
SOURCE = QA / 'source'
PROGRESS = REPO / 'ALPINE_OVERVIEW_PROGRESS.json'
ASSETS = REPO / 'public/assets' / CONFIG['version']
Z = CONFIG['sourceZoom']
X0, Y0, X1, Y1 = CONFIG['sourceTileBounds']

def sha(data):
    return hashlib.sha256(data).hexdigest()

def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.part')
    with temp.open('wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    temp.replace(path)

def save_json(path, value):
    atomic(path, (json.dumps(value, indent=2) + '\n').encode())

def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def log(**values):
    print(json.dumps(values), flush=True)

def dependency_hashes():
    return {'mask': sha((REPO/'public/assets/west-coast-mask.png').read_bytes()),
            'colourCode': sha((REPO/'public/imagery.js').read_bytes())}

def initial_state():
    fingerprint = sha(json.dumps({'config': CONFIG, 'dependencies': dependency_hashes()}, sort_keys=True).encode())
    if PROGRESS.exists():
        state = json.loads(PROGRESS.read_text())
        if state['fingerprint'] != fingerprint:
            raise RuntimeError('Inputs changed: create a new overview version or deliberately rebuild the manifest')
        return state
    return {'version': CONFIG['version'], 'fingerprint': fingerprint, 'config': CONFIG,
            'dependencies': dependency_hashes(), 'createdAt': timestamp(), 'stage': 'planned',
            'sources': {}, 'outputs': {}}

STATE = initial_state()

def checkpoint(stage=None):
    if stage:
        STATE['stage'] = stage
    STATE['updatedAt'] = timestamp()
    save_json(PROGRESS, STATE)

def parent_keys():
    current = {(x, y) for y in range(Y0, Y1+1) for x in range(X0, X1+1)}
    result = {}
    for z in range(Z-1, CONFIG['minimumZoom']-1, -1):
        current = {(x//2, y//2) for x, y in current}
        result[z] = sorted(current)
    return result

MASK_META = json.loads((REPO/'WEST_COAST_REGIONAL_VALIDATION.json').read_text())
FOOTPRINT = json.loads((REPO/'WEST_COAST_SURVEY_FOOTPRINTS.json').read_text())
assert FOOTPRINT['z'] == Z
MASK = Image.open(REPO/'public/assets/west-coast-mask.png').getchannel('A')

def mask_for(x, y):
    dx, dy = (x-FOOTPRINT['x'])*256, (y-FOOTPRINT['y'])*256
    return MASK.crop((dx, dy, dx+256, dy+256))

def jobs():
    result = [('linz', Z, x, y) for y in range(Y0, Y1+1) for x in range(X0, X1+1)]
    result += [('linz', z, x, y) for z, keys in parent_keys().items() for x, y in keys]
    result += [('esri', Z, x, y) for y in range(Y0, Y1+1) for x in range(X0, X1+1)
               if mask_for(x, y).getextrema()[1] > 0]
    return result

def source_key(job):
    return '/'.join(map(str, job))

def source_path(job):
    kind, z, x, y = job
    return SOURCE/kind/str(z)/str(x)/(str(y)+'.webp')

def valid_image(data):
    with Image.open(BytesIO(data)) as im:
        im.load()
        if im.size != (256, 256):
            raise ValueError('Unexpected source image dimensions')
    # Known HTTP-200 Esri no-data response from the inspected high-zoom samples.
    no_data = json.loads((REPO/'REGIONAL_ESRI_ZOOM_VALIDATION.json').read_text())['inspectedPlaceholderSHA256']
    if sha(data) == no_data:
        raise ValueError('Provider no-data placeholder')

def fetch_one(job, key):
    p = source_path(job)
    previous = STATE['sources'].get(source_key(job), {})
    if p.exists():
        try:
            data = p.read_bytes()
            valid_image(data)
            if previous.get('sha256') and previous['sha256'] != sha(data):
                raise ValueError('Source checksum changed')
            return {**previous, 'status': 'completed', 'path': str(p.relative_to(REPO)),
                    'bytes': len(data), 'sha256': sha(data), 'reused': True}
        except Exception:
            pass
    kind, z, x, y = job
    url = (f'https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api={key}'
           if kind == 'linz' else f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}')
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
            valid_image(data)
            atomic(p, data)
            return {'status': 'completed', 'path': str(p.relative_to(REPO)), 'bytes': len(data),
                    'sha256': sha(data), 'completedAt': timestamp(), 'attempts': attempt+1}
        except Exception as error:
            if attempt == 2:
                return {'status': 'failed', 'error': type(error).__name__, 'attempts': 3}
            time.sleep(attempt+1)

def fetch_sources(workers):
    key = os.environ.get('LINZ_API_KEY') or re.search(r"const linzApiKey='([^']+)'", (REPO/'public/app.js').read_text()).group(1)
    tasks = jobs()
    for job in tasks:
        STATE['sources'].setdefault(source_key(job), {'status': 'pending'})
    checkpoint('fetching')
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_one, job, key): job for job in tasks}
        for done, future in enumerate(as_completed(futures), 1):
            STATE['sources'][source_key(futures[future])] = future.result()
            checkpoint()
            if done % 24 == 0 or done == len(tasks):
                log(stage='sources', done=done, total=len(tasks), failed=sum(v['status']=='failed' for v in STATE['sources'].values()))
    failed = [k for k, v in STATE['sources'].items() if v['status'] != 'completed']
    checkpoint('sources_complete' if not failed else 'sources_failed')
    if failed:
        raise RuntimeError(f'{len(failed)} source tasks incomplete; rerun fetch to resume')

def read_source(kind, z, x, y):
    job = (kind, z, x, y)
    row = STATE['sources'].get(source_key(job))
    if not row or row['status'] != 'completed':
        raise RuntimeError('Fetch/verify all sources before building')
    data = source_path(job).read_bytes()
    if sha(data) != row['sha256']:
        raise RuntimeError('Source checksum failed; rerun fetch')
    return Image.open(BytesIO(data)).convert('RGBA')

def esri_compensate(im):
    result = subprocess.run(['node', '-e', "const b=require('node:fs').readFileSync(0);require('./public/imagery.js').compensateEsri(b);process.stdout.write(b)"],
                            cwd=REPO, input=im.tobytes(), capture_output=True, check=True)
    return Image.frombytes('RGBA', im.size, result.stdout)

def area_average(mosaic):
    # Average premultiplied colours and alpha together, avoiding dark fringe
    # pixels. One unbiased nearest-even rounding avoids a progressive brightness
    # lift from repeated horizontal/vertical integer BOX-filter rounding.
    pixels = np.asarray(mosaic).astype('float32')
    alpha = pixels[:, :, 3:]/255
    premultiplied = pixels[:, :, :3]*alpha
    alpha_mean = alpha.reshape(256, 2, 256, 2, 1).mean(axis=(1, 3))
    colour_mean = premultiplied.reshape(256, 2, 256, 2, 3).mean(axis=(1, 3))
    rgb = np.divide(colour_mean, alpha_mean, out=np.zeros_like(colour_mean), where=alpha_mean>0)
    out = np.concatenate([rgb, alpha_mean*255], axis=2)
    return Image.fromarray(np.clip(np.rint(out), 0, 255).astype('uint8'))

def base_overlay(x, y, variant):
    im = read_source('linz', Z, x, y)
    gap_alpha = mask_for(x, y)
    if gap_alpha.getextrema()[1] > 0:
        esri = read_source('esri', Z, x, y)
        if variant == 'natural':
            esri = esri_compensate(esri)
        esri.putalpha(gap_alpha)
        im = Image.alpha_composite(im, esri)
    yy, xx = np.mgrid[:256, :256]
    px, py = (x-X0)*256+xx+.5, (y-Y0)*256+yy+.5
    distance = np.minimum.reduce([px, py, (X1-X0+1)*256-px, (Y1-Y0+1)*256-py])
    t = np.clip(distance/(CONFIG['inwardFeatherTiles']*256), 0, 1)
    alpha = np.round(t*t*(3-2*t)*255).astype('uint8')
    im.putalpha(Image.fromarray(alpha))
    return im

def encode_output(im):
    buffer = BytesIO()
    im.save(buffer, 'WEBP', lossless=True, exact=True, method=4)
    data = buffer.getvalue()
    decoded = Image.open(BytesIO(data)).convert('RGB')
    if decoded.tobytes() != im.convert('RGB').tobytes():
        raise RuntimeError('Lossless output round-trip mismatch')
    digest = sha(data)
    p = ASSETS/(digest[:24]+'.webp')
    if p.exists() and sha(p.read_bytes()) != digest:
        raise RuntimeError('Content-address collision or corrupt output')
    if not p.exists():
        atomic(p, data)
    return {'url': '/assets/'+CONFIG['version']+'/'+p.name, 'bytes': len(data),
            'sha256': digest, 'pixelSHA256': sha(im.convert('RGB').tobytes())}

def build():
    assert CONFIG['resampling'] == 'BOX'
    ASSETS.mkdir(parents=True, exist_ok=True)
    checkpoint('building')
    mappings, quality = {}, {}
    for variant in ['raw', 'natural']:
        current = {(x, y): base_overlay(x, y, variant) for y in range(Y0, Y1+1) for x in range(X0, X1+1)}
        for z, keys in parent_keys().items():
            next_level = {}
            for x, y in keys:
                mosaic = Image.new('RGBA', (512, 512))
                for dy in range(2):
                    for dx in range(2):
                        child = current.get((x*2+dx, y*2+dy))
                        if child is not None:
                            mosaic.paste(child, (dx*256, dy*256))
                overlay = area_average(mosaic)
                if overlay.getchannel('A').getextrema()[1] == 0:
                    continue
                next_level[(x, y)] = overlay
                base = read_source('linz', z, x, y)
                output = Image.alpha_composite(base, overlay).convert('RGB')
                row = encode_output(output)
                task = f'{variant}/{z}/{x}/{y}'
                STATE['outputs'][task] = {**row, 'status': 'completed'}
                tile = mappings.setdefault(f'{z}/{x}/{y}', {})
                tile[variant] = row['url']
                a = np.array(overlay.getchannel('A'))
                core = a == 255
                if core.any():
                    before = np.array(base.convert('RGB')).astype(int)
                    reference = np.array(overlay.convert('RGB')).astype(int)
                    after = np.array(output).astype(int)
                    quality[task] = {'corePixels': int(core.sum()),
                                     'beforeMAE': float(abs(before-reference)[core].mean()),
                                     'afterMAE': float(abs(after-reference)[core].mean())}
                checkpoint()
            current = next_level
            log(stage='build', variant=variant, z=z, tiles=len(next_level))
    public = {'version': CONFIG['version'], 'sourceZoom': Z, 'minimumZoom': CONFIG['minimumZoom'],
              'maximumZoom': CONFIG['maximumZoom'], 'sourceTileBounds': CONFIG['sourceTileBounds'], 'tiles': mappings}
    script = '/* Generated precomputed Cook overview routing. See ALPINE_OVERVIEW_STATUS.md. */\n(function(r){const data='+json.dumps(public, separators=(',', ':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeAlpineOverviews=data;})(globalThis);\n'
    atomic(REPO/'public/alpine-overviews.js', script.encode())
    files = {row['url']: row for row in STATE['outputs'].values()}
    report = {'version': CONFIG['version'], 'sourceZoom': Z, 'levels': list(range(13)),
              'sourceCounts': {kind: sum(k.startswith(kind+'/') for k in STATE['sources']) for kind in ['linz', 'esri']},
              'failedSources': sum(v['status']!='completed' for v in STATE['sources'].values()),
              'tileCoordinates': len(mappings), 'uniqueFiles': len(files),
              'assetBytes': sum(v['bytes'] for v in files.values()), 'losslessRoundTripsVerified': True,
              'colourModes': ['raw', 'natural'], 'filter': 'recursive aligned 2x2 area averaging',
              'inwardFeatherTiles': CONFIG['inwardFeatherTiles'], 'pillow': pillow_version,
              'quality': quality, 'fineImageryUnchanged': 'Runtime replacement is restricted to z0–12; original z13+ requests are untouched.',
              'caveat': 'Source-pixel, area-filter, asset and routing validation. No live browser or FPS measurement; survey edges/coverage differences inside the original z13 imagery remain.'}
    save_json(REPO/'ALPINE_OVERVIEW_VALIDATION.json', report)
    # Remove only unreferenced build outputs within this explicitly versioned directory.
    used = {Path(url).name for url in files}
    for p in ASSETS.glob('*.webp'):
        if p.name not in used:
            p.unlink()
    checkpoint('built')
    log(stage='complete', coordinates=len(mappings), files=len(files), bytes=report['assetBytes'], sources=len(STATE['sources']))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', choices=['fetch', 'build', 'all'], default='all')
    parser.add_argument('--workers', type=int, default=8)
    args = parser.parse_args()
    if args.stage in ['fetch', 'all']:
        fetch_sources(max(1, min(12, args.workers)))
    if args.stage in ['build', 'all']:
        build()
