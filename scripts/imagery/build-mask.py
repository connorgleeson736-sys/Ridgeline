from pathlib import Path
import json,math,hashlib,os
import numpy as np
from scipy.ndimage import distance_transform_edt,label
from PIL import Image,ImageDraw
import matplotlib;matplotlib.use('Agg')
import matplotlib.pyplot as plt
repo=Path(__file__).resolve().parents[2];r=Path(os.environ.get('RIDGELINE_QA_ROOT',repo/'source-cache/regional-imagery'));a=json.load(open(r/'coverage.json'));assert not a['failed']
fine=np.array(Image.open(r/'fine-survey-coverage.png'))>0
H,W=fine.shape;xx,yy=np.meshgrid(np.arange(W)+.5,np.arange(H)+.5)
wx=(a['x']+xx/256)/2**a['z'];wy=(a['y']+yy/256)/2**a['z'];lon=wx*360-180;lat=np.degrees(np.arctan(np.sinh(np.pi*(1-2*wy))))
b=a['bounds'];eligible=(~fine)&(lon>=b[0])&(lon<=b[2])&(lat>=b[1])&(lat<=b[3])
labels,n=label(eligible);counts=np.bincount(labels.ravel());eligible&=counts[labels]>=16
# Two footprint pixels of guard, then four pixels of inward feather. No Esri
# opacity is ever written into an observed high-resolution LINZ footprint.
d=distance_transform_edt(np.pad(eligible,1))[1:-1,1:-1]
alpha=np.round(np.clip((d-2)/4,0,1)*255).astype('uint8');assert not np.any(alpha[fine])
rgba=np.full((H,W,4),255,dtype='uint8');rgba[:,:,3]=alpha
assets=repo/'public/assets';Image.fromarray(rgba).save(assets/'west-coast-mask.png',optimize=True)
def simplify(p,eps=.15):
 if len(p)<=2:return p
 v=p[-1]-p[0];t=np.clip(((p-p[0])@v)/(v@v or 1),0,1);dist=np.linalg.norm(p-(p[0]+t[:,None]*v),axis=1);k=int(dist.argmax())
 if dist[k]<=eps:return np.array([p[0],p[-1]])
 return np.concatenate((simplify(p[:k+1],eps)[:-1],simplify(p[k:],eps)))
def contours(level):
 fig,ax=plt.subplots();cs=ax.contour(np.pad(alpha,1),levels=[level]);out=[]
 for p in cs.allsegs[0]:
  p=p-.5 # padded indices -> pixel-edge coordinates
  # Split closed rings at their farthest vertex so RDP handles the ring.
  if len(p)<4:continue
  k=int(np.sum((p-p[0])**2,axis=1).argmax());q=np.concatenate((simplify(p[:k+1])[:-1],simplify(p[k:])))
  out.append(np.round(q,2).tolist())
 plt.close(fig);return out
meta=dict(version=1,z=a['z'],x=a['x'],y=a['y'],width=W,height=H,outer=contours(.5),inner=contours(254.5))
(repo/'public/west-coast-footprint.js').write_text('/* Authenticated fine-survey alpha footprints; see WEST_COAST_REGIONAL_VALIDATION.json. */\n(function(r){const data='+json.dumps(meta,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeWestCoastFootprint=data;})(globalThis);\n')
points={}
for name,lo,la in [('Cook',170.141,-43.595),('Tasman',170.157,-43.57),('Balfour gap',170.103,-43.56),('west gap',170.10,-43.58),('Fox',170.06,-43.5),('Franz Josef',170.2,-43.47)]:
 px=((lo+180)/360*2**a['z']-a['x'])*256;py=((1-math.asinh(math.tan(math.radians(la)))/math.pi)/2*2**a['z']-a['y'])*256
 points[name]=dict(longitude=lo,latitude=la,alpha=int(alpha[round(py),round(px)]),fineLINZ=bool(fine[round(py),round(px)]))
 if name=='Cook':
  radius=math.ceil(850/(40075016.686*math.cos(math.radians(la))/(2**a['z']*256)))+1
  model=alpha[int(py)-radius:int(py)+radius,int(px)-radius:int(px)+radius];assert np.all(model==0);modelUnchanged=True
report=dict(date='2026-10-18 Melbourne',method='Screened regional survey layers at z11, then unioned the actual PNG alpha coverage of intersecting high-resolution surveys at z13. Esri is eligible only where those fine surveys have no coverage, inside the reviewed alpine ROI.',coveragePixelGroundMetres=round(40075016.686*math.cos(math.radians(-43.55))/(2**a['z']*256),3),guardPixels=2,inwardFeatherPixels=4,reviewBounds=b,activeSurveys=a['active'],maskPixels=W*H,esriNonzeroPixels=int((alpha>0).sum()),LINZFinePixelsChanged=int((alpha[fine]>0).sum()),cookModel1_7kmUnchanged=modelUnchanged,points=points,maskSHA256=hashlib.sha256((assets/'west-coast-mask.png').read_bytes()).hexdigest(),outerVertices=sum(map(len,meta['outer'])),innerVertices=sum(map(len,meta['inner'])),screenedTiles=len(a['screen']),footprintTiles=len(a['tiles']),failedTiles=len(a['failed']),caveat='Footprints sampled at about 14 m ground spacing with conservative guard and inward feather; not a claim of centimetre-exact survey boundaries. No imagery is generated or upscaled. Only an opacity mask is precomputed.')
(repo/'WEST_COAST_REGIONAL_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');(repo/'WEST_COAST_SURVEY_FOOTPRINTS.json').write_text(json.dumps(a,indent=2)+'\n')
# Non-deployed QA overlay in world-aligned Mercator coordinates.
# Show georeferenced footprint at its own extent: white = preserved fine LINZ,
# cyan = replacement, grey = unreviewed/no replacement.
over=np.zeros((H,W,3),dtype='uint8');over[:]=[80,80,80];over[fine]=[235,235,235];over[alpha>0]=[40,190,210]
qa=repo/'qa/regional-2026-10-18';qa.mkdir(parents=True,exist_ok=True);out=Image.fromarray(over);dr=ImageDraw.Draw(out)
for name,p in points.items():
 lo,la=p['longitude'],p['latitude'];px=((lo+180)/360*2**a['z']-a['x'])*256;py=((1-math.asinh(math.tan(math.radians(la)))/math.pi)/2*2**a['z']-a['y'])*256;dr.ellipse((px-8,py-8,px+8,py+8),fill='red');dr.text((px+10,py),name,fill='red',stroke_width=1,stroke_fill='white')
out.thumbnail((1200,1200));out.save(qa/'regional-footprint.png');print(json.dumps(report))
