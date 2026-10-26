"""Audit all native photo windows, including exact common-pixel border equality."""
import json
from pathlib import Path
from functools import lru_cache
import numpy as np
from PIL import Image
from . import source_photos
from .state import key,digest,atomic_json

def verify(state):
    entries=[];left,bottom,right,top=state.config['bounds']
    for i in range(16):
        name='source-photo-'+f'{i:04d}';task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):raise ValueError('Missing validated native photo window')
        report=task['metrics'];iy,ix=divmod(i,4)
        expected=source_photos.pixel_window([left+ix*500,bottom+iy*500,left+(ix+1)*500,bottom+(iy+1)*500])
        if list(expected)!=report['pixel_window'] or report['alpha_min']!=255:raise ValueError('Native photo window grid/alpha mismatch')
        entries.append(dict(task=name,signature=task['signature'],window=list(expected),
            path='data/source-photos/'+f'{i:04d}'+'/photo.png',sha256=report['png_sha256'],bytes=report['png_bytes']))
    expected=source_photos.pixel_window(state.config['bounds']);c0,r0,c1,r1=expected
    breaks=sorted({n for entry in entries for n in [entry['window'][1],entry['window'][3]]});area=0
    for a,b in zip(breaks,breaks[1:]):
        intervals=sorted((e['window'][0],e['window'][2]) for e in entries if e['window'][1]<=a and e['window'][3]>=b)
        end=c0
        for lo,hi in intervals:
            if lo>end:raise ValueError('Native photo union contains a gap')
            end=max(end,hi)
        if end!=c1:raise ValueError('Native photo union extent mismatch')
        area+=(b-a)*(c1-c0)
    if area!=(c1-c0)*(r1-r0):raise ValueError('Native photo union area mismatch')
    @lru_cache(maxsize=4)
    def pixels(index):
        with Image.open(state.root/entries[index]['path']) as image:return np.array(image)
    comparisons=0;common_pixels=0
    for i,first in enumerate(entries):
        a=first['window']
        for j in range(i+1,len(entries)):
            b=entries[j]['window'];lo=[max(a[0],b[0]),max(a[1],b[1])];hi=[min(a[2],b[2]),min(a[3],b[3])]
            if hi[0]<=lo[0] or hi[1]<=lo[1]:continue
            one=pixels(i)[lo[1]-a[1]:hi[1]-a[1],lo[0]-a[0]:hi[0]-a[0]]
            two=pixels(j)[lo[1]-b[1]:hi[1]-b[1],lo[0]-b[0]:hi[0]-b[0]]
            if not np.array_equal(one,two):raise ValueError('Shared native source pixels differ across checkpoints')
            comparisons+=1;common_pixels+=(hi[0]-lo[0])*(hi[1]-lo[1])
    return dict(complete=True,windows=entries,unique_opaque_pixels=area,native_pixel_spacing_m=.3,
        common_pixel_comparisons=comparisons,common_pixels_checked=common_pixels,all_common_pixels_identical=True,
        requested_bounds=state.config['bounds'],aligned_crop_bounds=source_photos.bounds_for(expected),
        total_png_bytes=sum(e['bytes'] for e in entries),resampled=False,ai=False,
        integrated_in_pilot_glbs=False,limits='Native 2km source imagery preparation only; pilot GLBs retain the prior same-place source photographic crops')

def process(state):
    report=verify(state);name='source-photo-coverage'
    signature=key(dict(stage='native-photo-coverage-v1',entries=report['windows'],implementation=digest(__file__)))
    with state.lock(name):
        if not state.valid(name,signature):
            state.start(name,signature);path=state.root/'data/source-photos/coverage.json';atomic_json(path,report)
            state.stage(name,signature,[path],report)
        elif state.remote:state.remote.sync_task(state,name)
        if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Photo coverage audit awaits checkpoint')
    return report

if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private coverage session JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    result=process(state);print(json.dumps({k:v for k,v in result.items() if k!='windows'}),flush=True)
