"""Resumable native-grid LINZ photo windows over the entire 2km processing ROI.

COG windows retain exact source pixels/alpha; no WebMercator resampling or AI.
STAC/advertised object hashes are pinned, but a partial read is NOT described as
a recomputed whole-COG SHA. Every actual output is SHA-verified externally.
"""
from pathlib import Path
import io,json,math
import numpy as np
import requests
import rasterio
from rasterio.windows import Window
from PIL import Image
from .state import atomic_bytes,atomic_json,key,digest

BASE='https://nz-imagery.s3-ap-southeast-2.amazonaws.com/canterbury/canterbury_2015-2016_0.3m/rgb/2193/'
PINS={
 '0609':'74bf3f98b4d68c1ba67aee3eec398d96b8a582338e99f66d3aeb553ee9026963',
 '0610':'428f91be55a567cff18cd70779ef5f9928d0e30fb80996aa5ac2d83e7bdf56c5',
 '0709':'e2326566a84efd44af0c156d04b4c30b48d411c70f48ba590f8b20080e26dee6',
 '0710':'71f6c554a3cbd11638580447a1ca2a23d52426baf2c09f1746bb41a5b7626517'}
PIXEL=.3;ANCHOR=(1367200.,5172000.)

def pixel_window(bounds):
    left,bottom,right,top=map(float,bounds)
    if not all(map(math.isfinite,[left,bottom,right,top])) or right<=left or top<=bottom:raise ValueError('Invalid photo bounds')
    c0=math.floor((left-ANCHOR[0])/PIXEL+1e-7);c1=math.ceil((right-ANCHOR[0])/PIXEL-1e-7)
    r0=math.floor((ANCHOR[1]-top)/PIXEL+1e-7);r1=math.ceil((ANCHOR[1]-bottom)/PIXEL-1e-7)
    return c0,r0,c1,r1

def bounds_for(window):
    c0,r0,c1,r1=window
    return [ANCHOR[0]+c0*PIXEL,ANCHOR[1]-r1*PIXEL,ANCHOR[0]+c1*PIXEL,ANCHOR[1]-r0*PIXEL]

def head_identity(source):
    response=requests.head(source['url'],timeout=(20,60),allow_redirects=False);response.raise_for_status()
    if response.headers.get('x-amz-meta-multihash')!='1220'+source['whole_cog_sha256_advertised']:
        raise ValueError('Source COG advertised hash changed')
    if response.headers.get('Accept-Ranges','').lower()!='bytes':raise ValueError('COG range reads unavailable')
    etag=response.headers.get('ETag')
    if not etag or (source.get('etag') and source['etag']!=etag):raise ValueError('Source COG generation changed')
    return dict(etag=etag,bytes=int(response.headers['Content-Length']))

def catalog(state):
    directory=state.root/'data/source-photos';name='source-photo-catalog'
    signature=key(dict(stage='canterbury-native-photo-catalog-v1',pins=PINS,implementation=digest(__file__),bounds=state.config['bounds']))
    with state.lock(name):
        if state.valid(name,signature):
            if state.remote:state.remote.sync_task(state,name)
            return json.loads((directory/'catalog.json').read_text())
        state.start(name,signature);directory.mkdir(parents=True,exist_ok=True);paths=[]
        try:
            def metadata(name):
                response=requests.get(BASE+name,timeout=(20,60));response.raise_for_status()
                path=directory/name;atomic_bytes(path,response.content);paths.append(path);return response.json()
            collection=metadata('collection.json')
            if collection['license']!='CC-BY-4.0':raise ValueError('Unexpected source image licence')
            sources=[]
            for short,sha in PINS.items():
                name='BX15_5000_'+short;item=metadata(name+'.json');asset=item['assets']['visual']
                if asset['file:checksum']!='1220'+sha or asset['href']!='./'+name+'.tiff':raise ValueError('Pinned source STAC changed')
                source=dict(id=name,url=BASE+name+'.tiff',whole_cog_sha256_advertised=sha,
                    whole_cog_hash_recomputed=False,stac_sha256=digest(directory/(name+'.json')),
                    capture_start=item['properties']['start_datetime'],capture_end=item['properties']['end_datetime'])
                source.update(pixel_origin=[8000 if short.endswith('10') else 0,12000 if short.startswith('07') else 0],
                    width=8000,height=12000)
                source.update(head_identity(source));sources.append(source)
            value=dict(crs='EPSG:2193',pixel_spacing_m=PIXEL,anchor=ANCHOR,requested_bounds=state.config['bounds'],
                aligned_crop_bounds=bounds_for(pixel_window(state.config['bounds'])),sources=sources,
                license=collection['license'],providers=collection['providers'],expected_tasks=['source-photo-'+f'{i:04d}' for i in range(16)],
                limits='Partial COG range reads; source-advertised full hashes pinned, not recomputed. Output windows are independently SHA-verified.')
            path=directory/'catalog.json';atomic_json(path,value);paths.append(path)
            state.stage('source-photo-catalog',signature,paths,dict(sources=4,windows=16,native_pixel_spacing_m=PIXEL))
            if state.remote and state.task('source-photo-catalog')['state']!='durable':raise RuntimeError('Photo catalog awaits external checkpoint')
            return value
        except Exception as error:
            if not state.valid('source-photo-catalog',signature):state.fail('source-photo-catalog',error)
            raise

