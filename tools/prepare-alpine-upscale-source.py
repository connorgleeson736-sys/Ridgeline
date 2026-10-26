"""Recover the Alpine Model's authentic6144px photo, then checkpoint it.

Reuse audited native windows and range-read only uncovered margins. Projection
and sampling match the original Alpine Model image preparation.
"""
from pathlib import Path
import hashlib, json, math
import numpy as np
from PIL import Image
from pyproj import Transformer
from tools.lidar_pipeline.state import State,atomic_json,atomic_bytes,digest,key
from tools.lidar_pipeline.remote import Remote
from tools.lidar_pipeline.source_photos import ANCHOR,PIXEL,bounds_for,compose

print('Ready for private Alpine source JSON on hidden stdin.',flush=True)
s=json.loads(input());checkout=Path(s['checkout']);model=checkout/'public/assets/mt-cook/model'
meta=json.loads((model/'region.json').read_text());old=json.loads((model/'ai/provenance.json').read_text())
config=dict(stage='alpine-full-region-ai4-v1',model_metadata_sha256=digest(model/'region.json'),
    original_source_sha256=old['sourceImageSha256'],weights_sha256=old['weightsSha256'],
    source_pixels=[6144,6144],output_pixels=[24576,24576],total_pixel_ratio_to_previous_ai=4,
    scope='Existing1.7km Alpine Model imagery only; elevation untouched')
