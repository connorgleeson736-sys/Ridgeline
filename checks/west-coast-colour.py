"""Matched geographic RGB analysis, not a browser visual test.
Run with a directory containing west/coast/summit-{12,13}.webp and paint.json
(before/after actual exported imagery paint settings). No derived image is saved.
"""
import hashlib
import json
import pathlib
import sys
import numpy as np
from PIL import Image

root=pathlib.Path(sys.argv[1])
paint=json.loads((root/'paint.json').read_text())

def value(expression,zoom):
    if not isinstance(expression,list):return expression
    stops=np.array(expression[3:],dtype=float).reshape((-1,2))
    return float(np.interp(zoom,stops[:,0],stops[:,1]))

def grade(rgb,p,z):
    saturation=value(p['raster-saturation'],z)
    factor=1-1/(1.001-saturation) if saturation>0 else -saturation
    rgb=rgb+(rgb.mean(axis=2,keepdims=True)-rgb)*factor
    contrast=value(p['raster-contrast'],z)
    factor=1/(1-contrast) if contrast>0 else 1+contrast
    rgb=(rgb-.5)*factor+.5
    lo=value(p['raster-brightness-min'],z)
    hi=value(p['raster-brightness-max'],z)
    return np.clip(lo+(hi-lo)*rgb,0,1)

coords={'west':(169.98,-43.56),'summit':(170.141,-43.595),'coast':(169.8,-43.5)}
rows=[]
for name,(lon,lat) in coords.items():
    import math
    x=int((lon+180)/360*8192)
    y=int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*8192)
    a=Image.open(root/f'{name}-12.webp').convert('RGB')
    size=a.width//2
    a=np.asarray(a.crop(((x%2)*size,(y%2)*size,(x%2+1)*size,(y%2+1)*size))).astype(float)/255
    b=np.asarray(Image.open(root/f'{name}-13.webp').convert('RGB').resize((size,size),Image.Resampling.LANCZOS)).astype(float)/255
    for z in [11.5,12,13]:
        before=[grade(rgb,paint['before'],z) for rgb in [a,b]]
        after=[grade(rgb,paint['after'],z) for rgb in [a,b]]
        error=lambda pair:float(np.abs(pair[0]-pair[1]).mean())
        chroma=lambda pair:float(abs(np.ptp(pair[0],axis=2).mean()-np.ptp(pair[1],axis=2).mean()))
        rows.append({'sample':name,'cameraZoom':z,'rgbMismatchBefore':error(before),'rgbMismatchAfter':error(after),'meanChromaGapBefore':chroma(before),'meanChromaGapAfter':chroma(after)})
        assert chroma(after)<chroma(before)
        assert error(after)<error(before)
result={'method':'Matched geographic footprint from original LINZ zoom-12/13 tiles and actual MapLibre raster grading equations; no generated/edited imagery assets.','rows':rows,'sourceHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in root.glob('*.webp')},'limitations':'This softens source-colour differences; seasonal/survey boundaries and different capture geometry remain. Not a browser pixel or FPS benchmark.'}
pathlib.Path('WEST_COAST_COLOUR_VALIDATION.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'samples':len(rows),'rgbMismatchReduced':all(r['rgbMismatchAfter']<r['rgbMismatchBefore'] for r in rows),'averageChromaGapReductionPercent':100*(1-sum(r['meanChromaGapAfter'] for r in rows)/sum(r['meanChromaGapBefore'] for r in rows))}))
