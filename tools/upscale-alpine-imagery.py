"""Full-region Real-ESRGAN detail:4x the previous regular AI texture pixels.

Infer directly from authentic source once (4x width/height), split into1024
bounded detail pages, and externally checkpoint every64 pages. No DEM writes.
"""
from pathlib import Path
import hashlib, json, os, shutil, sys, time
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
import torch
from tools.lidar_pipeline.state import State,atomic_json,atomic_bytes,digest,key
from tools.lidar_pipeline.remote import Remote
sys.path.insert(0,str(Path(__file__).parent/'vendor'))
from srvgg_arch import SRVGGNetCompact

print('Ready for private Alpine upscale JSON on hidden stdin.',flush=True)
s=json.loads(input());root=Path(s['root']);checkout=Path(s['checkout']);weights=Path(s['weights'])
remote=Remote(s['base'],s.pop('token'));state=State(root,json.loads((root/'config.json').read_text()),remote)
source=root/'data/source/source-6144.webp';parent=state.task('authentic-source')
if not parent or parent['state']!='durable' or not state.valid('authentic-source',parent['signature']):raise ValueError('Durable authentic imagery required')
weights_sha=state.config['weights_sha256'];parameters=[]
for name,sha in weights_sha.items():
 path=weights/(name+'.pth')
 if digest(path)!=sha:raise ValueError('Unverified neural weights')
 parameters.append(torch.load(path,map_location='cpu',weights_only=True)['params'])
torch.set_num_threads(min(8,os.cpu_count() or 1));torch.set_num_interop_threads(1)
model=SRVGGNetCompact(3,3,64,32,4,'prelu')
model.load_state_dict({k:parameters[0][k]*.25+parameters[1][k]*.75 for k in parameters[0]},strict=True)
model.eval().to(memory_format=torch.channels_last)
rgb=np.asarray(Image.open(source).convert('RGB'))
if rgb.shape!=(6144,6144,3):raise ValueError('Unexpected georeferenced source dimensions')
PAD,GUTTER,CELL,SCALE=40,32,384,4
extended=np.pad(rgb,((PAD+GUTTER,PAD+GUTTER),(PAD+GUTTER,PAD+GUTTER),(0,0)),mode='edge')
recipe=dict(stage='alpine-source-specific-ai4-v1',source_sha256=digest(source),source_task=parent['signature'],
    weights_sha256=weights_sha,architecture_sha256=digest(Path(__file__).parent/'vendor/srvgg_arch.py'),
    implementation_sha256=digest(__file__),torch=torch.__version__,scale=SCALE,denoise_strength=.25,
    detail_residual=.65,context_source_pixels=PAD,gutter_source_pixels=GUTTER,
    output_page_pixels=1024,encoding='WebP95 method4',appearance_only=True)
recipe_key=key(recipe);directory=root/'data/tiles';directory.mkdir(parents=True,exist_ok=True)

def infer(array):
 tensor=torch.from_numpy(np.ascontiguousarray(array.transpose(2,0,1))).unsqueeze(0).float().div_(255).to(memory_format=torch.channels_last)
 with torch.inference_mode():return model(tensor).squeeze(0).permute(1,2,0).contiguous().numpy()

sample=rgb[3000:3160,3000:3200];whole=infer(sample);part=infer(sample[:,40:])
context_difference=float(np.max(np.abs(whole[160:480,320:480]-part[160:480,160:320])))
if context_difference>=1e-4:raise ValueError('Neural tile context mismatch')
print(json.dumps(dict(context_difference=context_difference,torch=torch.__version__,parents=256,detail_pages=1024)),flush=True)

