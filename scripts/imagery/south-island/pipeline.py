"""South Island imagery pyramid. Immutable, hash-verified R2 checkpoints per batch.
Run with --stage all|fetch|build|restore. Private credentials arrive on hidden stdin.
All completed source and output batches are recoverable from the Site's R2 API.
"""
from pathlib import Path
from PIL import Image
from io import BytesIO
from concurrent.futures import ThreadPoolExecutor
import argparse,collections,hashlib,json,math,os,re,sys,tarfile,threading,time,termios,urllib.request,urllib.error,urllib.parse
import numpy as np
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
CONFIG=json.loads((HERE/'config.json').read_text())
WORK=Path(os.environ.get('RIDGE_SOUTH_WORK','/workspace/scratch/3dd2ae8bd0a2/south-island-overviews-v1'))
STATE_PATH=WORK/'progress.json'
def digest(b):return hashlib.sha256(b).hexdigest()
def encoded(v):return (json.dumps(v,sort_keys=True,separators=(',',':'),allow_nan=False)+'\n').encode()
def atomic(p,b):
 p.parent.mkdir(parents=True,exist_ok=True);t=p.with_name(p.name+'.part')
 with t.open('wb')as f:f.write(b);f.flush();os.fsync(f.fileno())
 t.replace(p)
