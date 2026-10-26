"""Bake same-place native NZTM pixels from durable LINZ photo windows.

No Web Mercator reprojection, resampling, AI synthesis or cliff material. UVs
retain the aerial acquisition limitations on vertical and hidden surfaces.
"""
import io
import json
from pathlib import Path
import numpy as np
from PIL import Image
from .source_photos import ANCHOR, PIXEL, pixel_window, bounds_for
from .state import digest


def compose(window, sources, maximum=2048):
    c0,r0,c1,r1=window;w,h=c1-c0,r1-r0
    if min(w,h)<1 or max(w,h)>maximum:
        raise ValueError('Native photo crop exceeds bounded texture dimensions; split geometry')
    canvas=np.zeros((h,w,4),np.uint8);coverage=np.zeros((h,w),bool);used=[]
    for source in sources:
        sc0,sr0,sc1,sr1=source['window']
        left,top=max(c0,sc0),max(r0,sr0);right,bottom=min(c1,sc1),min(r1,sr1)
        if right<=left or bottom<=top:continue
        path=Path(source['path'])
        if digest(path)!=source['sha256']:raise ValueError('Native source window SHA changed')
        with Image.open(path) as image:
            if image.mode!='RGBA' or image.size!=(sc1-sc0,sr1-sr0):
                raise ValueError('Native source window pixel grid changed')
            piece=np.asarray(image.crop((left-sc0,top-sr0,right-sc0,bottom-sr0)))
        if (piece[:,:,3]!=255).any():raise ValueError('Native photo crop contains invalid alpha')
        target=canvas[top-r0:bottom-r0,left-c0:right-c0]
        covered=coverage[top-r0:bottom-r0,left-c0:right-c0]
        if covered.any() and not np.array_equal(target[covered],piece[covered]):
            raise ValueError('Durable native photo overlap disagrees')
        target[:]=piece;covered[:]=True
        used.append(dict(task=source['task'],signature=source['signature'],sha256=source['sha256'],
                         pixel_window=source['window']))
    if not coverage.all():raise ValueError('Missing native photographic coverage; no extrapolation')
    output=io.BytesIO();Image.fromarray(canvas[:,:,:3]).save(output,format='PNG');png=output.getvalue()
    with Image.open(io.BytesIO(png)) as image:
        if not np.array_equal(np.asarray(image),canvas[:,:,:3]):raise ValueError('Native texture pixels changed')
    return png,used


def bake(state, local_xyz):
    points=np.asarray(local_xyz,dtype=np.float64)
    if points.ndim!=2 or points.shape[1]!=3 or not len(points) or not np.isfinite(points).all():
        raise ValueError('Finite local XYZ geometry required')
    required=['source-photo-catalog','source-photo-coverage']
    for name in required:
        task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):
            raise ValueError('Durable full native imagery coverage audit required')
    directory=state.root/'data/source-photos';catalog=json.loads((directory/'catalog.json').read_text())
    xy=points[:,:2]+np.asarray(state.config['origin'])[:2]
    pixels=np.c_[(xy[:,0]-ANCHOR[0])/PIXEL,(ANCHOR[1]-xy[:,1])/PIXEL]
    extent=np.array(pixel_window(state.config['bounds']))
    if (pixels.min(0)<extent[:2]-1e-7).any() or (pixels.max(0)>extent[2:]+1e-7).any():
        raise ValueError('Geometry outside audited native photo footprint')
    lo=np.maximum(np.floor(pixels.min(0)).astype(int)-2,extent[:2])
    hi=np.minimum(np.ceil(pixels.max(0)).astype(int)+2,extent[2:])
    # An exactly vertical plane may span zero pixels on one axis. Keep a small
    # real crop around it; do not synthesize a photographed vertical face.
    hi=np.maximum(hi,lo+1);window=[*lo.tolist(),*hi.tolist()];sources=[]
    for i in range(16):
        path=directory/f'{i:04d}';metadata=path/'validation.json'
        if not metadata.exists():continue
        report=json.loads(metadata.read_text());sc0,sr0,sc1,sr1=report['pixel_window']
        if sc0>=hi[0] or sr0>=hi[1] or sc1<=lo[0] or sr1<=lo[1]:continue
        name=f'source-photo-{i:04d}';task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):
            raise ValueError('Verified durable native source window required')
        sources.append(dict(task=name,signature=task['signature'],path=path/'photo.png',
                            sha256=report['png_sha256'],window=report['pixel_window']))
    png,used=compose(window,sources)
    uv=((pixels-lo)/(hi-lo)).astype('<f4')
    if uv.min()<0 or uv.max()>1:raise ValueError('Native UV encoding escaped geographic footprint')
    return uv,png,dict(kind='native',appearance_only=False,resampled=False,ai=False,
        source_crs='EPSG:2193',native_pixel_spacing_m=PIXEL,image_pixels=(hi-lo).tolist(),
        crop_pixel_window=window,crop_bounds=bounds_for(window),png_bytes=len(png),covered=True,
        input_windows=used,catalog_sha256=digest(directory/'catalog.json'),
        attribution=dict(license=catalog['license'],providers=catalog['providers']),
        geographic_uv='Exact source NZTM pixel grid; original native pixel values, no extrapolation',
        limitations='Aerial ground sampling is0.3m in plan view, not uniform on steep surface area. Hidden cliff faces remain unobserved.')
