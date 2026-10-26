from pathlib import Path
import json,re,math,hashlib
from PIL import Image
import numpy as np
ROOT=Path(__file__).resolve().parents[2];atlas=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/gap-imagery-atlas.js').read_text())[1]);entries=atlas['entries']
def sample(lon,lat):
 z=atlas['z'];xx=(lon+180)/360*2**z;yy=(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z;x=int(xx);y=int(yy);e=entries.get(f'{x}/{y}')
 if not e:return 0
 return Image.open(ROOT/'public'/e['maskURL'].lstrip('/')).getpixel((int((xx-x)*256),int((yy-y)*256)))[3]
allpaths=set();maskPixels=0
for e in entries.values():
 p=ROOT/'public'/e['maskURL'].lstrip('/');a=np.array(Image.open(p));assert a.shape==(256,256,4);assert set(np.unique(a[:,:,3]))<={0,255};assert np.all(a[:,:,:3]==128);assert not e['seamOffsets'];allpaths.add(e['maskURL']);maskPixels+=int((a[:,:,3]>0).sum())
fineTiles={}
for p in (ROOT/'qa/gap-expansion/source').glob('fine-*.png'):
 parts=p.stem.split('-');key=parts[-2]+'/'+parts[-1]
 if key not in entries:continue
 a=np.array(Image.open(p).convert('RGBA'))[:,:,3]>0
 if key in fineTiles:fineTiles[key]|=a
 else:fineTiles[key]=a
fineOverlap=0
for key,a in fineTiles.items():
 e=entries[key];fineOverlap+=int((a&(np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')))[:,:,3]>0)).sum())
assert fineOverlap==0,fineOverlap
targets=json.load(open(ROOT/'qa/gap-expansion/audit-targets.geojson'))['features'];samples={}
for f in targets:
 p=f['properties'];a=sample(p['sample_lon'],p['sample_lat']);assert a==255,(p['id'],a);samples[p['id']]=a
for lon,lat in [(170.141,-43.595),(168.727,-44.385),(167.924,-44.671),(168.012,-44.594),(174.618801,-37.000206)]:assert sample(lon,lat)==0,(lon,lat)
# Verify the entire old feather mask is covered by a binary replacement, including its previous transition band.
old=json.loads(re.search(r'const data=(.*);if\(typeof module',(ROOT/'public/west-coast-footprint.js').read_text())[1]);oldalpha=np.array(Image.open(ROOT/'public/assets/west-coast-mask.png'))[:,:,3];missing=0
for yy,xx in zip(*np.where(oldalpha>0)):
 x=old['x']+xx//256;y=old['y']+yy//256;e=entries.get(f'{x}/{y}')
 if e is None:missing+=1;continue
 # Cache decoded masks across old pixels.
 if 'pixels' not in e:e['pixels']=np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')))[:,:,3]
 if e['pixels'][yy%256,xx%256]!=255:missing+=1
assert missing==0,missing
for row in atlas['coarse'].values():
 im=Image.open(ROOT/'public'/row['url'].lstrip('/'));assert im.size==(256,256);allpaths.add(row['url'])
# Drop only obsolete generated content hashes from earlier local attempts.
for p in (ROOT/'public/assets/gap-imagery-v1').glob('*.png'):
 if '/assets/gap-imagery-v1/'+p.name not in allpaths:p.unlink()
report={'hardBinaryMaskTiles':len(entries),'maskedPixelsAtZ13':maskPixels,'sampleAlpha':samples,'fineAndWaterControlsUnchanged':True,'measuredFinePixelsChanged':fineOverlap,'oldFeatherPixelsLeftUncovered':missing,'coarseOverlayTiles':len(atlas['coarse']),'sourceAssets':len(allpaths),'sourceAssetBytes':sum((ROOT/'public'/u.lstrip('/')).stat().st_size for u in allpaths),'noFeatheringOrColourOffsets':True,'protectedCookRadiusMetres':1100,'caveat':'Footprints and native z13 mask grid are approximate, not subpixel survey cutlines. Coarse imagery uses standard area downsampling, not a geographic feather band.'}
(ROOT/'GAP_MASK_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
