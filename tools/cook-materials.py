"""Visual-only rock material and terrain lighting. Never writes elevation samples."""
import json,pathlib,numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates,gaussian_filter
import sys
meta=json.loads(pathlib.Path(sys.argv[2]).read_text());h=np.fromfile(sys.argv[1],dtype='<f4').reshape(meta['gridSize'],meta['gridSize']);out=pathlib.Path('public/assets/mt-cook/model');out.mkdir(exist_ok=True)
# Shade the authentic terrain at 2-source-pixel spacing. Eight horizon directions, native metre distances.
a=h[::2,::2];step=meta['pixelSpacingMetres']*2;yy,xx=np.indices(a.shape);dy,dx=np.gradient(a,step);normal=np.stack([-dx,-dy,np.ones_like(a)],axis=-1);normal/=np.linalg.norm(normal,axis=-1,keepdims=True)
occlusion=np.zeros_like(a);sunshadow=np.zeros_like(a);sx,sy,sz=-.42,-.50,.757;sunlen=np.hypot(sx,sy)
for angle in np.arange(8)*np.pi/4:
 horizon=np.zeros_like(a)
 for distance in [2,4,8,16,32,64,128]:
  sample=map_coordinates(a,[yy+np.sin(angle)*distance,xx+np.cos(angle)*distance],order=1,mode='nearest');horizon=np.maximum(horizon,(sample-a)/(distance*step))
 occlusion+=np.arctan(np.maximum(0,horizon))
for distance in [2,4,8,16,32,64,128,256,512]:
 sample=map_coordinates(a,[yy+sy/sunlen*distance,xx+sx/sunlen*distance],order=1,mode='nearest');slope=(sample-a)/(distance*step);sunshadow=np.maximum(sunshadow,np.clip((slope-sz/sunlen+.045)/.09,0,1))
ao=np.clip(1-occlusion/(8*np.pi/2)*.50,.48,1);rgba=np.concatenate([normal*.5+.5,ao[...,None]],axis=-1);Image.fromarray(np.uint8(np.clip(rgba,0,1)*255)).save(out/'terrain-normal-ao.png');Image.fromarray(np.uint8((1-sunshadow)*255)).save(out/'sun-shadow.png')
if len(sys.argv)>3:
 source=Image.open(sys.argv[3]).convert('RGB');source=source.resize((6144,6144),Image.Resampling.LANCZOS) if source.size!=(6144,6144) else source
 source.resize((2048,2048),Image.Resampling.LANCZOS).save(out/'aerial.webp',quality=92)
 photos=out/'photos';photos.mkdir(exist_ok=True)
 for y in range(16):
  for x in range(16):source.crop((x*384-2,y*384-2,(x+1)*384+2,(y+1)*384+2)).save(photos/f'{y*16+x}.webp',quality=92)
image=Image.open(meta['imageryPath'].replace('/assets','public/assets'));image.resize((512,512),Image.Resampling.LANCZOS).save(out/'aerial-preview.webp',quality=90)
coarse=h[::16,::16].astype('<f4');import gzip;gzip.open(out/'camera-dem.f32.gz','wb').write(coarse.tobytes())
print(json.dumps({'lightingSize':a.shape[0],'cameraSize':coarse.shape[0],'material':'source aerial imagery only; no procedural cliff replacement'}))
