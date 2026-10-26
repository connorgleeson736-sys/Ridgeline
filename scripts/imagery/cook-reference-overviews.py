"""Build a local fine-source colour pyramid, preserving existing imagery outside
its measured Cook footprint. Resumable source snapshots and outputs are committed
by the Sites workflow. Site credentials arrive only on hidden stdin.
"""
from pathlib import Path
from PIL import Image
from io import BytesIO
import numpy as np,json,hashlib,urllib.request,re,sys,termios,concurrent.futures,os,threading
ROOT=Path(__file__).resolve().parents[2];QA=ROOT/'qa/zoom-colour-2026-10-19';CACHE=QA/'source';ASSETS=ROOT/'public/assets/cook-reference-v1';ASSETS.mkdir(parents=True,exist_ok=True)
INDEX=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/south-overviews.js').read_text())[1]);FOOT=json.loads((ROOT/'WEST_COAST_SURVEY_FOOTPRINTS.json').read_text());GAP=Image.open(ROOT/'public/assets/west-coast-mask.png').getchannel('A')
API=re.search(r"const linzApiKey='([^']+)'",(ROOT/'public/app.js').read_text()).group(1)
def sha(b):return hashlib.sha256(b).hexdigest()
def atomic(p,b):
 p.parent.mkdir(parents=True,exist_ok=True);q=p.with_suffix(p.suffix+'.part');
 with q.open('wb')as f:f.write(b);f.flush();os.fsync(f.fileno())
 q.replace(p)
def jsonsave(p,v):atomic(p,(json.dumps(v,indent=2)+'\n').encode())
settings=termios.tcgetattr(0);hidden=settings[:];hidden[3]&=~termios.ECHO;termios.tcsetattr(0,termios.TCSANOW,hidden);print('Ready for private Site credentials (hidden).',flush=True)
try:CREDENTIALS=json.loads(sys.stdin.readline())
finally:termios.tcsetattr(0,termios.TCSANOW,settings)
rows={};SOURCE_LOCKS={}
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
def get(url,private=False,headers=None):
 request=urllib.request.Request(url,headers={**(headers or {}),**({'OAI-Sites-Authorization':'Bearer '+CREDENTIALS['token']}if private else {})})
 # Site origin is supplied by get_site; never forward its bearer to imagery hosts.
 for attempt in range(3):
  try:
   with urllib.request.build_opener(NoRedirect).open(request,timeout=40)as r:return r.read(),r.status,dict(r.headers)
  except Exception:
   if attempt==2:raise RuntimeError('Image acquisition failed; resume from saved source snapshots')from None

def source(z,x,y,kind='linz'):
 p=CACHE/(f'{z}-{x}-{y}.webp'if kind=='linz'else f'esri-{z}-{x}-{y}.webp')
 with SOURCE_LOCKS.setdefault(str(p),threading.Lock()):
  if not p.exists():
   url=f'https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api={API}'if kind=='linz'else f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
   b,_,_=get(url);atomic(p,b)
  b=p.read_bytes();im=Image.open(BytesIO(b)).convert('RGBA');assert im.size==(256,256);rows[p.name]={'sha256':sha(b),'bytes':len(b)};return im

def gap(z,x,y,size=256):
 f=2**(FOOT['z']-z)*256
 return GAP.transform((size,size),Image.Transform.EXTENT,(x*f-FOOT['x']*256,y*f-FOOT['y']*256,(x+1)*f-FOOT['x']*256,(y+1)*f-FOOT['y']*256),resample=Image.Resampling.BILINEAR)

def existing(z,x,y,mode):
 k=f'{z}/{x}/{y}';entry=INDEX['tiles'].get(k)
 if z<=12 and entry:
  row=entry if isinstance(entry,list)or 'url'in entry else entry[mode]
  if isinstance(row,dict)and 'url'in row:return Image.open(ROOT/'public'/row['url'].lstrip('/')).convert('RGBA')
  r=row.get('range')if isinstance(row,dict)else row;file=INDEX['packs'][r[0]];p=CACHE/f'base-{mode}-{z}-{x}-{y}.webp'
  if not p.exists():
   b,status,h=get(CREDENTIALS['url'].rstrip('/')+f'/api/lidar/files/{file}.bin?tile=colour-reference-{k}',True,{'Range':f'bytes={r[1]}-{r[1]+r[2]-1}'})
   assert status==206 and len(b)==r[2];atomic(p,b)
  return Image.open(p).convert('RGBA')
 im=source(z,x,y);a=gap(z,x,y)
 if a.getextrema()[1]:
  esri=source(z,x,y,'esri')
  if mode=='natural':
   import subprocess
   corrected=subprocess.run(['node','-e',"const b=require('node:fs').readFileSync(0);require('./public/imagery.js').compensateEsri(b);process.stdout.write(b)"],cwd=ROOT,input=esri.tobytes(),capture_output=True,check=True).stdout;esri=Image.frombytes('RGBA',im.size,corrected)
  esri.putalpha(a);im=Image.alpha_composite(im,esri)
 return im

