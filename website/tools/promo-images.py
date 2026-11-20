#!/usr/bin/env python3
"""The website's pictures from tools/promo-shots.mjs's renders.

    python3 -I tools/promo-images.py <render-dir> <site-img-dir> [<masters-dir>]

Each render is twice its size on screen (a little sharper than the app's own
screen, no more). Web copies are that size or smaller (Lanczos): landscape scenes at 2400 and 1600 px wide, phone scenes at
860 px; WebP and JPEG. The share picture (og.jpg) is 1200x630 from the hero.
Masters (optional): the full renders as high-quality JPEG, for other uses.
"""
import os, sys
from PIL import Image

def save(im, path_noext, quality=84):
    im.save(path_noext + '.webp', 'WEBP', quality=quality, method=6)
    im.convert('RGB').save(path_noext + '.jpg', 'JPEG', quality=quality + 4, optimize=True, progressive=True)

def main():
    src, dst = sys.argv[1], sys.argv[2]
    masters = sys.argv[3] if len(sys.argv) > 3 else None
    os.makedirs(dst, exist_ok=True)
    for name in sorted(os.listdir(src)):
        if not name.endswith('.png'): continue
        im = Image.open(os.path.join(src, name)).convert('RGB'); base = name[:-4]
        w, h = im.size
        if masters:
            os.makedirs(masters, exist_ok=True); im.save(os.path.join(masters, base + '.jpg'), 'JPEG', quality=92, optimize=True)
        if w > h:  # landscape
            short = 'hero' if base.startswith('hero') else base
            for width in (2400, 1600):
                save(im.resize((width, round(h * width / w)), Image.Resampling.LANCZOS), os.path.join(dst, f'{short}-{width}'))
            if base.startswith('hero'):
                # Share picture: 1200x630 from the middle of the hero.
                ch = round(w * 630 / 1200); top = max(0, (h - ch) // 2)
                im.crop((0, top, w, top + ch)).resize((1200, 630), Image.Resampling.LANCZOS).save(os.path.join(dst, 'og.jpg'), 'JPEG', quality=86, optimize=True)
        else:  # phone
            short = {'walk-gillespie': 'walk', 'route-aspiring': 'route'}.get(base, base)
            save(im.resize((860, round(h * 860 / w)), Image.Resampling.LANCZOS), os.path.join(dst, short))
        print(base, im.size)

if __name__ == '__main__':
    main()
