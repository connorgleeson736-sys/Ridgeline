"""Refresh sparse Esri replacement masks without replacing valid LINZ aerials.

The audit and western rectangles are search areas only. Actual survey alpha,
interior land, and current lossless 10m comparisons determine replacements.
Downloads/checkpoints stay outside the checkout; no credentials are written.
"""
from pathlib import Path
import argparse, concurrent.futures, gzip, hashlib, io, json, math, re, urllib.request, subprocess
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import binary_dilation, label
from shapely import make_valid
from shapely.geometry import shape, box, mapping
from shapely.ops import unary_union, transform
from shapely.strtree import STRtree
from pyproj import Transformer

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--audit',type=Path,required=True);parser.add_argument('--work',type=Path,required=True);args=parser.parse_args()
AUDIT=args.audit;WORK=args.work;WORK.mkdir(parents=True,exist_ok=True);CACHE=WORK/'source';CACHE.mkdir(exist_ok=True)
KEY=re.search("const linzApiKey='([^']+)'",(ROOT/'public/app.js').read_text())[1]
OLD=json.loads((AUDIT/'live-aerial-config.json').read_text());CURRENT=json.loads((WORK/'live-config.json').read_text())
old_ids={r['3857']:r for r in OLD['layers'] if r.get('3857')};current_ids={r['3857']:r for r in CURRENT['layers'] if r.get('3857')}
assert set(old_ids)==set(current_ids),'Imagery IDs changed: refresh source footprints before modifying masks'
MERC=Transformer.from_crs(4326,3857,always_xy=True).transform;LL=Transformer.from_crs(3857,4326,always_xy=True).transform
Z=13;source_hashes={};records=[]
def atomic(path,data):
 path.parent.mkdir(parents=True,exist_ok=True);temp=path.with_suffix('.part');temp.write_bytes(data);temp.replace(path)
def save(path,data):atomic(path,(json.dumps(data,separators=(',',':'))+'\n').encode())
def load_json(path):return json.loads(path.read_text())
def xy(lon,lat,z):return (lon+180)/360*2**z,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z
def lonlat(x,y,z):return x/2**z*360-180,math.degrees(math.atan(math.sinh(math.pi*(1-2*y/2**z))))
def tilebox(x,y,z):
 w,n=lonlat(x,y,z);e,s=lonlat(x+1,y+1,z);return box(w,s,e,n)
def polygons(g):
 if g.geom_type=='Polygon':yield g
 elif hasattr(g,'geoms'):
  for q in g.geoms:yield from polygons(q)
def raster(g,x,y,z):
 out=Image.new('L',(256,256));draw=ImageDraw.Draw(out)
 for p in polygons(g):
  def ring(r):return [((xy(a,b,z)[0]-x)*256,(xy(a,b,z)[1]-y)*256)for a,b in r.coords]
  draw.polygon(ring(p.exterior),fill=255)
  for q in p.interiors:draw.polygon(ring(q),fill=0)
 return np.array(out)>0
def fetch(job):
 kind,z,x,y=job;path=CACHE/f'{kind}-{z}-{x}-{y}.png'
 if not path.exists() and kind in current_ids:
  oldname=old_ids[kind]['name'];prior=ROOT/f'qa/gap-expansion/source/fine-{oldname}-{z}-{x}-{y}.png'
  if prior.exists():atomic(path,prior.read_bytes())
 if not path.exists():
  name=current_ids[kind]['name'] if kind in current_ids else kind
  if kind=='esri':url=f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'
  else:url=f'https://basemaps.linz.govt.nz/v1/tiles/{name}/WebMercatorQuad/{z}/{x}/{y}.png?api={KEY}'
  for attempt in range(3):
   try:
    with urllib.request.urlopen(url,timeout=35)as response:data=response.read()
    im=Image.open(io.BytesIO(data));assert im.size==(256,256);im.verify();atomic(path,data);break
   except Exception:
    if attempt==2:raise RuntimeError(f'Incomplete source {kind}/{z}/{x}/{y}; resume cached work')from None
 data=path.read_bytes();source_hashes[path.name]=hashlib.sha256(data).hexdigest()
 return job,np.array(Image.open(io.BytesIO(data)).convert('RGBA'))