def compose(bounds,sources,check_remote=True):
    window=pixel_window(bounds);c0,r0,c1,r1=window;w,h=c1-c0,r1-r0
    if max(w,h)>2000:raise ValueError('Photo window exceeds bounded 2000px working unit')
    canvas=np.zeros((h,w,4),np.uint8);coverage=np.zeros((h,w),bool);inputs=[]
    for source in sources:
        if 'pixel_origin' in source:
            sc,sr=source['pixel_origin']
            if sc>=c1 or sr>=r1 or sc+source['width']<=c0 or sr+source['height']<=r0:continue
        if check_remote:head_identity(source)
        headers={'GDAL_HTTP_HEADERS':'If-Match: '+source['etag']} if check_remote else {}
        with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN='EMPTY_DIR',CPL_VSIL_CURL_ALLOWED_EXTENSIONS='.tiff',
                GDAL_HTTP_MAX_RETRY=3,GDAL_HTTP_RETRY_DELAY=1,GDAL_HTTP_TIMEOUT=60,**headers):
            with rasterio.open(source['url']) as dataset:
                if dataset.crs.to_epsg()!=2193 or dataset.count!=4 or dataset.dtypes!=('uint8',)*4:
                    raise ValueError('Unexpected native source photo CRS/bands')
                transform=dataset.transform
                if abs(transform.a-PIXEL)>1e-12 or abs(transform.e+PIXEL)>1e-12 or transform.b or transform.d:
                    raise ValueError('Unexpected native photo pixel transform')
                origin=np.array([(transform.c-ANCHOR[0])/PIXEL,(ANCHOR[1]-transform.f)/PIXEL])
                if np.abs(origin-np.rint(origin)).max()>1e-6:raise ValueError('Source photos do not share one pixel grid')
                oc,orr=np.rint(origin).astype(int);left,top=max(c0,oc),max(r0,orr)
                right,bottom=min(c1,oc+dataset.width),min(r1,orr+dataset.height)
                if right<=left or bottom<=top:continue
                pixels=dataset.read(window=Window(left-oc,top-orr,right-left,bottom-top)).transpose(1,2,0)
                target=canvas[top-r0:bottom-r0,left-c0:right-c0];covered=coverage[top-r0:bottom-r0,left-c0:right-c0]
                if covered.any() and not np.array_equal(target[covered],pixels[covered]):raise ValueError('Overlapping native photo pixels disagree')
                target[:]=pixels;covered[:]=True
                inputs.append(dict(id=source['id'],window=[int(left-oc),int(top-orr),int(right-left),int(bottom-top)],
                    source_etag=source.get('etag'),advertised_whole_sha256=source.get('whole_cog_sha256_advertised')))
        if check_remote:head_identity(source)
    if not coverage.all():raise ValueError('Native imagery has uncovered pixels; never extrapolate')
    alpha=canvas[:,:,3]
    if (alpha!=255).any():raise ValueError(f'Native imagery has {int((alpha!=255).sum())} non-opaque pixels; coverage must be resolved')
    output=io.BytesIO();Image.fromarray(canvas).save(output,format='PNG');png=output.getvalue()
    with Image.open(io.BytesIO(png)) as decoded:
        if not np.array_equal(np.asarray(decoded),canvas):raise ValueError('Saved native photo pixels changed')
    return png,dict(requested_bounds=list(bounds),bounds=bounds_for(window),pixel_window=list(window),
        crs='EPSG:2193',pixel_spacing_m=PIXEL,pixels=[w,h],alpha_min=int(alpha.min()),alpha_max=int(alpha.max()),
        covered_pixels=int(coverage.sum()),opaque_pixels=int((alpha==255).sum()),inputs=inputs,resampled=False,ai=False)

def process(state,windows=None):
    cat=catalog(state);requested=range(16) if windows is None else windows
    if any(type(i) is not int or not 0<=i<16 for i in requested):raise ValueError('Photo window outside 4x4 ROI')
    left,bottom,right,top=state.config['bounds']
    if right-left!=2000 or top-bottom!=2000:raise ValueError('This imagery recipe targets the audited 2km ROI')
    for i in requested:
        iy,ix=divmod(i,4);bounds=[left+ix*500,bottom+iy*500,left+(ix+1)*500,bottom+(iy+1)*500]
        name='source-photo-'+f'{i:04d}';directory=state.root/'data/source-photos'/f'{i:04d}'
        signature=key(dict(stage='native-photo-window-v1',catalog=key(cat),bounds=bounds,implementation=digest(__file__)))
        with state.lock(name):
            if state.valid(name,signature):
                if state.remote:state.remote.sync_task(state,name)
                continue
            state.start(name,signature);directory.mkdir(parents=True,exist_ok=True)
            try:
                png,report=compose(bounds,cat['sources']);photo=directory/'photo.png';atomic_bytes(photo,png)
                report.update(png_bytes=len(png),png_sha256=digest(photo),license=cat['license'],providers=cat['providers'])
                path=directory/'validation.json';atomic_json(path,report)
                state.stage(name,signature,[photo,path],report)
                if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Native photo window awaits checkpoint')
                print(json.dumps(dict(task=name,bytes=len(png),alpha_min=report['alpha_min'],pixels=report['pixels'])),flush=True)
            except Exception as error:
                if not state.valid(name,signature):state.fail(name,error)
                raise
    return dict(durable=[i for i in range(16) if (state.task('source-photo-'+f'{i:04d}') or {}).get('state')=='durable'],
        required=16,note='Native source windows; not yet used by existing terrain assets')

if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private photo session JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    remote=Remote(settings['base'],settings.pop('token'))
    state=State(root,json.loads((root/'config.json').read_text()),remote)
    remote.record(state.run,'configs','config',state.config)
    print(json.dumps(process(state,settings.get('windows'))),flush=True)
