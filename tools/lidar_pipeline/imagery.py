"""Bake only geographically covered, same-place existing source/AI imagery.

Never clamp missing geographic coverage, move colours across cliffs, introduce a
generic material, or claim top-down photographs observe unmeasured cliff faces.
"""
from pathlib import Path
import io
import json
import numpy as np
from PIL import Image
from .state import digest


def bake(geographic,metadata_path,kind='source'):
    if kind not in ['source','ai']:raise ValueError('Choose authentic source or existing same-place AI appearance')
    metadata_path=Path(metadata_path);meta=json.loads(metadata_path.read_text());base=metadata_path.parent
    cells=128;across=(meta['gridSize']-1)//cells
    if across!=16:raise ValueError('Unexpected source photo atlas geometry')
    core,gutter=(384,2) if kind=='source' else (768,4)
    folder=base/('photos' if kind=='source' else 'ai')
    longitude,latitude=np.asarray(geographic)[:,:2].T
    world=np.column_stack([(longitude+180)/360,(1-np.arcsinh(np.tan(np.deg2rad(latitude)))/np.pi)/2])
    origin=np.asarray(meta['worldCenter'])-(meta['gridSize']-1)/2*meta['worldStep']
    pixels=(world-origin)/meta['worldStep']*(core/cells)
    total=across*core
    if not np.isfinite(pixels).all() or pixels.min()<0 or pixels.max()>total:
        raise ValueError('Geometry outside authentic imagery footprint; expanded source imagery is required')
    lo=np.maximum(np.floor(pixels.min(axis=0)).astype(int)-2,0)
    hi=np.minimum(np.ceil(pixels.max(axis=0)).astype(int)+2,total)
    width,height=hi-lo
    if width<1 or height<1:raise ValueError('No photographic area for this patch')
    canvas=Image.new('RGB',(int(width),int(height)));coverage=np.zeros((height,width),bool)
    provenance=None
    if kind=='ai':provenance=json.loads((base/'ai/provenance.json').read_text())
    expected={t['id']:t['sha256'] for t in provenance['tiles']} if provenance else {}
    inputs=[]
    for y in range(int(lo[1]//core),int((hi[1]-1)//core)+1):
        for x in range(int(lo[0]//core),int((hi[0]-1)//core)+1):
            tile=y*across+x;path=folder/(str(tile)+'.webp');sha=digest(path)
            if expected and sha!=expected[tile]:raise ValueError('Source-specific AI tile does not match its provenance')
            left,top=max(int(lo[0]),x*core),max(int(lo[1]),y*core)
            right,bottom=min(int(hi[0]),(x+1)*core),min(int(hi[1]),(y+1)*core)
            with Image.open(path) as photo:
                if photo.size!=(core+gutter*2,core+gutter*2):raise ValueError('Unexpected photo tile size')
                piece=photo.convert('RGB').crop((left-x*core+gutter,top-y*core+gutter,right-x*core+gutter,bottom-y*core+gutter))
            canvas.paste(piece,(left-int(lo[0]),top-int(lo[1])))
            coverage[top-lo[1]:bottom-lo[1],left-lo[0]:right-lo[0]]=True
            inputs.append(dict(tile=tile,path=str(path.relative_to(base)),sha256=sha))
    if not coverage.all():raise ValueError('Uncovered pixels in photographic crop')
    uv=((pixels-lo)/(hi-lo)).astype('<f4')
    if uv.min()<0 or uv.max()>1:raise ValueError('UV coverage changed during encoding')
    buffer=io.BytesIO();canvas.save(buffer,format='PNG');png=buffer.getvalue()
    with Image.open(io.BytesIO(png)) as decoded:
        if decoded.convert('RGB').tobytes()!=canvas.tobytes():raise ValueError('Lossless image bake changed source pixels')
    report=dict(kind=kind,appearance_only=kind=='ai',metadata_sha256=digest(metadata_path),
        crop_pixels=[int(lo[0]),int(lo[1]),int(hi[0]),int(hi[1])],image_pixels=[int(width),int(height)],
        input_tiles=inputs,covered=True,png_bytes=len(png),
        attribution=meta['attribution'],geographic_uv='Source Web Mercator footprint; no geographic extrapolation',
        limitations='Aerial projection retains its acquisition limits on vertical/hidden surfaces. AI detail, when chosen, is visual only and not recovered photographic evidence.')
    if provenance:report['ai_provenance']=dict(network=provenance['network'],weightsSha256=provenance['weightsSha256'],
        sourceImageSha256=provenance['sourceImageSha256'],provenance_sha256=digest(base/'ai/provenance.json'))
    return uv,png,report
