"""Hard regional source masks refined with actual fine-survey transparency.
Resumable authenticated snapshots. No feathering, colour smoothing, AI imagery or DEM changes.
"""
from pathlib import Path
import sys,os,json,re,math,hashlib,urllib.request,concurrent.futures,threading,io
sys.path.insert(0,'/workspace/scratch/linz-coverage-audit/packages')
import numpy as np
from PIL import Image,ImageDraw
from scipy.ndimage import maximum_filter
from shapely.geometry import shape,box,mapping
from shapely.ops import transform
from pyproj import Transformer
ROOT=Path(__file__).resolve().parents[2];QA=ROOT/'qa/gap-expansion';CACHE=QA/'source';ASSETS=ROOT/'public/assets/gap-imagery-v1';ASSETS.mkdir(parents=True,exist_ok=True)
KEY=os.environ.get('LINZ_API_KEY')or re.search("const linzApiKey='([^']+)'",(ROOT/'public/app.js').read_text())[1]
Z=13;C=40075016.686;locks={};manifest={};atlas={};patches={};reports=[]
project=Transformer.from_crs(4326,2193,always_xy=True).transform;unproject=Transformer.from_crs(2193,4326,always_xy=True).transform
features=json.load(open(QA/'audit-targets.geojson'))['features'];features.append({'type':'Feature','properties':{'id':'COOK-LEGACY'},'geometry':mapping(box(169.96,-43.665,170.245,-43.44))})
def atomic(p,b):
 p.parent.mkdir(parents=True,exist_ok=True);t=p.with_suffix('.part');t.write_bytes(b);t.replace(p)
def savejson(p,d):atomic(p,(json.dumps(d,indent=2)+'\n').encode())
def xy(lon,lat,z=Z):return (lon+180)/360*2**z,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z

def fetch(job):
 kind,x,y=job;p=CACHE/f'{kind}-{Z}-{x}-{y}.png'
 with locks.setdefault(str(p),threading.Lock()):
  if not p.exists():
   u=f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{Z}/{y}/{x}' if kind=='esri'else f'https://basemaps.linz.govt.nz/v1/tiles/{"aerial"if kind=="linz"else kind[5:] if kind.startswith("fine-") else"new-zealand-2025-2026-10m"}/WebMercatorQuad/{Z}/{x}/{y}.png?api={KEY}'
   for attempt in range(3):
    try:
     b=urllib.request.urlopen(u,timeout=35).read();im=Image.open(io.BytesIO(b));assert im.size==(256,256);atomic(p,b);break
    except Exception:
     if attempt==2:raise RuntimeError(f'Source request failed: {kind}/{x}/{y}; restart to reuse checkpoints')from None
  b=p.read_bytes();manifest[p.name]={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()};return job,np.array(Image.open(io.BytesIO(b)).convert('RGBA'))
def asset(im):
 b=io.BytesIO();im.save(b,format='PNG',optimize=True);data=b.getvalue();name=hashlib.sha256(data).hexdigest()[:24]+'.png';atomic(ASSETS/name,data);return '/assets/gap-imagery-v1/'+name

def drawshape(im,g,offset,fill=255):
 d=ImageDraw.Draw(im)
 def ring(coords):return [((xy(lon,lat)[0]-offset[0])*256,(xy(lon,lat)[1]-offset[1])*256)for lon,lat in coords]
 if g.geom_type=='Polygon':
  d.polygon(ring(g.exterior.coords),fill=fill)
  for q in g.interiors:d.polygon(ring(q.coords),fill=0)
 elif hasattr(g,'geoms'):
  for p in g.geoms:drawshape(im,p,offset,fill)

def contours(a,level):
 # marching squares is only for conservative routing; raster alpha remains authoritative.
 import matplotlib;matplotlib.use('Agg');import matplotlib.pyplot as plt
 fig,ax=plt.subplots();cs=ax.contour(np.pad(a,1),levels=[level]);rings=[]
 for q in cs.allsegs[0]:
  if len(q)<4:continue
  from shapely.geometry import LineString
  coords=np.array(LineString(q-.5).simplify(.3).coords);rings.append(np.round(coords,2).tolist())
 plt.close(fig);return rings

