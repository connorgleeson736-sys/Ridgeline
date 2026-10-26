"""Use LINZ's published survey capture areas, never colour-derived boundaries."""
from pathlib import Path
import argparse,json,math,re,io,gzip,hashlib,urllib.request,concurrent.futures
import numpy as np
from PIL import Image
from shapely import make_valid,contains_xy,prepare
from shapely.geometry import shape,box,Point,mapping
from shapely.ops import unary_union,transform
from shapely.strtree import STRtree
from pyproj import Transformer
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--audit',type=Path,required=True);p.add_argument('--cache',type=Path,required=True);p.add_argument('--config',type=Path,default=ROOT/'qa/official-surveys/aerial-config.json');a=p.parse_args();W=a.work;CACHE=a.cache
manifest=json.loads((W/'manifest.json').read_text());config=json.loads(a.config.read_text());old=json.loads((a.audit/'live-aerial-config.json').read_text());oldmap={r['3857']:r for r in old['layers']if r.get('3857')};rows={r['3857']:r for r in config['layers']if r.get('3857')}
latest=json.load(urllib.request.urlopen('https://basemaps.linz.govt.nz/v1/config/aerial.json',timeout=30));assert {r.get('3857')for r in latest['layers']}=={r.get('3857')for r in config['layers']},'Live source IDs changed; rematch surveys before proceeding'
project=Transformer.from_crs(4326,2193,always_xy=True).transform;unproject=Transformer.from_crs(2193,4326,always_xy=True).transform;merc=Transformer.from_crs(4326,3857,always_xy=True).transform
scope=box(165.5,-47.5,179.5,-34);fine=[];records=[];features=[];missing=[]
for r in manifest:
 if 'captureURL'in r:
  data=(W/r['id']/'capture-area.geojson').read_bytes();assert hashlib.sha256(data).hexdigest()==r['sha256'];g=make_valid(shape(json.loads(data)['geometry']))
  if not g.intersects(scope):continue
  features.append({'type':'Feature','properties':{k:r.get(k)for k in ['id','name','title','captureURL','gsd']},'geometry':mapping(g)})
  if r['name'].endswith('-10m'):coarse=transform(project,g);continue
  fine.append(transform(project,g))
 else:
  # Missing island capture areas are protected by LINZ's complete source-tile
  # footprints. They never become assumed gaps or approximate replacement masks.
  missing.append(r['name'])
 path=a.audit/'footprints'/(oldmap[r['id']]['name']+'.geojson');v=json.loads(path.read_text());gs=[make_valid(shape(f['geometry']))for f in v['features']if shape(f['geometry']).intersects(scope)]
 if gs:
  footprint=unary_union(gs);records.append({'id':r['id'],'geometry':footprint})
  if 'captureURL'not in r:fine.append(transform(project,footprint))
print(json.dumps({'stage':'official-geometry','captureAreas':len(features),'protectedSurveys':len(fine)}),flush=True)
fine=unary_union(fine)
land=transform(project,make_valid(shape(json.loads((a.audit/'nz-land.geojson').read_text())['geometry']))).buffer(-1000)
water=unary_union([transform(project,make_valid(shape(f['geometry'])))for f in json.loads((a.audit/'linz-10m-candidate-gaps.geojson').read_text())['features']if f['properties']['id']=='LINZ10-081'])
base=coarse.intersection(land).difference(water.union(Point(*project(170.141,-43.595)).buffer(1100)))
# Refine simplified polygons in a small sampling envelope. Actual fine-survey
# alpha vetoes replacement; no fixed-width coarse-imagery strip remains.
nominal=base.difference(fine)
nominal=unary_union([g for g in nominal.geoms if g.area>=10000])if hasattr(nominal,'geoms')else nominal
gap=nominal.buffer(50,quad_segs=1,join_style=2).intersection(base)
seam=nominal.boundary.buffer(125,quad_segs=1,join_style=2).intersection(gap)
(W/'nominal-border.geojson').write_text(json.dumps(mapping(transform(unproject,nominal)),separators=(',',':')))
(W/'seam-band.geojson').write_text(json.dumps(mapping(transform(unproject,seam)),separators=(',',':')))
gap=unary_union([g for g in gap.geoms if g.area>=10000])if hasattr(gap,'geoms')else gap
geo=transform(unproject,gap);(W/'official-gaps.geojson').write_text(json.dumps(mapping(geo),separators=(',',':')))
C=40075016.68557849
pix=lambda x,y,z=None:((np.asarray(x)+C/2)/C*8192,(C/2-np.asarray(y))/C*8192)
gp=transform(pix,transform(merc,geo));prepare(gp);w,s,e,n=gp.bounds;masks={}
for y in range(math.floor(s),math.floor(n)+1):
 for x in range(math.floor(w),math.floor(e)+1):
  if not gp.intersects(box(x,y,x+1,y+1)):continue
  gx=x+(np.arange(256)+.5)/256;gy=y+(np.arange(256)+.5)/256;mask=contains_xy(gp,gx[None,:],gy[:,None])
  if gp.intersection(box(x,y,x+1,y+1)).area>0:masks[(x,y)]=mask
