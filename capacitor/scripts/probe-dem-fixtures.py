"""Fetch a few real DEMs and sample resident parent quarters for geometry research.

No production changes. Edge samples use duplicated borders (no neighbour DEM).
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import urllib.request
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('--key-file', required=True)
p.add_argument('--output', required=True)
args = p.parse_args()
key = json.loads(Path(args.key_file).read_text())['linzApiKey']
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
rows = []
for name, lng, lat in [('Cook',170.141,-43.595),('Rolling Pin',168.7089609,-44.36369167),('Wellington',174.777,-41.289)]:
    for source_z in [12,14,16]:
        z = source_z+1
        x = int((lng+180)/360*2**z)
        y = int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z)
        url = (f'https://basemaps.linz.govt.nz/v1/tiles/elevation/'
               f'WebMercatorQuad/{source_z}/{x//2}/{y//2}.png?pipeline=terrain-rgb&api={key}')
        try:
            with urllib.request.urlopen(url,timeout=25) as response:
                data = response.read()
            rgb = np.array(Image.open(io.BytesIO(data)).convert('RGB'),dtype=np.float64)
            heights = rgb[:,:,0]*6553.6+rgb[:,:,1]*25.6+rgb[:,:,2]*.1-10000
            # Same half-pixel positions as the shipped adaptive profileJob.
            xx = np.clip(((x%2)+np.arange(129)/128)/2*256-.5,0,255)
            yy = np.clip(((y%2)+np.arange(129)/128)/2*256-.5,0,255)
            ix,iy = xx.astype(int),yy.astype(int)
            dx,dy = xx-ix,yy-iy
            a = heights[iy[:,None],ix[None,:]]
            b = heights[iy[:,None],np.minimum(255,ix+1)[None,:]]
            c = heights[np.minimum(255,iy+1)[:,None],ix[None,:]]
            d = heights[np.minimum(255,iy+1)[:,None],np.minimum(255,ix+1)[None,:]]
            sampled = ((a*(1-dx)+b*dx)*(1-dy[:,None])+(c*(1-dx)+d*dx)*dy[:,None])
            stem = name.lower().replace(' ','-')+'-'+str(z)
            sampled.astype('<f8').tofile(out/(stem+'.f64'))
            rows.append(dict(name=name,tile=[z,x,y],sourceTile=[source_z,x//2,y//2],
                             file=stem+'.f64',sourceSha256=hashlib.sha256(data).hexdigest(),
                             sampledSha256=hashlib.sha256(sampled.astype('<f8').tobytes()).hexdigest(),
                             sourceBytes=len(data),min=float(sampled.min()),max=float(sampled.max())))
            print(name,z,'ok',flush=True)
        except Exception as error:
            rows.append(dict(name=name,tile=[z,x,y],error=type(error).__name__))
            print(name,z,type(error).__name__,flush=True)
(out/'dem-fixtures.json').write_text(json.dumps(rows,indent=2)+'\n')
