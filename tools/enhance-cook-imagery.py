"""Offline Real-ESRGAN inference on authentic aerial imagery. Never reads/writes DEMs.
Usage: python tools/enhance-cook-imagery.py source-6144.webp weights-directory [--sample]
Dependencies: torch CPU, NumPy, Pillow, SciPy. Official weights are hash-pinned below.
"""
import sys,pathlib,json,hashlib,time,os
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
import torch
sys.path.insert(0,str(pathlib.Path(__file__).parent/'vendor'))
from srvgg_arch import SRVGGNetCompact

WEIGHTS={'realesr-general-x4v3':'8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292','realesr-general-wdn-x4v3':'1641f8c4464b9f097c9fdda5589273713f67cf59f3d909e0bd688f0cee269dca'}
source=pathlib.Path(sys.argv[1]);weights=pathlib.Path(sys.argv[2]);out=pathlib.Path('public/assets/mt-cook/model/ai');out.mkdir(parents=True,exist_ok=True)
torch.set_num_threads(min(8,os.cpu_count() or 1));torch.set_num_interop_threads(1)
params=[]
for name,sha in WEIGHTS.items():
 file=weights/(name+'.pth');assert hashlib.sha256(file.read_bytes()).hexdigest()==sha,'Unverified model weights'
 params.append(torch.load(file,map_location='cpu',weights_only=True)['params'])
model=SRVGGNetCompact(3,3,64,32,4,'prelu');model.load_state_dict({k:params[0][k]*.25+params[1][k]*.75 for k in params[0]},strict=True);model.eval().to(memory_format=torch.channels_last)
image=Image.open(source).convert('RGB');assert image.size==(6144,6144),'Source georeferencing expects 6144² imagery';rgb=np.asarray(image);PAD=40;GUTTER=2;CELL=384
def infer(array):
 tensor=torch.from_numpy(np.ascontiguousarray(array.transpose(2,0,1))).unsqueeze(0).float().div_(255).to(memory_format=torch.channels_last)
 with torch.inference_mode():output=model(tensor)
 return output.squeeze(0).permute(1,2,0).contiguous().numpy()

# Independent context test: tiled output must agree with full inference away from image borders.
small=rgb[3350:3510,3800:4000];full=infer(small);max_diff=0.
for x in [0,100]:
 lo=max(0,x-PAD);hi=min(small.shape[1],x+100+PAD);part=infer(small[:,lo:hi]);a=part[40*4:120*4,(x-lo+40 if x==0 else x-lo)*4:(x-lo+100 if x==0 else x-lo+60)*4];b=full[40*4:120*4,(40 if x==0 else x)*4:(100 if x==0 else x+60)*4];max_diff=max(max_diff,float(np.max(np.abs(a-b))))
assert max_diff<1e-4,('Inference context mismatch',max_diff)
print(json.dumps({'contextCheckMaxFloatDifference':max_diff,'model':'Real-ESRGAN general-x4v3','denoiseStrength':.25}),flush=True)

# Infer expanded source crops, preserve low-frequency source colour, serve 2× output.
def tile(tx,ty):
 x,y=tx*CELL,ty*CELL;left=max(0,x-GUTTER-PAD);top=max(0,y-GUTTER-PAD);right=min(6144,x+CELL+GUTTER+PAD);bottom=min(6144,y+CELL+GUTTER+PAD)
 original=Image.fromarray(rgb[top:bottom,left:right]);network=infer(np.asarray(original));ai=Image.fromarray(np.uint8(np.clip(network,0,1)*255+.5)).resize((original.width*2,original.height*2),Image.Resampling.LANCZOS)
 base=original.resize(ai.size,Image.Resampling.LANCZOS);a=np.asarray(ai,dtype=np.float32)/255;b=np.asarray(base,dtype=np.float32)/255
 # Neural detail is visual-only. Preserve broad colours/snow boundaries with a source-referenced residual.
 residual=a-b;low=gaussian_filter(residual,sigma=(2,2,0),mode='nearest');restored=np.clip(b+.65*(residual-low),0,1)
 patch=Image.fromarray(np.uint8(restored*255+.5));box=((x-GUTTER-left)*2,(y-GUTTER-top)*2,(x+CELL+GUTTER-left)*2,(y+CELL+GUTTER-top)*2);patch=patch.crop(box)
 # Outside-region gutter pixels use edge colours rather than introducing dark borders.
 p=np.asarray(patch).copy()
 if tx==0:p[:,:4]=p[:,4:5]
 if ty==0:p[:4]=p[4:5]
 if tx==15:p[:,-4:]=p[:,-5:-4]
 if ty==15:p[-4:]=p[-5:-4]
 return Image.fromarray(p)

if '--sample' in sys.argv:
 sample=tile(10,9);sample.save(out/'sample.webp',lossless=True);image.crop((10*384-2,9*384-2,11*384+2,10*384+2)).save(out/'sample-source.webp',lossless=True);print('Sample enhanced',flush=True);sys.exit(0)

started=time.monotonic();entries=[];atlas=np.zeros((12288,12288,3),np.uint8)
for ty in range(16):
 for tx in range(16):
  i=ty*16+tx;file=out/(str(i)+'.webp');patch=tile(tx,ty);patch.save(file,quality=94,method=5);core=np.asarray(patch)[4:-4,4:-4];atlas[ty*768:(ty+1)*768,tx*768:(tx+1)*768]=core
  entries.append({'id':i,'path':'/assets/mt-cook/model/ai/'+file.name,'size':[776,776],'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
 print(json.dumps({'completed':(ty+1)*16,'total':256,'elapsedSeconds':round(time.monotonic()-started)}),flush=True)
full_image=Image.fromarray(atlas);full_image.resize((2048,2048),Image.Resampling.LANCZOS).save(out/'aerial.webp',quality=94);full_image.resize((512,512),Image.Resampling.LANCZOS).save(out/'preview.webp',quality=92)
report={'schema':1,'sourceImageSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'sourceCollection':'Canterbury 0.3m Rural Aerial Photos (2015–2016)','sourcePixels':[6144,6144],'sourceNativeMetres':.3,'network':'Real-ESRGAN realesr-general-x4v3 / weak-denoise DNI','codeCommit':'a4abfb2979a7bbff3f69f58f58ae324608821e27','weightsRelease':'v0.2.5.0','weightsSha256':WEIGHTS,'architecture':{'numFeat':64,'numConv':32,'nativeScale':4,'activation':'PReLU'},'denoiseStrength':.25,'inference':'CPU FP32, eval + inference_mode, 40-pixel context','servingScale':2,'servingProcessing':'4x neural inference → 2x Lanczos; source colour retained using 65% high-frequency residual; WebP94','outputPixels':[12288,12288],'contextCheckMaxFloatDifference':max_diff,'status':'AI appearance only; no new measured resolution or DEM modifications','tiles':entries,'totalTileBytes':sum(x['bytes'] for x in entries),'elapsedSeconds':round(time.monotonic()-started)}
(out/'provenance.json').write_text(json.dumps(report,indent=2));print(json.dumps({'done':True,'tileMB':report['totalTileBytes']/1e6,'seconds':report['elapsedSeconds']}),flush=True)
