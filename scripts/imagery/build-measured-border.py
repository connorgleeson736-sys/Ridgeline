"""Display the measured Esri/LINZ selection instead of nominal survey holes.

The selected source masks remain unchanged. Display contours omit regions below
10,000 square metres (the existing nominal-gap threshold), dissolve internal tile
edges, and simplify by at most one source-mask pixel. Source z13 accuracy is an
approximation at closer zooms, rather than a claim of survey-grade precision.
Requires numpy, Pillow and shapely. Use --verify for an independent full-pixel check.
"""
from pathlib import Path
import collections,hashlib,json,math,re
import numpy as np
from PIL import Image
from shapely.geometry import LineString,MultiLineString,Polygon,Point
ROOT=Path(__file__).resolve().parents[2]
import argparse
p=argparse.ArgumentParser(description='Build display borders from verified source-selection masks; never modify imagery masks.')
p.add_argument('--report',type=Path,required=True);p.add_argument('--verify',action='store_true');args=p.parse_args();out=args.report.parent;out.mkdir(parents=True,exist_ok=True)
atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1]);edges={};pixels=0;sources={}
def edge(a,b):
 reverse=(b,a)
 if reverse in edges:del edges[reverse]
 else:edges[(a,b)]=True
for k,e in atlas['entries'].items():
 p=ROOT/'public'/e['maskURL'].lstrip('/');raw=p.read_bytes();sources[e['maskURL']]=hashlib.sha256(raw).hexdigest();m=np.array(Image.open(p).getchannel('A'))>0;pixels+=int(m.sum());x0,y0=e['x']*256,e['y']*256
 top=m&~np.pad(m[:-1],((1,0),(0,0)));bottom=m&~np.pad(m[1:],((0,1),(0,0)));left=m&~np.pad(m[:,:-1],((0,0),(1,0)));right=m&~np.pad(m[:,1:],((0,0),(0,1)))
 for arr,delta in [(top,(0,0,1,0)),(right,(1,0,1,1)),(bottom,(1,1,0,1)),(left,(0,1,0,0))]:
  for y,x in zip(*np.nonzero(arr)):
   x,y=int(x)+x0,int(y)+y0;edge((x+delta[0],y+delta[1]),(x+delta[2],y+delta[3]))
adj=collections.defaultdict(set)
for a,b in edges:adj[a].add(b)
del edges
rings=[];signed=0
D={(1,0):0,(0,1):1,(-1,0):2,(0,-1):3}
while adj:
 first=next(iter(adj));p=first;prev=None;ring=[first]
 while True:
  choices=adj[p]
  if prev is None:q=next(iter(choices))
  else:
   direction=D[(p[0]-prev[0],p[1]-prev[1])]
   rank={1:0,0:1,3:2,2:3}
   q=min(choices,key=lambda q:rank[(D[(q[0]-p[0],q[1]-p[1])]-direction)%4])
  choices.remove(q)
  if not choices:del adj[p]
  ring.append(q);prev,p=p,q
  if p==first:break
  assert p in adj,'Open contour'
 area2=sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(ring,ring[1:]));signed+=area2;rings.append((ring,area2))
assert signed==pixels*2,(signed,pixels*2)
W=256*2**atlas['z'];circ=40075016.68557849
def ll(p):return [round(p[0]/W*360-180,7),round(math.degrees(math.atan(math.sinh(math.pi*(1-2*p[1]/W)))),7)]
lines=[];removed=0;kept=[]
for ring,area2 in rings:
 midy=(min(p[1]for p in ring)+max(p[1]for p in ring))/2
 lat=math.atan(math.sinh(math.pi*(1-2*midy/W)));pixel_metres=circ/W*math.cos(lat);area=abs(area2)/2*pixel_metres**2
 if area<10000:removed+=1;continue
 kept.append(ring)
# Simplify the complete line collection together: adjacent outlines must not
# cross or collapse into each other when stair-step mask edges are reduced.
smoothed=MultiLineString(kept).simplify(1.,preserve_topology=True)
for line in smoothed.geoms:lines.append([ll(p) for p in line.coords])
geo={'type':'FeatureCollection','features':[{'type':'Feature','properties':{'kind':'boundary','method':'measured-source-selection','sourceZoom':atlas['z']},'geometry':{'type':'MultiLineString','coordinates':lines}}]}
data=(json.dumps(geo,separators=(',',':'))+'\n').encode();asset=ROOT/'public/assets/imagery-boundary-v5'/(hashlib.sha256(data).hexdigest()[:24]+'.geojson')
old=json.loads((ROOT/'public/assets/imagery-boundary-v4/335bcd9378652dfa88919f3b.geojson').read_text())['features'][0]['geometry']['coordinates']
near=lambda l:LineString(l).distance(Point(168.7089609,-44.36369167))<.025
report={'sourceMasks':len(atlas['entries']),'uniqueMaskFiles':len(sources),'selectedPixels':pixels,'contourSignedDoubleArea':signed,'exactAreaVerified':True,'rawContours':len(rings),'displayContours':len(lines),'displayContoursBelow10000m2Removed':removed,'displaySimplificationMaskPixels':1.,'maskZoom':atlas['z'],'sourceMaskSha256':sources,'oldRollingOutlines':sum(near(l) for l in old),'newRollingOutlines':sum(near(l) for l in lines),'outputAsset':'/'+str(asset.relative_to(ROOT/'public')),'outputBytes':len(data),'outputSha256':hashlib.sha256(data).hexdigest()}
# Independent prepared geometry check, at every original mask pixel centre.
if args.verify:
 from shapely import symmetric_difference_all,contains_xy,prepare
 g=symmetric_difference_all([Polygon(ring) for ring,_ in rings]);prepare(g)
 checked=0
 for e in atlas['entries'].values():
  expected=np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')).getchannel('A'))>0
  xs=e['x']*256+np.arange(256)+.5;ys=e['y']*256+np.arange(256)+.5
  actual=contains_xy(g,xs[None,:],ys[:,None]);assert np.array_equal(actual,expected),str((e['x'],e['y']))
  checked+=expected.size
 report['independentlyVerifiedMaskPixels']=checked
asset.parent.mkdir(parents=True,exist_ok=True);asset.write_bytes(data)
(ROOT/'public/imagery-boundary-data.js').write_text('globalThis.RidgeImageryBoundaryData='+json.dumps({'version':'gap-v5','url':'/'+str(asset.relative_to(ROOT/'public')),'sourceZoom':atlas['z'],'method':'measured-source-selection'},separators=(',',':'))+';\n')
official=ROOT/'OFFICIAL_IMAGERY_VALIDATION.json'
if official.exists():
 evidence=json.loads(official.read_text())
 evidence['displayBoundary']={'version':'gap-v5','url':report['outputAsset'],'bytes':len(data),'sourceZoom':atlas['z'],'sourceMasks':len(atlas['entries']),'method':'Measured source-selection contours; display only','minimumOutlinedAreaM2':10000,'simplificationMaskPixels':1.,'nominalReferenceURL':evidence.get('boundary',{}).get('url')}
 official.write_text(json.dumps(evidence,indent=2)+'\n')
args.report.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='sourceMaskSha256'},indent=2))
