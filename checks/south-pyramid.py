"""Independent CPU checks using authentic saved LINZ photos and the full coast mask."""
from pathlib import Path
from PIL import Image
import importlib.util,tempfile,json,numpy as np
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('south',root/'scripts/imagery/south-island/pipeline.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
# Opaque omission must include the neighbouring sample used by bilinear alpha.
checked=0
for x,y in m.PARENTS[12]:
 if m.coarse_full(12,x,y):
  assert all(m.source_alpha(x*2+dx,y*2+dy).getextrema()==(255,255)for dy in range(2)for dx in range(2));checked+=1
coastal=next((x,y)for x,y in m.SOURCE_KEYS if m.source_alpha(x,y).getextrema()[0]>0 and m.source_alpha(x,y).getextrema()[0]<250)
x,y=coastal;a=(x-m.X0)*m.M;b=(y-m.Y0)*m.M
full=m.MASK.transform((512,256),Image.Transform.EXTENT,(a,b,a+2*m.M,b+m.M),resample=Image.Resampling.BILINEAR)
pair=Image.new('L',(512,256));pair.paste(m.source_alpha(x,y),(0,0));pair.paste(m.source_alpha(x+1,y),(256,0));assert pair.tobytes()==full.tobytes(),'Source mask interpolation must have no tile seam'
# Choose an authentic Cook source block with no Esri material substitution.
original=root/'qa/alpine-overviews-2026-10-18/source/linz/13'
chosen=None
for x,y in m.PARENTS[12]:
 children=[(x*2+dx,y*2+dy)for dy in range(2)for dx in range(2)]
 if m.coarse_full(12,x,y)and all((original/str(cx)/(str(cy)+'.webp')).exists()and m.gap_alpha(cx,cy)is None for cx,cy in children):chosen=(x,y,children);break
assert chosen
with tempfile.TemporaryDirectory()as tmp:
 m.WORK=Path(tmp);m.STATE={'sources':{},'outputs':{}};x,y,children=chosen;mosaic=Image.new('RGB',(512,512))
 for i,(cx,cy)in enumerate(children):
  p=original/str(cx)/(str(cy)+'.webp');im=Image.open(p).convert('RGB');mosaic.paste(im,((i%2)*256,(i//2)*256));q=m.source_path(('linz',13,cx,cy));m.atomic(q,p.read_bytes());m.STATE['sources'][f'linz/13/{cx}/{cy}']={'path':str(q.relative_to(m.WORK)),'sha256':m.digest(p.read_bytes())}
 rows,files=m.build_one((12,x,y));row=rows[f'raw/12/{x}/{y}'];result=np.asarray(Image.open(m.WORK/row['path']).convert('RGB')).astype(int)
 raw=np.asarray(mosaic).astype('uint32');expected=np.rint((raw[::2,::2]+raw[1::2,::2]+raw[::2,1::2]+raw[1::2,1::2])/4).astype(int);assert np.array_equal(result,expected);assert row['opaque'];assert rows[f'natural/12/{x}/{y}']['sha256']==row['sha256']
 report={'opaqueCoastGuardTilesChecked':checked,'coastMaskTileSeam':'byte-identical to one continuous resampling','authenticLINZFourTileMean':'pixel-exact independent integer sum / nearest-even rounding','ungradedLINZRawNaturalPixels':'identical','losslessOutput':'pixel-exact','sourceZoom':13,'outputZoom':12,'caveat':'CPU source/filter/encoding checks, not live browser or GPU pixels'}
 (root/'SOUTH_ISLAND_FILTER_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
