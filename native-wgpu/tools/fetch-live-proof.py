#!/usr/bin/env python3
"""Fetch genuine Aoraki LINZ proof inputs. Never logs URLs/client keys.
Requires Pillow. Output is external to the checkout; no synthetic fallback.
"""
import argparse, hashlib, io, json, math, pathlib, struct, urllib.request
from PIL import Image

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('output', type=pathlib.Path)
p.add_argument('--child-images', action='store_true', help='Fetch four full-resolution child photos for independent-LOD validation')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
root = pathlib.Path(__file__).resolve().parents[2]
key = json.loads((root/'native-wgpu/android/app/src/main/assets/map-defaults.json').read_text())['linzApiKey']
z = 11
x = int((170.1418 + 180) / 360 * 2**z)
y = int((1-math.asinh(math.tan(math.radians(-43.595)))/math.pi)/2*2**z)
records = []
def fetch(kind, xx, yy, zoom=z):
    ext = 'png' if kind == 'elevation' else 'webp'
    url = f'https://basemaps.linz.govt.nz/v1/tiles/{kind}/WebMercatorQuad/{zoom}/{xx}/{yy}.{ext}?'
    url += ('pipeline=terrain-rgb&' if kind == 'elevation' else '') + 'api=' + key
    # Do not propagate exception URLs containing the public client key.
    try:
        with urllib.request.urlopen(url, timeout=45) as r:
            assert r.status == 200
            data = r.read()
    except Exception as e:
        raise SystemExit(f'{kind} {zoom}/{xx}/{yy}: {type(e).__name__}, status {getattr(e,"code",None)}') from None
    im = Image.open(io.BytesIO(data)).convert('RGBA')
    assert im.size == (256, 256)
    name = f'{kind}-{zoom}-{xx}-{yy}.{ext}'
    (a.output/name).write_bytes(data)
    records.append(dict(file=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    return im
pixels = {}
for yy in range(y-1,y+2):
    for xx in range(x-1,x+3):
        im = fetch('elevation',xx,yy)
        rows = list(im.get_flattened_data()) if hasattr(im, "get_flattened_data") else list(im.getdata())
        assert all(v[3] == 255 for v in rows), 'nodata needs production parent fallback; do not fabricate heights'
        pixels[xx,yy] = [-10000 + (r*65536+g*256+b)*.1 for r,g,b,_ in rows]
def height(gx,gy):
    return pixels[gx//256,gy//256][(gy%256)*256+gx%256]
def mesh(xx):
    out=[]
    for j in range(129):
        for i in range(129):
            gx=xx*256+i*2-1;gy=y*256+j*2-1
            out.append(sum(height(gx+dx,gy+dy) for dx,dy in ((0,0),(1,0),(0,1),(1,1)))/4)
    return out
meshes = [mesh(x),mesh(x+1)]
assert all(meshes[0][j*129+128] == meshes[1][j*129] for j in range(129))
for i,m in enumerate(meshes):
    (a.output/f'tile{i}.heights').write_bytes(struct.pack('<'+'f'*len(m),*m))
    image = fetch('aerial',x+i,y)
    (a.output/f'tile{i}.rgba').write_bytes(image.tobytes())
if a.child_images:
    for i in range(4):
        image=fetch('aerial',x*2+i%2,y*2+i//2,z+1)
        (a.output/f'child{i}.rgba').write_bytes(image.tobytes())
report=dict(independentChildPhotos=a.child_images,provider='basemaps.linz.govt.nz',z=z,x=x,y=y,sharedEdgeSamples=129,
            sharedEdgeMaxErrorMetres=0,minimumMetres=min(map(min,meshes)),maximumMetres=max(map(max,meshes)),
            imagerySize=[256,256],records=records,
            limitation='Host fixture preparation; Android loader execution, cross-LOD stitching and visual parity remain unverified.')
(a.output/'provenance.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='records'}))
