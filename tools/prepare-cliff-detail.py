"""Extract a neutral, repeating visual residual from verified AI-enhanced LINZ imagery.

Run from any directory: python tools/prepare-cliff-detail.py
Requires NumPy, Pillow and SciPy. This never changes terrain or its base aerial colour.
"""
import gzip
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import binary_dilation, gaussian_filter

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'public/assets/mt-cook/model'
TILE, X, Y, PATCH, OUTPUT = 46, 452, 644, 96, 128


def periodic_part(image):
    """Remove the smooth field responsible for opposing crop-boundary jumps.

    Periodic-plus-smooth decomposition solves the discrete Poisson boundary term;
    the residual keeps local photographic structure without mirrored repetitions.
    """
    height, width = image.shape
    boundary = np.zeros_like(image)
    boundary[0] = image[-1] - image[0]
    boundary[-1] = image[0] - image[-1]
    boundary[:, 0] += image[:, -1] - image[:, 0]
    boundary[:, -1] += image[:, 0] - image[:, -1]
    denominator = (2 * np.cos(2 * np.pi * np.arange(height)[:, None] / height)
                   + 2 * np.cos(2 * np.pi * np.arange(width)[None, :] / width) - 4)
    denominator[0, 0] = 1
    spectrum = np.fft.fft2(boundary) / denominator
    spectrum[0, 0] = 0
    return image - np.fft.ifft2(spectrum).real


source = MODEL / 'ai' / f'{TILE}.webp'
region = json.loads((MODEL / 'region.json').read_text())
provenance = json.loads((MODEL / 'ai/provenance.json').read_text())
source_bytes = source.read_bytes()
source_sha = hashlib.sha256(source_bytes).hexdigest()
entry = next(item for item in provenance['tiles'] if item['id'] == TILE)
assert source_sha == entry['sha256'], 'AI tile provenance hash differs'
image = Image.open(source).convert('RGB')
rgb = np.asarray(image.crop((X, Y, X + PATCH, Y + PATCH)), dtype=np.float64) / 255
luminance = rgb @ np.array([0.2126, 0.7152, 0.0722])

# Small neutral/blue bright flecks are omitted rather than repeated as synthetic snow.
rock = ((rgb[:, :, 0] - rgb[:, :, 2] > .02)
        & (luminance < .55) & (luminance > .09))
assert rock.mean() > .98, 'Selected patch is no longer predominantly dark rock'
flecks = binary_dilation((~rock) | (luminance > np.median(luminance) + .075), iterations=1)
detail_rock = ~flecks
filled = gaussian_filter(luminance * detail_rock, 3, mode='reflect') / np.maximum(
    gaussian_filter(detail_rock.astype(float), 3, mode='reflect'), 1e-5)
luminance = np.where(detail_rock, luminance, filled)

# Log-luminance removes colour and makes the residual independent of broad lighting.
log_luminance = np.log(np.maximum(luminance, .03))
residual = log_luminance - gaussian_filter(log_luminance, 8, mode='reflect')
residual = periodic_part(residual)
residual = gaussian_filter(residual, .45, mode='wrap')
residual -= gaussian_filter(residual, 8, mode='wrap')
residual -= residual.mean()
normalizer = max(float(residual.std()), .001)
detail = .5 + .16 * residual / normalizer
detail = np.asarray(Image.fromarray(detail.astype(np.float32), mode='F').resize(
    (OUTPUT, OUTPUT), Image.Resampling.BICUBIC), dtype=np.float64)
# Preserve a neutral mean; renderer controls this residual's modest strength.
lo, hi = -1., 1.
for _ in range(40):
    correction = (lo + hi) / 2
    if np.clip(detail + correction, .01, .99).mean() > .5:
        hi = correction
    else:
        lo = correction
detail = np.clip(detail + (lo + hi) / 2, .01, .99)
pixels = np.uint8(np.rint(detail * 255))
asset = MODEL / 'cliff-detail.png'
Image.fromarray(np.repeat(pixels[:, :, None], 3, axis=2), 'RGB').save(asset, optimize=True)

tile_y, tile_x = divmod(TILE, 16)
# AI tiles have a four-pixel gutter; six AI pixels span one native DEM-grid interval.
center_x = (tile_x * 768 + X - 4 + PATCH / 2) / 6
center_y = (tile_y * 768 + Y - 4 + PATCH / 2) / 6
dem = np.frombuffer(gzip.decompress((MODEL / 'cook-dem.f32.gz').read_bytes()), '<f4').reshape(2049, 2049)
dy, dx = np.gradient(dem, region['pixelSpacingMetres'])
slopes = np.degrees(np.arctan(np.hypot(dx, dy)))
slopes = slopes[int(center_y - 8):int(center_y + 9), int(center_x - 8):int(center_x + 9)]
differences = np.concatenate((np.abs(np.diff(detail, axis=0)).ravel(),
                              np.abs(np.diff(detail, axis=1)).ravel()))
wrap = np.concatenate((np.abs(detail[0] - detail[-1]), np.abs(detail[:, 0] - detail[:, -1])))
footprint = PATCH / 6 * region['pixelSpacingMetres']
report = {
    'schema': 1,
    'source': '/assets/mt-cook/model/ai/46.webp',
    'sourceSha256': source_sha,
    'sourceCollection': provenance['sourceCollection'],
    'aiModel': provenance['network'],
    'cropPixels': [X, Y, PATCH, PATCH],
    'sourceGridCenter': [center_x, center_y],
    'sourceMedianSlopeDegrees': round(float(np.median(slopes)), 3),
    'rockColourHeuristicFraction': round(float(rock.mean()), 6),
    'omittedBrightFleckFraction': round(float(flecks.mean()), 6),
    'cropPlanimetricFootprintMetres': round(footprint, 4),
    'recommendedRepeatMetres': 18,
    'outputPixels': [OUTPUT, OUTPUT],
    'encoding': 'RGB8 equal-channel linear residual; 0.5 is neutral; read red channel',
    'processing': 'Omit small snow-colour flecks; log-luminance high-pass sigma8; periodic-plus-smooth boundary correction; wrap filtering; centred residual normalization',
    'appearanceOnly': 'Repeated visual detail, not georeferenced cliff measurements, displacement or a replacement material. Original aerial RGB remains the base.',
    'statistics': {
        'mean': round(float(pixels.mean() / 255), 6),
        'standardDeviation': round(float(pixels.std() / 255), 6),
        'range': [int(pixels.min()), int(pixels.max())],
        'interiorAdjacentDifferenceP95': round(float(np.percentile(differences, 95)), 6),
        'wrapAdjacentDifferenceP95': round(float(np.percentile(wrap, 95)), 6)
    },
    'sha256': hashlib.sha256(asset.read_bytes()).hexdigest(),
    'bytes': asset.stat().st_size,
    'attribution': region['attribution'],
}
assert report['statistics']['wrapAdjacentDifferenceP95'] < report['statistics']['interiorAdjacentDifferenceP95'] * 1.3, 'Repeat seam is stronger than source detail'
(MODEL / 'cliff-detail-provenance.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
