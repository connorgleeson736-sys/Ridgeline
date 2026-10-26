"""Authentic matched Cook samples; CPU reference of the native raster shader.
Uses RIDGELINE_PHOTO_CACHE or source-cache/recording-imagery; downloads only missing
source samples. Saves non-deployed QA and quantitative evidence in this repository.
"""
from pathlib import Path
from PIL import Image,ImageDraw
import numpy as np
import os,math,json,hashlib,subprocess,re,urllib.request
repo=Path(__file__).resolve().parents[2]
cache=Path(os.environ.get('RIDGELINE_PHOTO_CACHE',repo/'source-cache/recording-imagery'));cache.mkdir(parents=True,exist_ok=True)
key=os.environ.get('LINZ_API_KEY') or re.search(r"const linzApiKey='([^']+)'",(repo/'public/app.js').read_text()).group(1)
rows=[]
def source(kind):
 im=Image.new('RGB',(512,512))
 for y in range(10400,10402):
  for x in range(15934,15936):
   p=cache/f'{kind}-14-{x}-{y}.img'
   if not p.exists():
    url=f'https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/14/{x}/{y}.webp?api={key}' if kind=='linz' else f'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/14/{y}/{x}'
    with urllib.request.urlopen(url,timeout=25) as response:data=response.read()
    temp=p.with_suffix('.part');temp.write_bytes(data);temp.replace(p)
   tile=Image.open(p).convert('RGB');assert tile.size==(256,256);im.paste(tile,((x-15934)*256,(y-10400)*256))
   rows.append(dict(provider=kind,z=14,x=x,y=y,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
 lo,la=170.141,-43.595;px=((lo+180)/360*2**14-15934)*256;py=((1-math.asinh(math.tan(math.radians(la)))/math.pi)/2*2**14-10400)*256
 return np.array(im.crop((round(px)-123,round(py)-123,round(px)+123,round(py)+123))).astype(float)/255
paint=json.loads(subprocess.check_output(['node','-e',"const I=require('./public/imagery.js');console.log(JSON.stringify({linz:I.paint('linz'),esri:I.paint('satellite')}))"],cwd=repo))
old={**paint['esri'],'raster-saturation':-.18}
def grade(a,p,clip=True):
 rgb=a+(a.mean(axis=2,keepdims=True)-a)*(-p['raster-saturation']);c=p['raster-contrast'];f=1/(1-c) if c>0 else 1+c
 out=((rgb-.5)*f+.5)*(p['raster-brightness-max']-p['raster-brightness-min'])+p['raster-brightness-min'];return np.clip(out,0,1) if clip else out
linz=source('linz');esri=source('esri');before=grade(linz,old);after=grade(linz,paint['linz']);reference=grade(esri,paint['esri'])
snow=(linz.min(2)>.65)&(np.ptp(linz,axis=2)<.10);rock=(linz.mean(2)>.12)&(linz.mean(2)<.36)&(np.ptp(linz,axis=2)<.12)
rgba=np.full((*esri.shape[:2],4),255,dtype='uint8');rgba[:,:,:3]=np.round(esri*255).astype('uint8')
corrected=subprocess.run(['node','-e',"const b=require('node:fs').readFileSync(0);require('./public/imagery.js').compensateEsri(b);process.stdout.write(b)"],cwd=repo,input=rgba.tobytes(),capture_output=True,check=True).stdout
corrected=np.frombuffer(corrected,dtype='uint8').reshape(rgba.shape)[:,:,:3]/255;shown=grade(corrected,paint['linz']);difference=abs(shown-reference)*255
assert difference.max()<1.1
newRaw=grade(linz,paint['linz'],False)
report=dict(region='1.7 km Cook summit sample',sourceTiles=rows,linzPaint=paint['linz'],esriPaint=paint['esri'],snowMask='Source min RGB > 0.65 and max-min < 0.10; heuristic bright neutral pixels, not measured snow classification',rockMask='Source mean RGB 0.12–0.36 and max-min < 0.12; heuristic dark neutral pixels',snowMedianBeforeRGB255=(np.median(before[snow],axis=0)*255).tolist(),snowMedianAfterRGB255=(np.median(after[snow],axis=0)*255).tolist(),esriReferenceAtSameSnowCoordinatesRGB255=(np.median(reference[snow],axis=0)*255).tolist(),darkRockMedianChangePercent=(float(np.median(after[rock].mean(1)))/float(np.median(before[rock].mean(1)))-1)*100,LINZAnyChannelHighlightClippingPercent=float(np.mean(newRaw.max(2)>1))*100,LINZAnyChannelShadowClippingPercent=float(np.mean(newRaw.min(2)<0))*100,EsriCompensatedMaximumChannelError255=float(difference.max()),EsriCompensatedMeanChannelError255=float(difference.mean()),caveat='CPU reference with actual photo samples and shipped compensation function; no browser/GPU screenshot. Different survey dates/lighting mean this moves LINZ snow closer to Esri, not an exact radiometric or per-location match. Percentages apply only to this sample.')
qa=repo/'qa/snow-colour-2026-10-18';qa.mkdir(parents=True,exist_ok=True)
out=Image.new('RGB',(1080,410),(26,29,32));d=ImageDraw.Draw(out)
for i,(title,a) in enumerate([('Previous LINZ',before),('Adjusted LINZ',after),('Esri reference',reference)]):out.paste(Image.fromarray(np.round(a*255).astype('uint8')).resize((360,360)),(i*360,40));d.text((i*360+12,13),title,fill='white')
out.save(qa/'comparison.png');(repo/'SNOW_COLOUR_VALIDATION.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ['sourceTiles','linzPaint','esriPaint']}))
