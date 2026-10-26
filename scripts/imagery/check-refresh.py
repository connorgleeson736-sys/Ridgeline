"""Independent check of emitted masks against downloaded evidence."""
from pathlib import Path
import argparse,json,re,subprocess,hashlib,math
import numpy as np
from PIL import Image
from scipy.ndimage import binary_dilation
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);w=p.parse_args().work
read=lambda s:json.loads(re.search(r'const data=(.*);if\(typeof module',s)[1])
atlas=read((ROOT/'public/gap-imagery-atlas.js').read_text());old=read(subprocess.check_output(['git','show','c59d919d1577de82f0b8a03e5723e1fb5931c2ad:public/gap-imagery-atlas.js'],cwd=ROOT,text=True))
report=json.loads((ROOT/'IMAGERY_REFRESH_VALIDATION.json').read_text());assert report['sourceIDsUnchanged'];checked=0;added=0;fine_checked=0;emitted={}
for name,digest in report['sources'].items():assert hashlib.sha256((w/'source'/name).read_bytes()).hexdigest()==digest,name
for key,e in atlas['entries'].items():
 x,y=key.split('/');a=np.array(Image.open(ROOT/'public'/e['maskURL'].lstrip('/')));mask=a[:,:,3]>0;emitted[(int(x),int(y))]=mask;assert set(np.unique(a[:,:,3]))<={0,255}
 prior=old['entries'].get(key);previous=np.array(Image.open(ROOT/'public'/prior['maskURL'].lstrip('/')))[:,:,3]>0 if prior else np.zeros((256,256),bool)
 new=mask&~previous
 if new.any():
  aerial=np.array(Image.open(w/f'source/aerial-13-{x}-{y}.png').convert('RGBA'));ten=np.array(Image.open(w/f'source/new-zealand-2025-2026-10m-13-{x}-{y}.png').convert('RGBA'));exact=np.all(aerial==ten,axis=2)&(aerial[:,:,3]==255);assert not np.any(new&binary_dilation(~exact,iterations=1)),key;added+=int(new.sum())
 fine=np.zeros((256,256),bool)
 for path in (w/'source').glob(f'*-13-{x}-{y}.png'):
  # Survey sources use immutable imagery IDs; never imagery-composite aliases.
  if path.name.startswith('im_'):fine|=np.array(Image.open(path).convert('RGBA'))[:,:,3]>0;fine_checked+=1
 assert not np.any(mask&binary_dilation(fine,iterations=2)),key
 checked+=int(mask.sum())
assert added>0 and fine_checked>0
print(json.dumps({'PASS':True,'maskPixels':checked,'addedExact10mPixels':added,'fineSurveyTilesChecked':fine_checked,'validFinePixelsReplaced':0}))

# Check the shipped provider-label positions against the emitted raster itself.
geo=json.loads((ROOT/'public'/report['boundary']['url'].lstrip('/')).read_text());labels_checked=0
for f in geo['features']:
 if f['properties']['kind']!='label':continue
 lon,lat=f['geometry']['coordinates'];fx=(lon+180)/360*8192;fy=(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*8192;x,y=math.floor(fx),math.floor(fy)
 m=emitted.get((x,y));inside=m is not None and bool(m[min(255,int((fy-y)*256)),min(255,int((fx-x)*256))])
 assert inside==(f['properties']['provider']=='esri'),f
 labels_checked+=1
assert labels_checked==report['boundary']['labelPairs']*2
print(json.dumps({'providerLabelsCheckedAgainstEmittedMasks':labels_checked,'PASS':True}))