def average(im):
 a=np.asarray(im).astype('float32');alpha=a[:,:,3:]/255;pa=a[:,:,:3]*alpha;aa=alpha.reshape(256,2,256,2,1).mean((1,3));rgb=pa.reshape(256,2,256,2,3).mean((1,3));rgb=np.divide(rgb,aa,out=np.zeros_like(rgb),where=aa>0);return Image.fromarray(np.rint(np.clip(np.concatenate([rgb,aa*255],2),0,255)).astype('uint8'))

# Exact z15 photos, same radiometry as the sampled z19 imagery. No learned pixels.
keys=[(x,y)for y in range(20798,20804)for x in range(31868,31874)]
with concurrent.futures.ThreadPoolExecutor(max_workers=4)as pool:list(pool.map(lambda q:source(15,*q),keys))
overlay={}
for x,y in keys:
 im=source(15,x,y);a=np.asarray(gap(15,x,y)).astype(float)/255;xx=np.arange(256)+.5+(x-31868)*256;yy=np.arange(256)+.5+(y-20798)*256
 # 32 source pixels (~28 metres): a smooth boundary into unchanged surroundings.
 wx=np.clip(np.minimum(xx,1536-xx)/32,0,1);wy=np.clip(np.minimum(yy,1536-yy)/32,0,1);wx=wx*wx*(3-2*wx);wy=wy*wy*(3-2*wy)
 im.putalpha(Image.fromarray(np.rint(255*(1-a)*wy[:,None]*wx[None,:]).astype('uint8')));overlay[(x,y)]=im
assets={};validation=[]
for z in range(14,-1,-1):
 parents=sorted({(x//2,y//2)for x,y in overlay});nextlevel={}
 # Fetch independent existing bases together; all work remains bounded to four downloads.
 with concurrent.futures.ThreadPoolExecutor(max_workers=4)as pool:list(pool.map(lambda q:existing(z,*q),[(x,y,mode)for x,y in parents for mode in ['raw','natural']]))
 for x,y in parents:
  mosaic=Image.new('RGBA',(512,512))
  for dy in range(2):
   for dx in range(2):
    child=overlay.get((2*x+dx,2*y+dy))
    if child is not None:mosaic.paste(child,(256*dx,256*dy))
  im=average(mosaic)
  if im.getchannel('A').getextrema()[1]==0:continue
  nextlevel[(x,y)]=im;variants={}
  for mode in ['raw','natural']:
   base=existing(z,x,y,mode);result=Image.alpha_composite(base,im).convert('RGB');b=BytesIO();result.save(b,'WEBP',lossless=True,exact=True,method=4);data=b.getvalue();assert Image.open(BytesIO(data)).convert('RGB').tobytes()==result.tobytes();name=sha(data)[:24]+'.webp';atomic(ASSETS/name,data);variants[mode]='/assets/cook-reference-v1/'+name
   if mode=='raw':validation.append({'z':z,'x':x,'y':y,'sha256':sha(data),'bytes':len(data),'fullyReplacedPixels':int(np.sum(np.asarray(im.getchannel('A'))==255))})
  assets[f'{z}/{x}/{y}']=variants['raw']if variants['raw']==variants['natural']else variants
 overlay=nextlevel;jsonsave(QA/'build-progress.json',{'completedThroughZoom':z,'assets':assets,'sources':rows});print(json.dumps({'z':z,'coordinates':len(assets)}),flush=True)
manifest={'version':'cook-reference-v1','sourceZoom':15,'maximumZoom':14,'sourceTileBounds':[31868,20798,31874,20804],'tiles':assets}
atomic(ROOT/'public/cook-reference-overviews.js',('/* Authentic fine-source Cook imagery, lossless area-averaged overviews. */\n(function(r){const data='+json.dumps(manifest,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeReferenceOverviews=data;})(globalThis);\n').encode());jsonsave(QA/'build-validation.json',{'assets':validation,'sources':rows,'fineSourceTiles':len(keys),'losslessRoundtrip':True,'sourceZoom':15,'originalSouthIslandRun':INDEX['run']});print('COMPLETE',len(assets),flush=True)