def log(**v):print(json.dumps(v,separators=(',',':')),flush=True)
def deps():return {p.name:digest(p.read_bytes())for p in [HERE/'config.json',HERE/'footprint.geojson',HERE/'blend-mask.png',ROOT/'public/assets/west-coast-mask.png',ROOT/'public/imagery.js']}
RUN=digest(encoded({'schema':1,'version':CONFIG['version'],'dependencies':deps()}))
MASK=Image.open(HERE/'blend-mask.png');X0,Y0,X1,Y1=CONFIG['maskBounds'];M=CONFIG['maskPixelsPerSourceTile']
FOOT=json.loads((ROOT/'WEST_COAST_SURVEY_FOOTPRINTS.json').read_text());GAP=Image.open(ROOT/'public/assets/west-coast-mask.png').getchannel('A')
SOURCE_KEYS=set(map(tuple,CONFIG['sourceTiles']));PARENTS={};current=SOURCE_KEYS
for z in range(12,-1,-1):current={(x//2,y//2)for x,y in current};PARENTS[z]=sorted(current)
def source_alpha(x,y):return MASK.transform((256,256),Image.Transform.EXTENT,((x-X0)*M,(y-Y0)*M,(x-X0+1)*M,(y-Y0+1)*M),resample=Image.Resampling.BILINEAR)
def coarse_full(z,x,y):
 f=2**(13-z);b=((x*f-X0)*M,(y*f-Y0)*M,((x+1)*f-X0)*M,((y+1)*f-Y0)*M)
 if b[0]<0 or b[1]<0 or b[2]>MASK.width or b[3]>MASK.height:return False
 # Bilinear resampling also reads the neighbouring mask pixel.
 return MASK.crop((b[0]-1,b[1]-1,b[2]+1,b[3]+1)).getextrema()==(255,255)
def gap_alpha(x,y):
 a=(x-FOOT['x'])*256;b=(y-FOOT['y'])*256
 if a>=GAP.width or b>=GAP.height or a+256<=0 or b+256<=0:return None
 p=GAP.crop((a,b,a+256,b+256));return p if p.getextrema()[1] else None
def jobs():
 a=[('linz',13,x,y)for x,y in sorted(SOURCE_KEYS)]
 a += [('linz',z,x,y)for z,keys in PARENTS.items()for x,y in keys if not coarse_full(z,x,y)]
 a += [('esri',13,x,y)for x,y in sorted(SOURCE_KEYS)if gap_alpha(x,y) is not None]
 return a
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*a,**k):return None
class Remote:
 def __init__(self,url,token):
  u=urllib.parse.urlsplit(url)
  if u.scheme!='https' or not u.hostname.endswith('.chatgpt.site') or u.path not in ['','/'] or u.query or u.username:raise ValueError('Use the exact selected Site HTTPS origin')
  self.base=url.rstrip('/')+'/api/lidar/';self.token=token
 def request(self,method,path,data=None,headers=None,allow404=False):
  if '://' in path or path.startswith('/'):raise ValueError('Invalid checkpoint path')
  for attempt in range(5):
   try:
    req=urllib.request.Request(self.base+path,data=data,method=method,headers={**(headers or {}),'OAI-Sites-Authorization':'Bearer '+self.token})
    with urllib.request.build_opener(NoRedirect).open(req,timeout=90)as r:return r.status,dict(r.headers),r.read()
   except urllib.error.HTTPError as e:
    if e.code==404 and allow404:return 404,dict(e.headers),b''
    if e.code in [401,403]:raise RuntimeError('Persistent storage authorization expired; resume with fresh Site credentials') from None
    if e.code not in [429,500,502,503,504] or attempt==4:raise RuntimeError('Persistent storage HTTP '+str(e.code))from None
   except (TimeoutError,OSError):
    if attempt==4:raise RuntimeError('Persistent storage network failure')from None
   time.sleep(min(2**attempt,8))
 def record(self,kind,name,value):
  b=encoded(value);sha=digest(b);p=f'runs/{RUN}/{kind}/{name}/{sha}'
  _,_,v=self.request('PUT',p,b,{'Content-Type':'application/json'});assert json.loads(v)['sha256']==sha
  return {'path':p,'sha256':sha}
 def file(self,b,mime='application/octet-stream'):
  sha=digest(b);chunks=[]
  for off in range(0,len(b),8<<20):
   part=b[off:off+(8<<20)];h=digest(part)
   # PUT is already immutable/idempotent and verifies stored hash + length;
   # an extra HEAD round trip adds no durability guarantee here.
   _,_,reply=self.request('PUT','blobs/'+h,part);result=json.loads(reply);assert result['sha256']==h and result['bytes']==len(part)
   chunks.append({'sha256':h,'bytes':len(part)})
  d={'sha256':sha,'bytes':len(b),'mime':mime,'chunks':chunks};_,_,v=self.request('PUT','manifests/'+sha,encoded(d),{'Content-Type':'application/json'});assert json.loads(v)['verifiedPersistentSha256']==sha
  return {'sha256':sha,'bytes':len(b),'url':'/api/lidar/files/'+sha+'.bin'}
 def pack(self,files,kind,name,rows):
  unique=sorted(set(files));body=bytearray();entries=[];contents={}
  for path in unique:
   b=path.read_bytes();sha=digest(b);offset=contents.get(sha)
   if offset is None:offset=len(body);contents[sha]=offset;body.extend(b)
   entries.append({'path':str(path.relative_to(WORK)),'offset':offset,'bytes':len(b),'sha256':sha})
  ref=self.file(body)
  if kind=='outputs':
   offsets={e['path']:e for e in entries}
   for row in rows.values():
    e=offsets[row['path']];row['asset']={'file':ref['sha256'],'offset':e['offset'],'bytes':e['bytes'],'sha256':e['sha256']}
  record={'schema':1,'kind':kind,'name':name,'run':RUN,'file':ref,'entries':entries,'rows':rows};proof=self.record('tasks',name,record)
  return {**ref,'record':proof,'entries':entries}
 def restore(self,state):
  cursor=None;paths=[]
  while True:
   _,_,body=self.request('GET','runs/'+RUN+('?' +urllib.parse.urlencode({'cursor':cursor})if cursor else ''));listing=json.loads(body)
   paths.extend(item['key']for item in listing['objects']if '/tasks/'in item['key'])
   if not listing['truncated']:break
   cursor=listing['cursor']
  def read_record(path):
   _,_,b=self.request('GET',path);assert digest(b)==path.rsplit('/',1)[1]
   record=json.loads(b);assert record['run']==RUN;return record
  with ThreadPoolExecutor(max_workers=8)as pool:records=list(pool.map(read_record,paths))
  records.sort(key=lambda record:record['name']);log(stage='restore_plan',batches=len(records))
  def read_pack(record):
   ref=record['file'];_,_,body=self.request('GET','files/'+ref['sha256']+'.bin');assert digest(body)==ref['sha256'];return body
  # Three bounded downloads hide request latency. Files/checkpoints are written
  # serially: deduplicated image paths can appear in more than one pack.
  pending=collections.deque()
  def complete():
   record,future=pending.popleft();body=future.result()
   for entry in record['entries']:
    b=body[entry['offset']:entry['offset']+entry['bytes']];assert digest(b)==entry['sha256'];p=WORK/entry['path'];assert p.resolve().is_relative_to(WORK.resolve());atomic(p,b)
   if record['kind']=='sources':state['sources'].update(record['rows'])
   else:state['outputs'].update(record['rows'])
   state['batches'][record['name']]=record['file'];checkpoint(state);log(stage='restored',batch=record['name'],sources=len(state['sources']),coordinates=len(state['outputs'])//2)
  with ThreadPoolExecutor(max_workers=3)as pool:
   for record in records:
    if record['name']in state['batches']and all((WORK/e['path']).exists()and digest((WORK/e['path']).read_bytes())==e['sha256']for e in record['entries']):continue
    pending.append((record,pool.submit(read_pack,record)))
    if len(pending)>=3:complete()
   while pending:complete()
  return state

STATE=json.loads(STATE_PATH.read_text())if STATE_PATH.exists()else {'run':RUN,'config':CONFIG,'dependencies':deps(),'sources':{},'outputs':{},'batches':{},'stage':'planned'}
if STATE['run']!=RUN:raise RuntimeError('Inputs changed; use a new work directory or intentional reviewed migration')
def checkpoint(s=STATE):s['updatedAt']=time.time();atomic(STATE_PATH,encoded(s))
def source_path(job):kind,z,x,y=job;return WORK/'source'/kind/str(z)/str(x)/(str(y)+'.webp')
def key(job):return '/'.join(map(str,job))
rate_lock=threading.Lock();next_request=0
def throttle():
 global next_request
 with rate_lock:now=time.monotonic();delay=max(0,next_request-now);next_request=max(now,next_request)+1/12
 if delay:time.sleep(delay)
def source_fetch(job,api):
 p=source_path(job);k=key(job);old=STATE['sources'].get(k)
 if p.exists():
  b=p.read_bytes()
  if old and digest(b)==old['sha256']:return k,old
  if old is None:
   with Image.open(BytesIO(b))as im:im.load();assert im.size==(256,256)
   return k,{'sha256':digest(b),'bytes':len(b),'path':str(p.relative_to(WORK)),'status':'completed','reusedLocal':True}
 # Preserve the already-verified Cook source snapshots rather than refetching.
 original=ROOT/'qa/alpine-overviews-2026-10-18/source'/Path(*map(str,job[:-1]))/(str(job[-1])+'.webp')
 if original.exists():b=original.read_bytes();reused=True
 else:
  kind,z,x,y=job;url=(f'https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api={api}'if kind=='linz'else f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}')
  for attempt in range(5):
   try:
    if kind=='linz':throttle()
    with urllib.request.urlopen(url,timeout=35)as response:b=response.read()
    break
   except urllib.error.HTTPError as e:
    if e.code in [401,403]:raise RuntimeError('Imagery key rejected; resume with a valid LINZ key')from None
    if attempt==4:raise RuntimeError(f'Source {k} HTTP {e.code}')from None
    time.sleep(min(2**attempt,10))
   except (TimeoutError,OSError):
    if attempt==4:raise RuntimeError('Source network failure '+k)from None
    time.sleep(min(2**attempt,10))
  reused=False
 with Image.open(BytesIO(b))as im:im.load();assert im.size==(256,256),'Unexpected source tile dimensions'
 atomic(p,b);return k,{'sha256':digest(b),'bytes':len(b),'path':str(p.relative_to(WORK)),'status':'completed','reusedCook':reused}
def fetch(remote,workers):
 tasks=jobs();STATE['plannedSources']=len(tasks);checkpoint();log(stage='source_plan',sources=len(tasks),outputs=sum(map(len,PARENTS.values())),run=RUN)
 pending=collections.deque()
 def complete():
  future,name,rows=pending.popleft();ref=future.result();STATE['sources'].update(rows);STATE['batches'][name]={k:v for k,v in ref.items()if k!='entries'};STATE['stage']='fetching';checkpoint();log(stage='sources_durable',done=len(STATE['sources']),total=len(tasks),batch=name,bytes=ref['bytes'])
 with ThreadPoolExecutor(max_workers=workers)as downloads,ThreadPoolExecutor(max_workers=2)as uploads:
  for start in range(0,len(tasks),384):
   batch=tasks[start:start+384];name=f'sources-{start//384:05d}-n384'
   if name in STATE['batches']and all(key(j)in STATE['sources']and source_path(j).exists()for j in batch):continue
   rows=dict(downloads.map(lambda j:source_fetch(j,API),batch));log(stage='source_batch_fetched',start=start,count=len(rows))
   pending.append((uploads.submit(remote.pack,[source_path(j)for j in batch],'sources',name,rows),name,rows))
   if len(pending)>=2:complete()
  while pending:complete()
 STATE['stage']='sources_complete';checkpoint();remote.record('status','sources-complete',{'run':RUN,'stage':STATE['stage'],'sources':len(STATE['sources'])})

def read_source(kind,z,x,y):
 row=STATE['sources'][key((kind,z,x,y))];b=(WORK/row['path']).read_bytes();assert digest(b)==row['sha256'];return Image.open(BytesIO(b)).convert('RGBA')
def save_webp(im,p):
 b=BytesIO();im.save(b,'WEBP',lossless=True,exact=True,method=4);data=b.getvalue();assert Image.open(BytesIO(data)).convert(im.mode).tobytes()==im.tobytes();atomic(p,data);return {'sha256':digest(data),'bytes':len(data),'path':str(p.relative_to(WORK))}
ESRI_NATURAL={}
def prepare_esri():
 import subprocess
 for x,y in SOURCE_KEYS:
  if gap_alpha(x,y)is None:continue
  im=read_source('esri',13,x,y);p=subprocess.run(['node','-e',"const b=require('node:fs').readFileSync(0);require('./public/imagery.js').compensateEsri(b);process.stdout.write(b)"],cwd=ROOT,input=im.tobytes(),capture_output=True,check=True)
  ESRI_NATURAL[(x,y)]=Image.frombytes('RGBA',im.size,p.stdout)
def base(x,y,mode):
 if (x,y)not in SOURCE_KEYS:return None
 im=read_source('linz',13,x,y);a=gap_alpha(x,y)
 if a is not None:
  esri=(ESRI_NATURAL[(x,y)].copy()if mode=='natural'else read_source('esri',13,x,y));esri.putalpha(a);im=Image.alpha_composite(im,esri)
 im.putalpha(source_alpha(x,y));return im
def average(im):
 pixels=np.asarray(im).astype('float32');a=pixels[:,:,3:]/255;pa=pixels[:,:,:3]*a;aa=a.reshape(256,2,256,2,1).mean(axis=(1,3));rgb=pa.reshape(256,2,256,2,3).mean(axis=(1,3));rgb=np.divide(rgb,aa,out=np.zeros_like(rgb),where=aa>0);out=np.concatenate([rgb,aa*255],axis=2);return Image.fromarray(np.clip(np.rint(out),0,255).astype('uint8'))
def overlay_path(mode,z,x,y):return WORK/'overlay'/mode/str(z)/str(x)/(str(y)+'.webp')
def build_one(job):
 z,x,y=job;rows={};files=[]
 for mode in ['raw','natural']:
  # Outside the original reviewed gap footprint, the two colour variants have
  # identical image pixels: grading is applied by the same runtime shader.
  changed=any((cx,cy)in ESRI_NATURAL for cx,cy in [(x*2+dx,y*2+dy)for dy in range(2)for dx in range(2)])if z==12 else any(STATE['outputs'].get(f'natural/{z+1}/{x*2+dx}/{y*2+dy}',{}).get('sha256')!=STATE['outputs'].get(f'raw/{z+1}/{x*2+dx}/{y*2+dy}',{}).get('sha256')for dy in range(2)for dx in range(2))
  if mode=='natural'and not changed:
   rows[f'natural/{z}/{x}/{y}']=dict(rows[f'raw/{z}/{x}/{y}']);continue
  mosaic=Image.new('RGBA',(512,512))
  for dy in range(2):
   for dx in range(2):
    cx,cy=x*2+dx,y*2+dy
    if z==12:child=base(cx,cy,mode)
    else:
     row=STATE['outputs'].get(f'{mode}/{z+1}/{cx}/{cy}');child=Image.open(WORK/row['overlay']['path']).convert('RGBA')if row else None
    if child is not None:mosaic.paste(child,(dx*256,dy*256))
  overlay=average(mosaic);a=overlay.getchannel('A')
  o=save_webp(overlay,overlay_path(mode,z,x,y));files.append(WORK/o['path'])
  opaque=a.getextrema()==(255,255)
  result=overlay.convert('RGB')if opaque else Image.alpha_composite(read_source('linz',z,x,y),overlay).convert('RGB')
  b=BytesIO();result.save(b,'WEBP',lossless=True,exact=True,method=4);raw=b.getvalue();assert Image.open(BytesIO(raw)).convert('RGB').tobytes()==result.tobytes()
  path=WORK/'tiles'/(digest(raw)+'.webp');atomic(path,raw);files.append(path);rows[f'{mode}/{z}/{x}/{y}']={'sha256':digest(raw),'bytes':len(raw),'path':str(path.relative_to(WORK)),'overlay':o,'opaque':opaque,'status':'completed'}
 return rows,files
def build(remote,workers):
 prepare_esri()
 for z,keys in PARENTS.items():
  pending=collections.deque()
  def complete():
   future,name,rows=pending.popleft();ref=future.result();STATE['outputs'].update(rows);STATE['batches'][name]={k:v for k,v in ref.items()if k!='entries'};STATE['stage']='building';checkpoint();log(stage='outputs_durable',z=z,done=len(STATE['outputs'])//2,total=sum(map(len,PARENTS.values())),batch=name)
  with ThreadPoolExecutor(max_workers=min(workers,8))as compute,ThreadPoolExecutor(max_workers=2)as uploads:
   for start in range(0,len(keys),64):
    batch=[(z,x,y)for x,y in keys[start:start+64]];name=f'outputs-z{z:02d}-{start//64:05d}-n64'
    if name in STATE['batches']and all(f'raw/{z}/{x}/{y}'in STATE['outputs']and (WORK/STATE['outputs'][f'raw/{z}/{x}/{y}']['path']).exists()for _,x,y in batch):continue
    built=list(compute.map(build_one,batch));rows={k:v for sub,_ in built for k,v in sub.items()};files=[p for _,sub in built for p in sub]
    pending.append((uploads.submit(remote.pack,files,'outputs',name,rows),name,rows))
    if len(pending)>=2:complete()
   while pending:complete()
  remote.record('status',f'z{z:02d}-complete',{'run':RUN,'stage':'level_complete','z':z,'outputCoordinates':len(STATE['outputs'])//2})
 finish(remote)

def finish(remote):
 tiles={}
 for k,row in STATE['outputs'].items():
  mode,z,x,y=k.split('/');tiles.setdefault('/'.join([z,x,y]),{})[mode]=row['asset']
 assert len(tiles)==sum(map(len,PARENTS.values()))and all(set(v)=={'raw','natural'}for v in tiles.values())
 public={'version':CONFIG['version'],'run':RUN,'sourceZoom':13,'maximumZoom':12,'grade':'snow-v3','tiles':tiles}
 b=encoded(public);atomic(WORK/'tile-index.json',b);ref=remote.file(b,'application/json');remote.record('configs','final-index',{'index':ref,'run':RUN,'config':CONFIG,'dependencies':deps()})
 script='/* Verified persistent South Island overview ranges; see SOUTH_ISLAND_IMAGERY_STATUS.md. */\n(function(r){const data='+json.dumps(public,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeSouthOverviews=data;})(globalThis);\n';atomic(ROOT/'public/south-overviews.js',script.encode())
 STATE['stage']='complete';STATE['index']=ref;checkpoint();remote.record('status','complete',{'run':RUN,'sources':len(STATE['sources']),'coordinates':len(tiles),'index':ref,'stage':'complete'})
 unique={r['sha256']:r for r in STATE['outputs'].values()};report={'run':RUN,'sourceTiles':len(STATE['sources']),'overviewCoordinates':len(tiles),'uniqueTileFiles':len(unique),'tileBytes':sum(v['bytes']for v in unique.values()),'persistentIndex':ref,'sourceZoom':13,'levels':list(PARENTS),'dependencies':deps(),'losslessRoundTripsVerified':True,'externalCheckpointBatches':len(STATE['batches']),'allOutputsPersistent':True,'scope':CONFIG['scope']};atomic(ROOT/'SOUTH_ISLAND_IMAGERY_VALIDATION.json',encoded(report));log(stage='complete',**report)
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--stage',choices=['all','fetch','build','restore'],default='all');parser.add_argument('--workers',type=int,default=96);args=parser.parse_args()
 attrs=termios.tcgetattr(0)if sys.stdin.isatty()else None
 if attrs:
  hidden=attrs[:];hidden[3]&=~termios.ECHO;termios.tcsetattr(0,termios.TCSANOW,hidden)
 print('Ready for private Site credentials on stdin (hidden).',flush=True)
 auth=json.loads(sys.stdin.readline())
 if attrs:termios.tcsetattr(0,termios.TCSANOW,attrs)
 remote=Remote(auth['url'],auth['token']);remote.request('GET','health');API=auth.get('linzKey')or re.search(r"const linzApiKey='([^']+)'",(ROOT/'public/app.js').read_text()).group(1)
 remote.record('configs','configuration',{'run':RUN,'config':CONFIG,'dependencies':deps()})
 # Persist the runnable implementation before any long processing work starts.
 archive=BytesIO()
 with tarfile.open(fileobj=archive,mode='w:gz')as t:
  for p in sorted(HERE.glob('*')):
   if p.is_file():t.add(p,arcname='scripts/imagery/south-island/'+p.name)
  for p in [ROOT/'public/imagery.js',ROOT/'public/assets/west-coast-mask.png',ROOT/'WEST_COAST_SURVEY_FOOTPRINTS.json']:t.add(p,arcname=str(p.relative_to(ROOT)))
 code=remote.file(archive.getvalue(),'application/x-tar');remote.record('configs','runnable-code',{'run':RUN,'archive':code})
 checkpoint();log(stage='persistent_start',run=RUN,code=code)
 try:
  if args.stage=='restore':remote.restore(STATE)
  if args.stage in ['all','fetch']:fetch(remote,max(1,min(96,args.workers)))
  if args.stage in ['all','build']:build(remote,max(1,min(96,args.workers)))
 except Exception as e:
  STATE['stage']='failed';STATE['error']=str(e);checkpoint()
  try:remote.record('status','failed-'+str(time.time_ns()),{'run':RUN,'error':str(e),'sources':len(STATE['sources']),'outputs':len(STATE['outputs'])})
  except Exception:pass
  log(stage='failed',error=str(e));raise SystemExit(1)
