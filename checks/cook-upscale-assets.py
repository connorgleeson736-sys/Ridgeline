"""Validate full-region4x-pixel AI coverage, real tile overlaps and unchanged DEM."""
from pathlib import Path
from functools import lru_cache
import gzip,hashlib,json
import numpy as np
from PIL import Image

root=Path(__file__).resolve().parents[1]/'public/assets/mt-cook/model';folder=root/'upscale'
report=json.loads((folder/'provenance.json').read_text());old=json.loads((root/'ai/provenance.json').read_text());meta=json.loads((root/'region.json').read_text())
assert report['sourceImageSha256']==old['sourceImageSha256']
assert report['recipe']['weights_sha256']==old['weightsSha256']
assert report['outputPixels']==[24576,24576] and report['previousRegularAIPixels']==[12288,12288]
assert report['pixelRatioToPreviousAI']==4 and report['linearRatioToPreviousAI']==2
assert report['contextCheckMaxFloatDifference']<1e-4
assert hashlib.sha256(gzip.decompress((root/'cook-dem.f32.gz').read_bytes())).hexdigest()==meta['sourceSha256']
entries=report['tiles'];assert len(entries)==1024 and len({e['id'] for e in entries})==1024
grid={};total=0
for e in entries:
 path=folder/f"{e['id']}.webp"
 assert hashlib.sha256(path.read_bytes()).hexdigest()==e['sha256']
 assert path.stat().st_size==e['bytes'] and Image.open(path).size==(1024,1024)
 assert e['cells']==64 and e['corePixels']==768 and e['pixels']==1024 and e['gutterGridCells']==32/3
 assert e['id']==e['tile']*4+e['quadrant'] and e['path']==f"/assets/mt-cook/model/upscale/{e['id']}.webp"
 assert e['x']%64==0 and e['y']%64==0
 cell=(e['x']//64,e['y']//64);assert cell not in grid;grid[cell]=e
 total+=e['bytes']
assert set(grid)=={(x,y) for y in range(32) for x in range(32)}
assert total==report['totalBytes']

@lru_cache(maxsize=64)
def image(identifier):
 with Image.open(folder/f'{identifier}.webp') as photo:return np.asarray(photo).astype(np.int16)

errors=[]
for y in range(32):
 for x in range(32):
  a=image(grid[x,y]['id'])
  if x<31:errors.append(float(np.abs(a[:,-256:]-image(grid[x+1,y]['id'])[:,:256]).mean()))
  if y<31:errors.append(float(np.abs(a[-256:]-image(grid[x,y+1]['id'])[:256]).mean()))
assert max(errors)<4,'High-resolution geographic gutter disagreement'
assert len(errors)==1984
print(json.dumps(dict(verified_pages=1024,geographic_coverage='Complete existing1.77km Alpine Model',
 output_pixels=report['outputPixels'],pixel_ratio_to_previous_ai=4,overlap_pairs=len(errors),
 mean_overlap_rgb_difference=float(np.mean(errors)),maximum_overlap_pair_mean=max(errors),
 total_bytes=total,dem='Unchanged source SHA verified',ai='Visual enhancement only')))