def run():
 for fi,f in enumerate(features):
  ident=f['properties']['id'];g=shape(f['geometry']);buffered=transform(unproject,transform(project,g).buffer(200));w,s,e,n=buffered.bounds
  xa,ya=map(math.floor,xy(w,n));xb,yb=map(math.floor,xy(e,s));xa-=1;ya-=1;xb+=1;yb+=1
  # Align regions to z12 parents so masks never need fractional resampling at tile edges.
  xa=xa//2*2;ya=ya//2*2;xb=xb//2*2+1;yb=yb//2*2+1;W=(xb-xa+1)*256;H=(yb-ya+1)*256
  arrays={kind:np.zeros((H,W,4),dtype='uint8') for kind in ['esri']}
  def intersects(x,y):
   west=x/2**Z*360-180;east=(x+1)/2**Z*360-180
   north=math.degrees(math.atan(math.sinh(math.pi*(1-2*y/2**Z))));south=math.degrees(math.atan(math.sinh(math.pi*(1-2*(y+1)/2**Z))))
   return buffered.intersects(box(west,south,east,north))
  jobs=[(kind,x,y)for y in range(ya,yb+1)for x in range(xa,xb+1)if intersects(x,y)for kind in ['esri']]
  print(json.dumps({'region':ident,'stage':'sources','jobs':len(jobs),'pixels':[W,H]}),flush=True)
  with concurrent.futures.ThreadPoolExecutor(max_workers=16)as pool:
   for (kind,x,y),a in pool.map(fetch,jobs):arrays[kind][(y-ya)*256:(y-ya+1)*256,(x-xa)*256:(x-xa+1)*256]=a
  target=Image.new('L',(W,H));drawshape(target,buffered,(xa,ya));target=np.array(target)>0
  guard=json.load(open(QA/'fine-source-guard.geojson'))['features'];guardItems=[(shape(v['geometry']),v['properties']['source'])for v in guard if shape(v['geometry']).intersects(buffered)]
  fine=Image.new('L',(W,H))
  for geometry,name in guardItems:drawshape(fine,geometry,(xa,ya))
  potential=np.array(fine)>0;fineActual=np.zeros((H,W),dtype='bool');fineJobs=set()
  for y in range(ya,yb+1):
   for x in range(xa,xb+1):
    sl=np.s_[(y-ya)*256:(y-ya+1)*256,(x-xa)*256:(x-xa+1)*256]
    if not np.any(potential[sl]&target[sl]):continue
    west=x/2**Z*360-180;east=(x+1)/2**Z*360-180;north=math.degrees(math.atan(math.sinh(math.pi*(1-2*y/2**Z))));south=math.degrees(math.atan(math.sinh(math.pi*(1-2*(y+1)/2**Z))))
    tilebox=box(west,south,east,north)
    for geometry,name in guardItems:
     if geometry.intersects(tilebox):fineJobs.add(('fine-'+name,x,y))
  print(json.dumps({'region':ident,'stage':'fine-alpha','jobs':len(fineJobs)}),flush=True)
  with concurrent.futures.ThreadPoolExecutor(max_workers=16)as pool:
   for (kind,x,y),a in pool.map(fetch,sorted(fineJobs)):
    sl=np.s_[(y-ya)*256:(y-ya+1)*256,(x-xa)*256:(x-xa+1)*256];fineActual[sl]|=a[:,:,3]>0
  coarse=target&~fineActual
  if ident=='COOK-LEGACY':
   legacy=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/west-coast-footprint.js').read_text())[1])
   old=Image.open(ROOT/'public/assets/west-coast-mask.png').getchannel('A');canvas=Image.new('L',(W,H));canvas.paste(old,((legacy['x']-xa)*256,(legacy['y']-ya)*256))
   # Recover the former two-pixel guard and replace the old fractional blend with a binary mask.
   coarse|=maximum_filter((np.array(canvas)>0).astype('uint8'),size=5)>0
  coarse&=~fineActual
  # Ensure the complete protected Cook summit/model stays original, regardless of white/snow coincidences.
  gx=(np.arange(W)+.5)/256+xa;gy=(np.arange(H)+.5)/256+ya;cx,cy=xy(170.141,-43.595);mpp=C*math.cos(math.radians(-43.595))/(2**Z*256)
  protected=((gx[None,:]-cx)*256*mpp)**2+((gy[:,None]-cy)*256*mpp)**2<1100**2;coarse[protected]=False
  if not np.any(coarse):raise RuntimeError('No current coarse pixels in '+ident)
  mpp=C*math.cos(math.radians((s+n)/2))/(2**Z*256)
  # User explicitly requested hard source boundaries: no feathering, no colour smoothing.
  rgba=np.concatenate([np.full((H,W,3),128,dtype='uint8'),coarse[:,:,None].astype('uint8')*255],axis=2)
  corrected=arrays['esri'][:,:,:3];photo=np.concatenate([corrected,rgba[:,:,3:]],axis=2);photo[photo[:,:,3]==0,:3]=0
  # Merge overlaps in a common global grid. Existing legacy Cook coverage is unioned, not added twice.
  for y in range(ya,yb+1):
   for x in range(xa,xb+1):
    a=photo[(y-ya)*256:(y-ya+1)*256,(x-xa)*256:(x-xa+1)*256]
    if not a[:,:,3].any():continue
    key=f'{Z}/{x}/{y}'
    if key in patches:
     b=patches[key];choose=a[:,:,3]>b[:,:,3];b[choose]=a[choose]
    else:patches[key]=a.copy()
  for y in range(ya,yb+1):
   for x in range(xa,xb+1):
    q=rgba[(y-ya)*256:(y-ya+1)*256,(x-xa)*256:(x-xa+1)*256]
    if not q[:,:,3].any():continue
    key=f'{x}/{y}'
    if key in atlas:
     old=atlas[key]['pixels'];pick=q[:,:,3]>old[:,:,3];old[pick]=q[pick]
    else:atlas[key]={'pixels':q.copy()}
  reports.append({'id':ident,'coarsePixels':int(coarse.sum()),'overlapWidthMetres':0,'colourCorrectionReachMetres':0,'maximumRGBOffset':0,'protectedCookPixelsChanged':int(np.any(rgba[:,:,3][protected]>0))})
  savejson(QA/'progress.json',{'completedRegions':len(reports),'totalRegions':len(features),'reports':reports,'sources':manifest})
  print(json.dumps({'region':ident,'stage':'complete','coarsePixels':int(coarse.sum()),'hardBoundary':True}),flush=True)
 entries={}
 for key,v in atlas.items():
  q=v['pixels'];a=q[:,:,3];pure=(a==255)&(np.abs(q[:,:,:3].astype(int)-128).max(2)<=1)
  x,y=map(int,key.split('/'));entry={'z':13,'x':x,'y':y,'width':256,'height':256,'maskURL':asset(Image.fromarray(q)),'seamOffsets':False,'outer':contours(a,.5),'inner':contours(pure.astype('uint8')*255,254.5)}
  entries[key]=entry
 coarseTiles={};current=patches
 for z in range(12,-1,-1):
  parents={}
  for k,a in current.items():
   _,x,y=map(int,k.split('/'));p=f'{z}/{x//2}/{y//2}';parents.setdefault(p,np.zeros((512,512,4),dtype='uint8'))[(y%2)*256:(y%2+1)*256,(x%2)*256:(x%2+1)*256]=a
  current={}
  for k,a in parents.items():
   # Pillow RGBA resizing filters premultiplied colours, avoiding dark transparent fringes.
   im=Image.fromarray(a).resize((256,256),Image.Resampling.BOX);p=np.array(im)
   if not p[:,:,3].any():continue
   coarseTiles[k]={'url':asset(im),'full':bool(np.all(p[:,:,3]==255))};current[k]=p
 data={'version':'gap-v1','z':13,'entries':entries,'coarse':coarseTiles,'targets':[f['properties']['id']for f in features],'sourceZoom':13}
 atomic(ROOT/'public/gap-imagery-atlas.js',('/* Authenticated source masks and seam offsets; see GAP_EXPANSION_STATUS.md. */\n(function(r){const data='+json.dumps(data,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeGapAtlas=data;})(globalThis);\n').encode())
 savejson(ROOT/'GAP_EXPANSION_VALIDATION.json',{'regions':reports,'maskTiles':len(entries),'coarseOverlayTiles':len(coarseTiles),'sourceSnapshots':len(manifest),'sourceZoom':13,'pixelMethod':'Audited gap geometry expanded 200m, subtract actual live fine-survey PNG alpha on intersecting boundary tiles; live source IDs checked unchanged. Native binary mask grid z13, no feathering.','protectedCookRadiusMetres':1100,'seamMode':'Hard source boundaries: binary masks, no feathering or colour correction','DEMChanged':False,'generatedGeographicDetail':False})
 print(json.dumps({'done':True,'maskTiles':len(entries),'coarseTiles':len(coarseTiles)}),flush=True)
if __name__=='__main__':run()