# Validate each intersecting current aerial survey, including its partial alpha.
raw_masks={k:v.copy()for k,v in masks.items()}
seam_pixels=transform(pix,transform(merc,transform(unproject,seam)));prepare(seam_pixels)
tree=STRtree([r['geometry']for r in records])
def ll(x,y):return x/8192*360-180,math.degrees(math.atan(math.sinh(math.pi*(1-2*y/8192))))
def tilebox(x,y):
 w,n=ll(x,y);e,s=ll(x+1,y+1);return box(w,s,e,n)
jobs=[(records[int(i)]['id'],x,y)for x,y in masks for i in tree.query(tilebox(x,y),predicate='intersects')]
KEY=re.search("const linzApiKey='([^']+)'",(ROOT/'public/app.js').read_text())[1]
def fetch(job):
 ident,x,y=job;path=CACHE/f'{ident}-13-{x}-{y}.png'
 if not path.exists():
  prior=ROOT/f"qa/gap-expansion/source/fine-{oldmap[ident]['name']}-13-{x}-{y}.png"
  if prior.exists():path.write_bytes(prior.read_bytes())
 if not path.exists():
  url=f"https://basemaps.linz.govt.nz/v1/tiles/{rows[ident]['name']}/WebMercatorQuad/13/{x}/{y}.png?api={KEY}"
  for attempt in range(3):
   try:
    data=urllib.request.urlopen(url,timeout=35).read();im=Image.open(io.BytesIO(data));assert im.size==(256,256);im.verify();path.write_bytes(data);break
   except Exception:
    if attempt==2:raise RuntimeError(f'Incomplete survey evidence {ident}/{x}/{y}')from None
 data=path.read_bytes();image=np.array(Image.open(io.BytesIO(data)).convert('RGBA'));assert image.shape==(256,256,4)
 return job,image[:,:,3]>0,hashlib.sha256(data).hexdigest()
print(json.dumps({'stage':'survey-alpha-guard','maskTiles':len(masks),'requests':len(jobs)}),flush=True);overlap=0;sources={};fine_pixels={k:np.zeros((256,256),bool)for k in masks};tile_sources={};outside_band=0
with concurrent.futures.ThreadPoolExecutor(max_workers=20)as pool:
 for i,((ident,x,y),count,digest)in enumerate(pool.map(fetch,jobs)):
  fine_pixels[(x,y)]|=count;sources[f'{ident}-13-{x}-{y}.png']=digest
  tile_sources.setdefault(f'{x}/{y}',[]).append({'name':rows[ident]['name']})
  if i%100==0:print(json.dumps({'checked':i,'total':len(jobs)}),flush=True)
for (x,y),mask in masks.items():
 gx=x+(np.arange(256)+.5)/256;gy=y+(np.arange(256)+.5)/256;band=contains_xy(seam_pixels,gx[None,:],gy[:,None]);valid=fine_pixels[(x,y)]
 outside_band+=int(np.count_nonzero(mask&valid&~band));mask&=~valid
assert outside_band==0,'Aerial coverage outside the runtime protection band; do not publish'
np.savez_compressed(W/'raw-vector-masks.npz',**{f'{x}_{y}':v.astype('uint8')for(x,y),v in raw_masks.items()})
(W/'tile-fine-sources.json').write_text(json.dumps(tile_sources,separators=(',',':')))
np.savez_compressed(W/'verified-masks.npz',**{f'{x}_{y}':v.astype('uint8')for(x,y),v in masks.items()})
report={'method':'Official LINZ polygons with native survey alpha veto at each requested boundary tile; no fixed 10m guard strip','maskZoom':13,'sourceIDsUnchanged':True,'officialCaptureAreas':len(features),'protectedSurveySources':len(records),'maskTiles':len(masks),'gapAreaKm2':gap.area/1e6,'finePixelsReplaced':overlap,'surveyEvidenceTiles':len(jobs),'surveyGuardMetres':0,'runtimeFineAlphaVeto':True,'seamBandMetres':125,'outsideSeamAerialPixels':outside_band,'coastalUncertaintyMetres':1000,'missingIslandCaptureAreasProtected':missing,'sources':sources,'catalogue':'https://nz-imagery.s3-ap-southeast-2.amazonaws.com/catalog.json'}
(W/'verification.json').write_text(json.dumps(report,indent=2));(W/'capture-areas.geojson').write_text(json.dumps({'type':'FeatureCollection','features':features},separators=(',',':')));print(json.dumps({'done':True,'maskTiles':len(masks),'aerialOverlap':overlap}),flush=True)