def batch(jobs):
 with concurrent.futures.ThreadPoolExecutor(max_workers=24)as pool:yield from pool.map(fetch,jobs)
def fine_for(x,y,z):return sorted({records[int(i)]['id']for i in tree.query(tilebox(x,y,z),predicate='intersects')})
print(json.dumps({'stage':'footprints'}),flush=True)
scope=box(165.5,-47.5,179.5,-34)
for ident,row in current_ids.items():
 if row['category']in ['Bathymetry','Elevation']or row['name'].endswith('-10m'):continue
 path=AUDIT/'footprints'/(old_ids[ident]['name']+'.geojson');assert path.exists(),row['name']
 geoms=[make_valid(shape(f['geometry']))for f in load_json(path)['features']if shape(f['geometry']).intersects(scope)]
 if geoms:records.append({'id':ident,'name':row['name'],'geometry':unary_union(geoms)})
tree=STRtree([r['geometry']for r in records])
# These rectangles only locate pixels to inspect. They never become masks.
rects=[(171.2,-41.9,172.9,-40.8),(170.7,-42.6,172.2,-41.9),(170.2,-43.2,171.7,-42.6),(169.7,-43.85,171,-43.2),(168.6,-44.35,170.1,-43.85),(167.7,-44.7,169.1,-44.35),(167.1,-45.15,168.5,-44.7),(166.6,-45.8,168.2,-45.15),(166.2,-46.3,167.8,-45.8)]
targets=[make_valid(shape(f['geometry']))for f in load_json(AUDIT/'linz-10m-candidate-gaps.geojson')['features']if f['properties']['id']!='LINZ10-081']
land=make_valid(shape(load_json(AUDIT/'nz-land.geojson')['geometry']))
# The audit coast is generalized: leave a 1km coastal uncertainty strip alone.
land=transform(LL,transform(MERC,land).buffer(-1000))
search=unary_union([box(*r)for r in rects]+targets).intersection(land)
w,s,e,n=search.bounds;xa,ya=map(math.floor,xy(w,n,11));xb,yb=map(math.floor,xy(e,s,11));scan={}
for y in range(ya,yb+1):
 for x in range(xa,xb+1):
  if not search.intersects(tilebox(x,y,11)):continue
  mask=raster(search,x,y,11)
  if mask.any():scan[(x,y)]=mask
jobs=[(ident,11,x,y)for x,y in scan for ident in fine_for(x,y,11)]
screen_requests=len(jobs)
print(json.dumps({'stage':'screen-fine-alpha','tiles':len(scan),'requests':len(jobs)}),flush=True)
unions={k:np.zeros((256,256),dtype=bool)for k in scan}
for i,((ident,z,x,y),a)in enumerate(batch(jobs)):
 unions[(x,y)]|=a[:,:,3]>0
 if i%100==0:save(WORK/'progress.json',{'stage':'screen-fine-alpha','done':i,'total':len(jobs)});print(json.dumps({'screen':i,'total':len(jobs)}),flush=True)
detail={}
for (x,y),mask in scan.items():
 # A screening pixel touched by real aerial imagery cannot seed a gap.
 candidate=mask&~binary_dilation(unions[(x,y)],iterations=1)
 for dy in range(4):
  for dx in range(4):
   if candidate[dy*64:(dy+1)*64,dx*64:(dx+1)*64].any():detail[(x*4+dx,y*4+dy)]=None
# Always recheck every previously replaced mask tile against current fine alpha.
old_atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',subprocess.check_output(['git','show','c59d919d1577de82f0b8a03e5723e1fb5931c2ad:public/gap-imagery-atlas.js'],cwd=ROOT,text=True))[1])
for k in old_atlas['entries']:detail.setdefault(tuple(map(int,k.split('/'))),None)
jobs=[(ident,Z,x,y)for x,y in detail for ident in fine_for(x,y,Z)]
print(json.dumps({'stage':'refine-fine-alpha','tiles':len(detail),'requests':len(jobs)}),flush=True)
fine={k:np.zeros((256,256),dtype=bool)for k in detail}
for i,((ident,z,x,y),a)in enumerate(batch(jobs)):
 fine[(x,y)]|=a[:,:,3]>0
 if i%100==0:save(WORK/'progress.json',{'stage':'refine-fine-alpha','done':i,'total':len(jobs)});print(json.dumps({'refine':i,'total':len(jobs)}),flush=True)