root=Path(s['root']);remote=Remote(s['base'],s.pop('token'));state=State(root,config,remote)
remote.record(state.run,'configs','config',config)
name='authentic-source';signature=key(dict(config=config,implementation=digest(__file__)))
directory=root/'data/source';output=directory/'source-6144.webp';provenance=directory/'provenance.json'
with state.lock(name):
 if state.valid(name,signature):
  remote.sync_task(state,name);print(json.dumps(dict(reused=True,run=state.run,source=str(output))),flush=True);raise SystemExit
 state.start(name,signature)
 native_root=Path(s['native_root']);native_state=State(native_root,json.loads((native_root/'config.json').read_text()))
 cat=json.loads((native_root/'data/source-photos/catalog.json').read_text())
 cx,cy=meta['worldCenter'];span=(meta['gridSize']-1)*meta['worldStep'];transform=Transformer.from_crs(4326,2193,always_xy=True)
 wx=np.array([cx-span/2,cx+span/2]);wy=np.array([cy-span/2,cy+span/2])
 lng,lat=np.meshgrid(wx*360-180,np.degrees(np.arctan(np.sinh(np.pi*(1-2*wy)))))
 xx,yy=transform.transform(lng,lat)
 left=ANCHOR[0]+math.floor((xx.min()-ANCHOR[0])/PIXEL)*PIXEL
 top=ANCHOR[1]-math.floor((ANCHOR[1]-yy.max())/PIXEL)*PIXEL
 w=math.ceil((xx.max()-left)/PIXEL)+2;h=math.ceil((top-yy.min())/PIXEL)+2
 c0=int(round((left-ANCHOR[0])/PIXEL));r0=int(round((ANCHOR[1]-top)/PIXEL))
 canvas=np.zeros((h,w,4),np.uint8);coverage=np.zeros((h,w),bool);inputs=[]
 for i in range(16):
  task=native_state.task(f'source-photo-{i:04d}')
  if not task or task['state']!='durable' or not native_state.valid(task['name'],task['signature']):raise ValueError('Native source checkpoint is not verified')
  path=native_root/'data/source-photos'/f'{i:04d}';report=json.loads((path/'validation.json').read_text())
  sc,sr,ec,er=report['pixel_window'];lc,tr=max(c0,sc),max(r0,sr);rc,br=min(c0+w,ec),min(r0+h,er)
  if rc<=lc or br<=tr:continue
  with Image.open(path/'photo.png') as image:piece=np.asarray(image.crop((lc-sc,tr-sr,rc-sc,br-sr)))
  target=canvas[tr-r0:br-r0,lc-c0:rc-c0];mask=coverage[tr-r0:br-r0,lc-c0:rc-c0]
  if mask.any() and not np.array_equal(target[mask],piece[mask]):raise ValueError('Native overlap changed')
  target[:]=piece;mask[:]=True;inputs.append(dict(task=task['name'],signature=task['signature'],sha256=report['png_sha256']))
 # The older Alpine Model extends~84m west of the newer2km summit ROI.
 # Fetch that real source strip rather than extending edge colours.
 margins=[]
 for row in range(0,h,1536):
  stop=min(h,row+1536);missing=np.argwhere(~coverage[row:stop])
  if not len(missing):continue
  a,b=missing.min(0);d,e=missing.max(0)+1
  window=[c0+int(b),r0+row+int(a),c0+int(e),r0+row+int(d)]
  png,report=compose(bounds_for(window),cat['sources'])
  import io
  with Image.open(io.BytesIO(png)) as image:piece=np.asarray(image)
  if report['pixel_window']!=window:raise ValueError('Margin source grid changed')
  target=canvas[row+a:row+d,b:e];mask=coverage[row+a:row+d,b:e]
  if mask.any() and not np.array_equal(target[mask],piece[mask]):raise ValueError('Native margin overlap changed')
  target[:]=piece;mask[:]=True;margins.append(report)
 if not coverage.all() or (canvas[:,:,3]!=255).any():raise ValueError('Authentic source has missing coverage')
 out=np.empty((6144,6144,3),np.uint8);wx=cx+(((np.arange(6144)+.5)/6144)-.5)*span;lng=wx*360-180
 for y0 in range(0,6144,128):
  rr=np.arange(y0,min(6144,y0+128));wy=cy+(((rr+.5)/6144)-.5)*span
  lat=np.degrees(np.arctan(np.sinh(np.pi*(1-2*wy))));lg,lt=np.meshgrid(lng,lat);xx,yy=transform.transform(lg,lt)
  col=(xx-left)/PIXEL-.5;row=(top-yy)/PIXEL-.5;ic=np.floor(col).astype(int);ir=np.floor(row).astype(int);dx=col-ic;dy=row-ir
  if ic.min()<0 or ir.min()<0 or ic.max()+1>=w or ir.max()+1>=h:raise ValueError('Projected sample outside source')
  a=canvas[ir,ic,:3].astype(float);b=canvas[ir,ic+1,:3].astype(float);c=canvas[ir+1,ic,:3].astype(float);e=canvas[ir+1,ic+1,:3].astype(float)
  vals=a*((1-dx)*(1-dy))[:,:,None]+b*(dx*(1-dy))[:,:,None]+c*((1-dx)*dy)[:,:,None]+e*(dx*dy)[:,:,None]
  out[y0:y0+len(rr)]=np.clip(np.rint(vals),0,255).astype(np.uint8)
 import io
 buffer=io.BytesIO();Image.fromarray(out).save(buffer,format='WEBP',lossless=True,method=6);atomic_bytes(output,buffer.getvalue())
 report=dict(source_sha256=digest(output),matches_original_encoded_source=digest(output)==old['sourceImageSha256'],
   source_pixel_sha256=hashlib.sha256(out.tobytes()).hexdigest(),source_pixels=[6144,6144],native_sources=inputs,
   extra_native_margin_reads=margins,projection='Original model WebMercator footprint -> NZTM native0.3m pixels; bilinear source sampling',
   collection=old['sourceCollection'],elevation_modified=False)
 atomic_json(provenance,report);state.stage(name,signature,[output,provenance],report)
 if state.task(name)['state']!='durable':raise RuntimeError('Source awaits external checkpoint')
 print(json.dumps(dict(run=state.run,source=str(output),sha256=report['source_sha256'],matches_original=report['matches_original_encoded_source'])),flush=True)
