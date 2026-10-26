from pathlib import Path
import re,urllib.request,concurrent.futures,json,math,hashlib,time,os
from PIL import Image
import numpy as np
repo=Path(__file__).resolve().parents[2];root=Path(os.environ.get('RIDGELINE_QA_ROOT',repo/'source-cache/regional-imagery'));root.mkdir(parents=True,exist_ok=True)
key=os.environ.get('LINZ_API_KEY') or re.search(r"const linzApiKey='([^']+)'",(repo/'public/app.js').read_text()).group(1)
config=json.load(open(repo/'qa/regional-2026-10-18/survey-config.json'));names=[r['name'] for r in config['layers'] if 'canterbury' in r['name'] or 'west-coast' in r['name']]
def tile(lon,lat,z):return (lon+180)/360*2**z,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z
bounds=[169.96,-43.665,170.245,-43.44]
def limits(z):
 a,b=tile(bounds[0],bounds[3],z);c,d=tile(bounds[2],bounds[1],z);return math.floor(a),math.floor(b),math.floor(c),math.floor(d)
def pull(job):
 name,z,x,y=job;p=root/f'{name}-{z}-{x}-{y}.png'
 for attempt in range(2):
  try:
   if not p.exists():
    with urllib.request.urlopen(f'https://basemaps.linz.govt.nz/v1/tiles/{name}/WebMercatorQuad/{z}/{x}/{y}.png?api={key}',timeout=20) as r:data=r.read()
    temp=p.with_suffix('.part');temp.write_bytes(data);temp.replace(p)
   a=np.array(Image.open(p).convert('RGBA'));return dict(name=name,z=z,x=x,y=y,covered=int((a[:,:,3]>0).sum()),bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest())
  except Exception as e:
   if attempt:return dict(name=name,z=z,x=x,y=y,error=type(e).__name__)
   if p.exists():p.unlink()

def atomic_json(path,data):
 temp=path.with_suffix('.part');temp.write_text(json.dumps(data,indent=2));temp.replace(path)

def run(jobs,label):
 rows=[]
 with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
  for row in pool.map(pull,jobs):
   rows.append(row)
   if len(rows)%32==0:
    atomic_json(root/(label+'-progress.json'),dict(done=len(rows),total=len(jobs),rows=rows))
    print(json.dumps(dict(stage=label,done=len(rows),total=len(jobs))),flush=True)
 return rows
# Prune datasets using alpha coverage over the actual review area, not their titles.
z=11;xa,ya,xb,yb=limits(z);jobs=[(name,z,x,y) for name in names for y in range(ya,yb+1) for x in range(xa,xb+1)];rows=run(jobs,'survey-screen')
if any('error' in row for row in rows):
 atomic_json(root/'screen-failures.json',rows);raise RuntimeError('Survey screening incomplete; retry before deriving replacement area')
active=[]
for name in names:
 anycov=False
 for row in rows:
  if row['name']!=name or 'error'in row:continue
  a=np.array(Image.open(root/f'{name}-{z}-{row["x"]}-{row["y"]}.png').convert('RGBA'))[:,:,3]
  lx,ty=tile(bounds[0],bounds[3],z);rx,by=tile(bounds[2],bounds[1],z)
  x0=max(0,int((lx-row['x'])*256));x1=min(256,math.ceil((rx-row['x'])*256));y0=max(0,int((ty-row['y'])*256));y1=min(256,math.ceil((by-row['y'])*256))
  if x1>x0 and y1>y0 and np.any(a[y0:y1,x0:x1]):anycov=True
 if anycov:active.append(name)
print(json.dumps(dict(stage='active-surveys',names=active)),flush=True)
(root/'coverage-progress.json').write_text(json.dumps(dict(bounds=bounds,screen=rows,active=active),indent=2))
z=13;xa,ya,xb,yb=limits(z);jobs=[(name,z,x,y) for name in active for y in range(ya,yb+1) for x in range(xa,xb+1)];detail=run(jobs,'fine-footprints')
if any('error' in row for row in detail):
 atomic_json(root/'footprint-failures.json',detail);raise RuntimeError('Survey footprints incomplete; retry before deriving replacement area')
mask=Image.new('L',((xb-xa+1)*256,(yb-ya+1)*256))
for row in detail:
 if 'error'in row:continue
 a=np.array(Image.open(root/f'{row["name"]}-{z}-{row["x"]}-{row["y"]}.png').convert('RGBA'))[:,:,3]
 ox=(row['x']-xa)*256;oy=(row['y']-ya)*256;region=np.array(mask.crop((ox,oy,ox+256,oy+256)));region=np.maximum(region,a);mask.paste(Image.fromarray(region),(ox,oy))
mask.save(root/'fine-survey-coverage.png')
result=dict(bounds=bounds,z=z,x=xa,y=ya,width=mask.width,height=mask.height,active=active,screen=rows,tiles=detail,failed=[r for r in rows+detail if 'error'in r]);(root/'coverage.json').write_text(json.dumps(result,indent=2));print(json.dumps(dict(stage='complete',active=active,detailTiles=len(detail),failed=len(result['failed']),size=mask.size)),flush=True)
