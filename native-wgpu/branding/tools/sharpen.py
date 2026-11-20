# The kea redrawn crisply from a soft 512 px image: the sky as a fitted smooth gradient,
# the bird as flat colours (k-means palette) decided at 4x, small fringes removed.
import sys, numpy as np
from PIL import Image, ImageFilter
src, out = sys.argv[1], sys.argv[2]
im = np.asarray(Image.open(src).convert('RGB'), float)
H, W, _ = im.shape
ys, xs = np.mgrid[0:H, 0:W] / H
# Sky: blue-ish pixels, fitted per channel as a quadratic in x and y.
r, g, b = im[..., 0], im[..., 1], im[..., 2]
sky0 = (b > r + 45) & (b > g + 5) & (b > 170)
def basis(x, y): return np.stack([np.ones_like(x), x, y, x * y, x * x, y * y], -1)
A = basis(xs[sky0], ys[sky0])
coef = np.linalg.lstsq(A, im[sky0], rcond=None)[0]
for _ in range(2):  # refit on pixels close to the model (drop edge blends)
    model = basis(xs, ys) @ coef
    sky = np.linalg.norm(im - model, axis=-1) < 14
    coef = np.linalg.lstsq(basis(xs[sky], ys[sky]), im[sky], rcond=None)[0]
model = basis(xs, ys) @ coef
bird = np.linalg.norm(im - model, axis=-1) > 40
# Bird palette: k-means on clearly-bird pixels (eroded mask: no edge blends).
core = np.asarray(Image.fromarray((bird * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(5))) > 0
px = im[core]
K = int(sys.argv[3]) if len(sys.argv) > 3 else 7
rng = np.random.default_rng(1)
cent = px[rng.choice(len(px), K, replace=False)]
for _ in range(40):
    lab = np.argmin(((px[:, None] - cent[None]) ** 2).sum(-1), 1)
    cent = np.array([px[lab == k].mean(0) if (lab == k).any() else cent[k] for k in range(K)])
# Merge near-duplicates; drop colours that are mostly blends (few pixels, or lying
# between two others), so edges are not outlined in an in-between colour.
lab = np.argmin(((px[:, None] - cent[None]) ** 2).sum(-1), 1)
count = np.bincount(lab, minlength=K).astype(float)
skymean = model[~bird].mean(0)
keep = []
for k in np.argsort(-count):
    if count[k] < 0.0012 * len(px): continue
    if any(np.linalg.norm(cent[k] - cent[j]) < 22 for j in keep): continue
    ends = [cent[j] for j in keep] + [skymean]
    between = count[k] < 0.06 * len(px) and any(np.linalg.norm(ends[i] - cent[k]) + np.linalg.norm(cent[k] - ends[j]) < np.linalg.norm(ends[i] - ends[j]) * 1.08 for i in range(len(ends)) for j in range(i + 1, len(ends)))
    if between: continue
    keep.append(k)
cent = cent[keep]; K = len(cent)
print('palette', np.round(cent).astype(int).tolist(), 'share', np.round(count[keep] / len(px), 3).tolist())
# 4x: upscale smoothly, label every pixel by nearest of (sky model there, palette).
S = 4
big = np.asarray(Image.fromarray(im.astype(np.uint8)).resize((W * S, H * S), Image.Resampling.LANCZOS), float)
yb, xb = np.mgrid[0:H * S, 0:W * S] / (H * S)
skyb = basis(xb, yb) @ coef
d_sky = np.linalg.norm(big - skyb, axis=-1)
d_pal = np.stack([np.linalg.norm(big - c, axis=-1) for c in cent], -1)
lab = np.where(d_sky < d_pal.min(-1) * 1.0, K, d_pal.argmin(-1)).astype(np.uint8)
# Remove thin fringes and specks (the soft edges' in-between colours): a mode filter.
lab = np.asarray(Image.fromarray(lab).filter(ImageFilter.ModeFilter(9)))
lab = np.asarray(Image.fromarray(lab).filter(ImageFilter.ModeFilter(7)))
# Specks: any region smaller than ~60 source pixels takes its surroundings' label.
from scipy import ndimage
lab = lab.copy()
for k in range(K + 1):
    regions, n = ndimage.label(lab == k)
    if n == 0: continue
    sizes = ndimage.sum(np.ones_like(regions), regions, range(1, n + 1))
    for i, size in enumerate(sizes, 1):
        if size < 60 * S * S:
            region = regions == i
            ring = ndimage.binary_dilation(region, iterations=2) & ~region
            if ring.any(): lab[region] = np.bincount(lab[ring]).argmax()
# Smooth outlines: each colour's region blurred a little, every pixel taking the
# strongest (rounds the staircase and waviness of the soft source's edges).
sigma = float(sys.argv[4]) if len(sys.argv) > 4 else 3.0
strength = np.stack([ndimage.gaussian_filter((lab == k).astype(np.float32), sigma) for k in range(K + 1)], -1)
lab = strength.argmax(-1).astype(np.uint8)
pal = np.vstack([cent, [[0, 0, 0]]])
rgb = pal[lab]
mask = lab != K
rgb[~mask] = skyb[~mask]
Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).save(out + '-full.png')
# Transparent bird and the sky alone, for icons built on other backgrounds.
alpha = (mask * 255).astype(np.uint8)
Image.fromarray(np.dstack([np.clip(pal[lab], 0, 255).astype(np.uint8), alpha])).save(out + '-bird.png')
top, bottom = (basis(np.array([.5]), np.array([0.])) @ coef)[0], (basis(np.array([.5]), np.array([1.])) @ coef)[0]
print('sky top', np.round(top).astype(int).tolist(), 'bottom', np.round(bottom).astype(int).tolist())