raw_masks={};old_masks={}
for x,y in detail:
 candidate=raster(search,x,y,Z)if search.intersects(tilebox(x,y,Z))else np.zeros((256,256),bool)
 old=old_atlas['entries'].get(f'{x}/{y}')
 if old:old_masks[(x,y)]=np.array(Image.open(ROOT/'public'/old['maskURL'].lstrip('/')))[:,:,3]>0
 else:old_masks[(x,y)]=np.zeros((256,256),bool)
 # Protect the full resampling footprint of any measured fine-survey alpha.
 candidate=(candidate|old_masks[(x,y)])&~binary_dilation(fine[(x,y)],iterations=2)
 # Preserve the existing summit safety exclusion independently of colour.
 xx=(np.arange(256)+.5)/256+x;yy=(np.arange(256)+.5)/256+y;cx,cy=xy(170.141,-43.595,Z);mpp=40075016.686*math.cos(math.radians(-43.595))/(2**Z*256)
 candidate[((xx[None,:]-cx)*256*mpp)**2+((yy[:,None]-cy)*256*mpp)**2<1100**2]=False
 if candidate.any():raw_masks[(x,y)]=candidate
# Match every new mask pixel against the lossless national 10m source.
# A representative sample alone cannot authorize a complete connected region.
new_tiles=[k for k,v in raw_masks.items()if np.any(v&~old_masks[k])]
jobs=[(kind,Z,x,y)for x,y in new_tiles for kind in ['aerial','new-zealand-2025-2026-10m']]
print(json.dumps({'stage':'verify-new-pixels','tiles':len(new_tiles),'requests':len(jobs)}),flush=True)
samples={job:a for job,a in batch(jobs)};verified=[];rejected=[]
verified_masks={k:(v&old_masks[k])for k,v in raw_masks.items()}
for x,y in new_tiles:
 fresh=raw_masks[(x,y)]&~old_masks[(x,y)]
 a=samples[('aerial',Z,x,y)];b=samples[('new-zealand-2025-2026-10m',Z,x,y)]
 exact=np.all(a==b,axis=2)&(a[:,:,3]==255)
 safe=fresh&~binary_dilation(~exact,iterations=1)
 labels,count=label(safe)
 for number in range(1,count+1):
  component=labels==number
  if component.sum()<16:continue
  from scipy.ndimage import distance_transform_edt
  sy,sx=np.unravel_index(np.argmax(distance_transform_edt(component)),component.shape)
  lon,lat=lonlat(x+(sx+.5)/256,y+(sy+.5)/256,Z)
  verified_masks[(x,y)]|=component
  verified.append({'x':x,'y':y,'lon':lon,'lat':lat,'pixels':int(component.sum()),'exact10m':True})
 excluded=int((fresh&~verified_masks[(x,y)]).sum())
 if excluded:rejected.append({'x':x,'y':y,'pixels':excluded})
verified_masks={k:v for k,v in verified_masks.items()if v.any()}
assert all(not np.any(v&fine[k])for k,v in verified_masks.items())
np.savez_compressed(WORK/'verified-masks.npz',**{f'{x}_{y}':v.astype('uint8')for(x,y),v in verified_masks.items()})
save(WORK/'verification.json',{'sourceIDsUnchanged':True,'fineSurveys':len(records),'screenTiles':len(scan),'screenRequests':screen_requests,'maskTiles':len(verified_masks),'verifiedNewPatches':verified,'unconfirmedPatchesExcluded':rejected,'finePixelsReplaced':0,'newPixelVerification':'Every new native z13 mask pixel matches current aerial and national 10m lossless RGBA exactly; nonmatches and adjacent pixels excluded','sources':source_hashes,'maskZoom':Z,'fineGuardPixels':2,'coastalUncertaintyMetres':1000})
print(json.dumps({'done':True,'maskTiles':len(verified_masks),'verifiedNewPatches':len(verified),'excluded':len(rejected)}),flush=True)