def process_tile(tile):
 receipt=root/'data/parents'/f'{tile:04d}.json'
 if receipt.exists():
  cached=json.loads(receipt.read_text())
  if cached.get('recipe')==recipe_key and all((root/e['local_path']).exists() and digest(root/e['local_path'])==e['sha256'] for e in cached['tiles']):
   return cached['tiles'],receipt
 tx,ty=tile%16,tile//16;x,y=tx*CELL,ty*CELL
 original=Image.fromarray(extended[y:y+CELL+2*(PAD+GUTTER),x:x+CELL+2*(PAD+GUTTER)])
 result=infer(np.asarray(original))
 base=np.asarray(original.resize((original.width*SCALE,original.height*SCALE),Image.Resampling.LANCZOS),dtype=np.float32)/255
 residual=result-base
 restored=np.clip(base+.65*(residual-gaussian_filter(residual,sigma=(4,4,0),mode='nearest')),0,1)
 image=Image.fromarray(np.uint8(restored*255+.5)).crop((PAD*SCALE,PAD*SCALE,(PAD+CELL+2*GUTTER)*SCALE,(PAD+CELL+2*GUTTER)*SCALE))
 entries=[]
 for q in range(4):
  left=(q%2)*768;top=(q//2)*768;patch=image.crop((left,top,left+1024,top+1024))
  identifier=tile*4+q;path=directory/f'{identifier}.webp'
  import io
  buffer=io.BytesIO();patch.save(buffer,format='WEBP',quality=95,method=4);atomic_bytes(path,buffer.getvalue())
  entries.append(dict(id=identifier,tile=tile,quadrant=q,x=tx*128+(q%2)*64,y=ty*128+(q//2)*64,
      cells=64,gutterGridCells=GUTTER/3,pixels=1024,corePixels=768,
      path=f'/assets/mt-cook/model/upscale/{identifier}.webp',local_path=str(path.relative_to(root)),
      bytes=path.stat().st_size,sha256=digest(path)))
 atomic_json(receipt,dict(recipe=recipe_key,tiles=entries))
 return entries,receipt

started=time.monotonic();all_entries=[]
for first in range(0,256,16):
 name=f'neural-pages-{first:04d}-{first+15:04d}';signature=key(dict(recipe=recipe,first=first,count=16))
 with state.lock(name):
  manifest=root/'data/batches'/(name+'.json')
  if state.valid(name,signature):
   remote.sync_task(state,name);entries=json.loads(manifest.read_text())['tiles']
  else:
   state.start(name,signature);entries=[];paths=[]
   for tile in range(first,first+16):
    rows,receipt=process_tile(tile);entries+=rows;paths.append(receipt)
    print(json.dumps(dict(parent=tile,completed_pages=len(all_entries)+len(entries),total_pages=1024,seconds=round(time.monotonic()-started))),flush=True)
   atomic_json(manifest,dict(recipe=recipe_key,tiles=entries));paths+=[root/e['local_path'] for e in entries]+[manifest]
   state.stage(name,signature,paths,dict(pages=len(entries),parent_tiles=16,first_parent=first))
  if state.task(name)['state']!='durable':raise RuntimeError('Completed neural pages await external checkpoint')
 all_entries+=entries
 progress=dict(completed_pages=len(all_entries),pending_pages=1024-len(all_entries),recipe=recipe_key,
               elapsed_seconds=round(time.monotonic()-started))
 proof=remote.record(state.run,'status',f'pages-{len(all_entries):04d}',progress)
 atomic_json(root/'data/progress.json',dict(progress,external_proof=proof))

report=dict(schema=1,recipe=recipe,run=state.run,network='Real-ESRGAN realesr-general-x4v3 / weak-denoise DNI',
 sourceImageSha256=digest(source),sourcePixels=[6144,6144],previousRegularAIPixels=[12288,12288],
 outputPixels=[24576,24576],pixelCount=24576**2,pixelRatioToPreviousAI=4,linearRatioToPreviousAI=2,
 scale=4,appearanceOnly=True,contextCheckMaxFloatDifference=context_difference,
 method='Direct neural inference from authentic source; source-colour-preserving65% high-frequency residual.4x pixels versus previous regular AI imagery;1024 bounded pages with real neighbouring context.',
 limits='Generated appearance detail; no new survey resolution, geographic features or elevation modifications.',
 tiles=all_entries,totalBytes=sum(e['bytes'] for e in all_entries),seconds=round(time.monotonic()-started))
name='complete-neural-imagery';signature=key(dict(recipe=recipe,entries=all_entries))
path=root/'data/provenance.json'
if not state.valid(name,signature):
 state.start(name,signature);atomic_json(path,report);state.stage(name,signature,[path],dict(pages=1024,pixel_count=24576**2,bytes=report['totalBytes']))
else:remote.sync_task(state,name)
if state.task(name)['state']!='durable':raise RuntimeError('Final neural manifest awaits external checkpoint')
destination=checkout/'public/assets/mt-cook/model/upscale';destination.mkdir(exist_ok=True)
for entry in all_entries:shutil.copy2(root/entry['local_path'],destination/f"{entry['id']}.webp")
shutil.copy2(path,destination/'provenance.json')
print(json.dumps(dict(complete=True,pages=1024,pixels=report['outputPixels'],pixel_ratio=4,bytes=report['totalBytes'],run=state.run)),flush=True)
