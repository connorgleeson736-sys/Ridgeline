import sys
from pathlib import Path
import numpy as np,json,rasterio,hashlib,math
from pyproj import Transformer
from rasterio.windows import Window
from PIL import Image
p=Path(sys.argv[1]);p.mkdir(parents=True,exist_ok=True);meta=json.load(open(sys.argv[2] if len(sys.argv)>2 else 'public/assets/mt-cook/region.json'));cx,cy=meta['worldCenter'];step=meta['worldStep'];circ=40075016.68557849

from urllib.request import urlopen
for name in ['BX15_5000_0609', 'BX15_5000_0610', 'BX15_5000_0709', 'BX15_5000_0710']:
 metadata=p/(name+'.json')
 if not metadata.exists():metadata.write_bytes(urlopen('https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/'+name+'.json').read())
 item=json.load(open(metadata));expected=item['assets']['visual']['file:checksum'][4:];file=p/(name+'.tiff')
 if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=expected:
  temporary=p/(name+'.download');temporary.write_bytes(urlopen('https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/'+name+'.tiff').read());assert hashlib.sha256(temporary.read_bytes()).hexdigest()==expected;temporary.replace(file)
# Cover same model edges (grid samplecenter extents), imagepixelcenters inside.
span=(meta['gridSize']-1)*step;tx=Transformer.from_crs(4326,2193,always_xy=True)
wx=np.array([cx-span/2,cx+span/2]);wy=np.array([cy-span/2,cy+span/2]);lng,lt=np.meshgrid(wx*360-180,np.degrees(np.arctan(np.sinh(np.pi*(1-2*wy)))));x,y=tx.transform(lng,lt)
# Align crop to original .3m sourcegrid (1367200,5172000).
left=1367200+math.floor((x.min()-1367200)/.3)*.3;top=5172000-math.floor((5172000-y.max())/.3)*.3;w=math.ceil((x.max()-left)/.3)+2;h=math.ceil((top-y.min())/.3)+2
native=np.zeros((4,h,w),dtype=np.uint8);info=[]
for name in ['BX15_5000_0609','BX15_5000_0610','BX15_5000_0709','BX15_5000_0710']:
 f=p/(name+'.tiff');j=json.load(open(p/(name+'.json')));actual=hashlib.sha256(f.read_bytes()).hexdigest();assert actual==j['assets']['visual']['file:checksum'][4:]
 with rasterio.open(f) as d:
  destc=int(round((d.bounds.left-left)/.3));destr=int(round((top-d.bounds.top)/.3));dc=max(0,destc);dr=max(0,destr);sc=max(0,-destc);sr=max(0,-destr);cw=min(w-dc,d.width-sc);ch=min(h-dr,d.height-sr)
  if cw>0 and ch>0:native[:,dr:dr+ch,dc:dc+cw]=d.read(window=Window(sc,sr,cw,ch))
  info.append({'tile':name,'url':'https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/'+name+'.tiff','sha256':actual,'sourcePixelMetres':list(d.res),'compression':d.tags(ns='IMAGE_STRUCTURE'),'captureDateRange':{k:j['properties'][k] for k in ['start_datetime','end_datetime']}})
print('Native assembled',w,h,flush=True)
report={'collection':'Canterbury0.3m Rural Aerial Photos (2015–2016)','nativeResolutionMetres':.3,'collectionUrl':'https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/collection.json','captureAreaCoversRegion':True,'sourceFiles':info,'outputs':[]}
for s in [6144,4096]:
 out=np.empty((s,s,3),dtype=np.uint8);missing=0;wx=cx+(((np.arange(s)+.5)/s)-.5)*span;lng=wx*360-180
 for y0 in range(0,s,128):
  rr=np.arange(y0,min(s,y0+128));wy=cy+(((rr+.5)/s)-.5)*span;lat=np.degrees(np.arctan(np.sinh(np.pi*(1-2*wy))));lg,lt=np.meshgrid(lng,lat);xx,yy=tx.transform(lg,lt);col=(xx-left)/.3-.5;row=(top-yy)/.3-.5;ic=np.floor(col).astype(int);ir=np.floor(row).astype(int);dx=col-ic;dy=row-ir
  assert ic.min()>=0 and ir.min()>=0 and ic.max()+1<w and ir.max()+1<h
  good=(native[3,ir,ic]>0)&(native[3,ir,ic+1]>0)&(native[3,ir+1,ic]>0)&(native[3,ir+1,ic+1]>0);missing+=int((~good).sum())
  a=native[:3,ir,ic].astype(float);b=native[:3,ir,ic+1].astype(float);c=native[:3,ir+1,ic].astype(float);e=native[:3,ir+1,ic+1].astype(float);vals=(a*(1-dx)*(1-dy)+b*dx*(1-dy)+c*(1-dx)*dy+e*dx*dy).transpose(1,2,0);out[y0:y0+len(rr)]=np.clip(np.rint(vals),0,255).astype(np.uint8)
 assert missing==0,missing
 f=p/f'cook-authentic-aerial-{s}.webp';Image.fromarray(out).save(f,lossless=True,method=6);report['outputs'].append({'path':str(f),'size':[s,s],'groundPixelSpacingMetres':meta['pixelSpacingMetres']*(meta['gridSize']-1)/s,'missingSamples':missing,'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()});print(report['outputs'][-1],flush=True)
(p/'cook-authentic-imagery-provenance.json').write_text(json.dumps(report,indent=2));print('DONE',flush=True)
