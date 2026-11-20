# Every Razorback Maps logo from the sharpened kea (full 2048 px square with its sky,
# and the bird alone on transparent).
import sys, os, numpy as np
from PIL import Image, ImageDraw
full = Image.open(sys.argv[1]).convert('RGB'); bird = Image.open(sys.argv[2]).convert('RGBA'); repo = sys.argv[3]
N = full.size[0]; L = Image.Resampling.LANCZOS
def rounded(size, radius=0.2):
    big = Image.new('L', (N, N), 0); ImageDraw.Draw(big).rounded_rectangle((0, 0, N - 1, N - 1), radius=int(N * radius), fill=255)
    im = full.copy().convert('RGBA'); im.putalpha(big); return im.resize((size, size), L)
def square(size): return full.resize((size, size), L)
w = lambda *p: os.path.join(repo, *p)
web = w('website/src/img')
for s in (96, 180, 192, 512): square(s).save(f'{web}/kea-{s}.png', optimize=True)
rounded(32).save(f'{web}/favicon-32.png', optimize=True); rounded(48).save(f'{web}/favicon-48.png', optimize=True); rounded(96).save(f'{web}/logo.png', optimize=True)
# The app: its map button (square) and launcher foreground (the bird alone, sized and
# centred: 42% of the 108 dp canvas, so the wing and tail clear a round mask).
square(192).save(w('native-wgpu/android/app/src/main/assets/ui/ridgeline-kea.png'), optimize=True)
bb = bird.getbbox(); kea = bird.crop(bb)
for name, px in (('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)):
    canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    k = kea.resize((round(px * 0.42), round(px * 0.42 * kea.size[1] / kea.size[0])), L)
    canvas.alpha_composite(k, ((px - k.size[0]) // 2, (px - k.size[1]) // 2))
    canvas.save(w(f'native-wgpu/android/app/src/main/res/mipmap-{name}/ic_launcher_foreground.png'), optimize=True)
# Branding: masters, the store icon, the rounded logo and the bird alone.
b = w('native-wgpu/branding')
full.save(f'{b}/razorback-logo-2048.png', optimize=True); bird.crop(bb).save(f'{b}/razorback-kea-transparent.png', optimize=True)
square(1024).save(f'{b}/ridgeline-icon-1024.png', optimize=True); square(512).save(f'{b}/ridgeline-play-icon-512.png', optimize=True)
rounded(1024).save(f'{b}/ridgeline-logo-rounded-1024.png', optimize=True); bird.crop(bb).resize((821, round(821 * kea.size[1] / kea.size[0])), L).save(f'{b}/ridgeline-kea-transparent.png', optimize=True)
# Preview: circle, rounded, square and small sizes, on dark and light.
p = Image.new('RGB', (900, 470), (32, 35, 38)); d = ImageDraw.Draw(p)
def circle(size):
    m = Image.new('L', (N, N), 0); ImageDraw.Draw(m).ellipse((0, 0, N - 1, N - 1), fill=255); im = full.copy().convert('RGBA'); im.putalpha(m); return im.resize((size, size), L)
for i, im in enumerate((circle(200), rounded(200), square(200))): p.paste(im, (20 + i * 220, 20), im.convert('RGBA'))
for i, s in enumerate((64, 48, 32, 24)): im = rounded(s); p.paste(im, (690 + (i % 2) * 90, 20 + (i // 2) * 90), im)
p.paste((240, 240, 232), (20, 250, 320, 450)); im = rounded(120); p.paste(im, (40, 270), im)
im = rounded(200); p.paste(im, (360, 250), im)
p.save(f'{b}/ridgeline-icon-preview.png', optimize=True)
print('bird bbox', bb, 'of', bird.size)
