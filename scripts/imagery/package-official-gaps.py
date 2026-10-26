"""Package official LINZ vector boundaries and matching imagery masks."""
from pathlib import Path
import argparse,io,json,hashlib,math,concurrent.futures,urllib.request
import numpy as np
from PIL import Image
from shapely import prepare
from shapely.geometry import box,Point,LineString,mapping
from shapely.ops import unary_union,transform
from pyproj import Transformer
from shapely.geometry import shape
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--cache',type=Path,required=True);args=p.parse_args();WORK=args.work;CACHE=args.cache
ASSETS=ROOT/'public/assets/gap-imagery-v3';ASSETS.mkdir(parents=True,exist_ok=True)
masks={tuple(map(int,k.split('_'))):v.astype(bool)for k,v in np.load(WORK/'verified-masks.npz').items()}
def asset(im):
 b=io.BytesIO();im.save(b,format='PNG',optimize=True);data=b.getvalue();name=hashlib.sha256(data).hexdigest()[:24]+'.png';(ASSETS/name).write_bytes(data);return '/assets/gap-imagery-v3/'+name
def esri(k):
 x,y=k
 if not masks[k].any():return k,np.zeros((256,256,4),dtype='uint8')
 path=CACHE/f'esri-13-{x}-{y}.png';prior=ROOT/f'qa/gap-expansion/source/esri-13-{x}-{y}.png'
 if not path.exists():
  if prior.exists():data=prior.read_bytes()
  else:
   for attempt in range(3):
    try:data=urllib.request.urlopen(f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/13/{y}/{x}',timeout=35).read();break
    except Exception:
     if attempt==2:raise RuntimeError(f'Esri download failed {x}/{y}')from None
  Image.open(io.BytesIO(data)).verify();path.write_bytes(data)
 a=np.array(Image.open(path).convert('RGBA'));assert a.shape==(256,256,4);a[:,:,3]=masks[k]*255;a[a[:,:,3]==0,:3]=0;return k,a
# The authoritative geometry is the published survey polygons, never a contour
# traced from RGB colours or from a native raster mask.
C=40075016.68557849;merc=Transformer.from_crs(4326,3857,always_xy=True).transform
pix=lambda x,y,z=None:((np.asarray(x)+C/2)/C*8192,(C/2-np.asarray(y))/C*8192)
geometry=transform(pix,transform(merc,shape(json.loads((WORK/'official-gaps.geojson').read_text()))))
# Retain vector fragments smaller than a native z13 pixel: they become
# visible when zooming in, even if their coarse mask has no opaque pixels.
w,s,e,n=geometry.bounds
for y in range(math.floor(s),math.floor(n)+1):
 for x in range(math.floor(w),math.floor(e)+1):
  if (x,y)not in masks and geometry.intersects(box(x,y,x+1,y+1))and geometry.intersection(box(x,y,x+1,y+1)).area>0:masks[(x,y)]=np.zeros((256,256),dtype=bool)
def polygons(g):
 if g.geom_type=='Polygon':yield g
 elif hasattr(g,'geoms'):
  for p in g.geoms:yield from polygons(p)
entries={}
for (x,y),m in sorted(masks.items()):
 a=np.zeros((256,256,4),dtype='uint8');a[:,:,:3]=128;a[:,:,3]=m*255
 clip=geometry.intersection(box(x-4/256,y-4/256,x+1+4/256,y+1+4/256));rings=[]
 for poly in polygons(clip):
  for ring in [poly.exterior,*poly.interiors]:rings.append([[round((px-x)*256,7),round((py-y)*256,7)]for px,py in ring.coords])
 if not rings:continue
 entries[f'{x}/{y}']={'z':13,'x':x,'y':y,'width':256,'height':256,'vector':True,'maskURL':asset(Image.fromarray(a)),'seamOffsets':False,'outer':rings}
print(json.dumps({'stage':'official-vector-boundary','tiles':len(entries)}),flush=True)
# Use this same source geometry for both the border and per-tile masks.
exact_boundary=geometry.boundary
prepare(geometry)
boundary=exact_boundary
def ll(x,y,z=None):return np.round(np.asarray(x)/8192*360-180,7),np.round(np.degrees(np.arctan(np.sinh(np.pi*(1-2*np.asarray(y)/8192)))),7)
features=[{'type':'Feature','properties':{'kind':'boundary'},'geometry':mapping(transform(ll,boundary))}]
lines=list(boundary.geoms)if hasattr(boundary,'geoms')else[boundary];pairs=[]
# Labels are fixed geographic points on verified opposite sides, selected by zoom.
for tier,(minz,maxz,spacing,offset)in enumerate([(10,13,4,1.5),(13,16,1,.2),(16,19,.3,.025),(19,24,.1,.003125)]):
 count=0
 for line in sorted(lines,key=lambda g:g.length,reverse=True):
  if line.length<offset*4:continue
  n=max(1,math.ceil(line.length/spacing))
  for i in range(n):
   d=line.length*(i+.5)/n;point=line.interpolate(d);a=line.interpolate(max(0,d-.001));b=line.interpolate(min(line.length,d+.001));dx,dy=b.x-a.x,b.y-a.y;length=math.hypot(dx,dy)
   if not length:continue
   for scale in [1]:
    left=Point(point.x-dy/length*offset*scale,point.y+dx/length*offset*scale);right=Point(point.x+dy/length*offset*scale,point.y-dx/length*offset*scale)
    if geometry.contains(left)!=geometry.contains(right):break
   else:continue
   # Exclude labels that would sit extremely close to another boundary.
   if min(exact_boundary.distance(left),exact_boundary.distance(right))<offset*.75:continue
   pair=[]
   for q in [left,right]:
    inside=geometry.contains(q);label='Esri World Imagery'if inside else'LINZ imagery';coords=list(ll(q.x,q.y));features.append({'type':'Feature','properties':{'kind':'label','label':label,'tier':tier,'provider':'esri'if inside else'linz'},'geometry':{'type':'Point','coordinates':coords}});pair.append({'x':q.x,'y':q.y,'inside':inside})
   pairs.append({'tier':tier,'points':pair});count+=1
   if count>=1800:break
  if count>=1800:break
 print(json.dumps({'tier':tier,'labelPairs':count}),flush=True)
bdata=(json.dumps({'type':'FeatureCollection','features':features},separators=(',',':'))+'\n').encode();name=hashlib.sha256(bdata).hexdigest()[:24]+'.geojson';bp=ROOT/'public/assets/imagery-boundary-v3';bp.mkdir(parents=True,exist_ok=True);(bp/name).write_bytes(bdata)
(ROOT/'public/imagery-boundary-data.js').write_text('globalThis.RidgeImageryBoundaryData='+json.dumps({'version':'gap-v3','url':'/assets/imagery-boundary-v3/'+name})+';\n')
with concurrent.futures.ThreadPoolExecutor(max_workers=12)as pool:current={f'13/{x}/{y}':a for (x,y),a in pool.map(esri,masks)}
coarse={}
for z in range(12,-1,-1):
 parents={}
 for key,a in current.items():
  _,x,y=map(int,key.split('/'));key=f'{z}/{x//2}/{y//2}';parents.setdefault(key,np.zeros((512,512,4),dtype='uint8'))[(y%2)*256:(y%2+1)*256,(x%2)*256:(x%2+1)*256]=a
 current={}
 for key,a in parents.items():
  im=Image.fromarray(a).resize((256,256),Image.Resampling.BOX);v=np.array(im)
  if v[:,:,3].any():coarse[key]={'url':asset(im),'full':bool(np.all(v[:,:,3]==255))};current[key]=v
old=json.loads(__import__('re').search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1])
data={'version':'gap-v3','z':13,'entries':entries,'coarse':coarse,'targets':old['targets'],'sourceZoom':13}
(ROOT/'public/gap-imagery-atlas.js').write_text('/* Official LINZ capture areas; see OFFICIAL_IMAGERY_BOUNDARIES.md. */\n(function(r){const data='+json.dumps(data,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeGapAtlas=data;})(globalThis);\n')
report=json.loads((WORK/'verification.json').read_text());report['maskTiles']=len(entries);report['boundary']={'url':'/assets/imagery-boundary-v3/'+name,'bytes':len(bdata),'labelPairs':len(pairs),'allPairsOnOppositeSides':all(p['points'][0]['inside']!=p['points'][1]['inside']for p in pairs)};report['coarseOverlayTiles']=len(coarse)
(ROOT/'OFFICIAL_IMAGERY_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'done':True,'maskTiles':len(entries),'coarseTiles':len(coarse),'boundaryBytes':len(bdata)}),flush=True)
