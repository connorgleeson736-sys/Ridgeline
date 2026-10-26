"""Independent geometric and source-evidence verification of shipped masks."""
from pathlib import Path
import argparse,json,re,math,hashlib
import numpy as np
from PIL import Image
from shapely import contains_xy,prepare
from shapely.geometry import shape,Point
from shapely.ops import transform
from pyproj import Transformer
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--cache',type=Path,required=True);a=p.parse_args()
atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1]);report=json.loads((ROOT/'OFFICIAL_IMAGERY_VALIDATION.json').read_text());assert atlas['version']=='gap-v3';assert report['finePixelsReplaced']==0
geo=shape(json.loads((a.work/'official-gaps.geojson').read_text()));merc=Transformer.from_crs(4326,3857,always_xy=True).transform;C=40075016.68557849;pix=lambda x,y,z=None:((np.asarray(x)+C/2)/C*8192,(C/2-np.asarray(y))/C*8192);g=transform(pix,transform(merc,geo));prepare(g);prepare(geo)
hashof=lambda arr:hashlib.sha256(arr.astype('uint8').tobytes()).hexdigest();fixtures=[];masks={};total=0
for key,e in atlas['entries'].items():
 assert e['vector'];x,y=e['x'],e['y'];mask=np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')).getchannel('A'));assert set(np.unique(mask))<={0,255}
 gx=x+(np.arange(256)+.5)/256;gy=y+(np.arange(256)+.5)/256;expected=contains_xy(g,gx[None,:],gy[:,None]).astype('uint8')*255;assert np.array_equal(mask,expected),key
 masks[(x,y)]=mask;total+=int((mask>0).sum());fixtures.append({'key':key,'z':13,'x':x,'y':y,'size':256,'sha256':hashof(expected)})
 # Independent close-zoom fixtures around published polygon edges, including
 # holes and sub-native fragments. Rasterise 32x32 pixel centres in the same
 # requested tile, using the full authoritative geometry rather than its clip.
 if len(fixtures)<260:
  for ring in e['outer'][:2]:
   px,py=ring[len(ring)//2];px=min(255.999999,max(.000001,px));py=min(255.999999,max(.000001,py));wx=x+px/256;wy=y+py/256
   for z in [18,19]:
    f=2**(z-13);tx,ty=math.floor(wx*f),math.floor(wy*f);xs=tx/f+(np.arange(32)+.5)/(32*f);ys=ty/f+(np.arange(32)+.5)/(32*f);expected=contains_xy(g,xs[None,:],ys[:,None]).astype('uint8')*255
    fixtures.append({'key':key,'z':z,'x':tx,'y':ty,'size':32,'sha256':hashof(expected)})
for name,digest in report['sources'].items():
 data=(a.cache/name).read_bytes();assert hashlib.sha256(data).hexdigest()==digest;x,y=map(int,Path(name).stem.split('-')[-2:]);fine=np.array(Image.open(a.cache/name).convert('RGBA'))[:,:,3]>0;assert not np.any((masks[(x,y)]>0)&fine),name
boundary=json.loads((ROOT/'public'/report['boundary']['url'].lstrip('/')).read_text());labels=0
for f in boundary['features']:
 if f['properties']['kind']!='label':continue
 inside=geo.contains(Point(*f['geometry']['coordinates']));assert inside==(f['properties']['provider']=='esri');labels+=1
assert labels==2*report['boundary']['labelPairs']
# The entire shipped boundary is the same polygon boundary, rounded to 7
# decimal places for transport. Check each vertex against the authoritative line.
line=geo.boundary;vertices=[]
def coords(v):
 if isinstance(v[0],(int,float)):yield v
 else:
  for q in v:yield from coords(q)
for f in boundary['features']:
 if f['properties']['kind']=='boundary':vertices.extend(coords(f['geometry']['coordinates']))
# Coordinates differ only by serialization rounding (<2 cm in NZ).
max_error=max(line.distance(Point(*q))for q in vertices[::max(1,len(vertices)//2000)]);assert max_error<1e-7,max_error
out=ROOT/'qa/official-surveys';out.mkdir(exist_ok=True);(out/'vector-mask-fixtures.json').write_text(json.dumps(fixtures,separators=(',',':')))
result={'maskPixels':total,'fineEvidenceTilesChecked':len(report['sources']),'validAerialPixelsReplaced':0,'providerLabelsChecked':labels,'boundaryVertices':len(vertices),'nativeAndCloseZoomFixtures':len(fixtures),'PASS':True};(out/'independent-validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
