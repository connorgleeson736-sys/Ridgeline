"""Independently checkpoint exact crossing-facet comparisons for r2 neighbours.

This proves agreement of the measured triangles across a processing divider,
not manifoldness, watertight geology or mixed-LOD renderer transitions.
"""
import json
from pathlib import Path
import numpy as np
from .state import atomic_json, digest, key


def canonical(triangles):
    t=np.asarray(triangles)
    if t.ndim!=3 or t.shape[1:]!=(3,3) or not np.isfinite(t).all():
        raise ValueError('Finite original XYZ triangles required')
    order=np.lexsort((t[:,:,2],t[:,:,1],t[:,:,0]),axis=1)
    rows=np.take_along_axis(t,order[:,:,None],axis=1).reshape(-1,9)
    return rows[np.lexsort(tuple(rows[:,i] for i in range(8,-1,-1)))]


def pairs(tiles,nx):
    tiles=set(tiles);result=[]
    for tile in sorted(tiles):
        if tile%nx+1<nx and tile+1 in tiles:result.append((tile,tile+1,0))
        if tile+nx in tiles:result.append((tile,tile+nx,1))
    return result


def process(state,tiles):
    size=state.config['tile_size_m'];bounds=state.config['bounds']
    nx=int((bounds[2]-bounds[0])/size);ny=int((bounds[3]-bounds[1])/size)
    if (not isinstance(tiles,list) or len(tiles)<2 or len(set(tiles))!=len(tiles)
        or any(type(t) is not int or not 0<=t<nx*ny for t in tiles)):
        raise ValueError('Explicit unique adjacent processing patches required')
    jobs=pairs(tiles,nx)
    if not jobs:raise ValueError('No adjacent pairs in requested scope')
    inputs={}
    for tile in sorted(tiles):
        name=f'local-surface-{tile:04d}-d1p35-r2';task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):
            raise ValueError('Verified durable r2 surface required: '+name)
        inputs[tile]=dict(task=name,signature=task['signature'],
            facets=state.root/'data/local/1p35-r2'/f'{tile:04d}'/'seam-triangles.npz',
            native_sha256=task['metrics']['recipe']['native_sha256'])
    records=[];refs=[]
    for left,right,axis in jobs:
        a,b=inputs[left],inputs[right]
        if a['native_sha256']!=b['native_sha256']:raise ValueError('Adjacent reconstruction generations differ')
        name=f'seam-r2-{left:04d}-{right:04d}'
        signature=key(dict(stage='exact-original-facet-seam-v1',implementation=digest(__file__),axis=axis,
            inputs=[dict(task=i['task'],signature=i['signature'],facet_sha256=digest(i['facets'])) for i in [a,b]]))
        path=state.root/'data/regional/seams-r2'/(name+'.json')
        with state.lock(name):
            if not state.valid(name,signature):
                state.start(name,signature)
                with np.load(a['facets']) as data:ta=canonical(data['plane_'+str(1 if axis==0 else 3)])
                with np.load(b['facets']) as data:tb=canonical(data['plane_'+str(0 if axis==0 else 2)])
                report=dict(tiles=[left,right],axis=axis,original_facets_a=len(ta),original_facets_b=len(tb),
                    accepted=bool(np.array_equal(ta,tb)),no_coordinate_tolerance=True,
                    no_missing_crossing_facets=bool(np.array_equal(ta,tb)),
                    measured_crossings_present=bool(len(ta) and len(tb)),publishable=False,
                    limits='Exact original triangle agreement only. Coplanar ownership, manifold sheets, source completeness and mixed-LOD transitions remain independent gates.')
                atomic_json(path,report);state.stage(name,signature,[path],report)
            elif state.remote:state.remote.sync_task(state,name)
            if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Seam result awaits external checkpoint')
            records.append(state.task(name)['metrics']);refs.append(dict(task=name,signature=signature))
        print(json.dumps(dict(task=name,accepted=records[-1]['accepted'],facets=records[-1]['original_facets_a'])),flush=True)
    report=dict(tiles=sorted(tiles),requested_scope_patches=len(tiles),required_region_patches=nx*ny,
        tested_pairs=len(records),accepted=all(r['accepted'] for r in records),
        failed_pairs=[r['tiles'] for r in records if not r['accepted']],pairs=records,publishable=False,
        note='Selected reconstructed scope only. This does not validate unprocessed regional neighbours or rendered LOD seams.')
    name='seams-r2-scope-'+key(sorted(tiles))[:16];signature=key(dict(inputs=refs,report=report))
    path=state.root/'data/regional/seams-r2'/(name+'.json')
    with state.lock(name):
        if not state.valid(name,signature):
            state.start(name,signature);atomic_json(path,report);state.stage(name,signature,[path],report)
        elif state.remote:state.remote.sync_task(state,name)
        if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Seam scope awaits external checkpoint')
    return report


if __name__=='__main__':
    from .remote import Remote
    from .state import State
    print('Ready for private regional seam JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    print(json.dumps(process(state,settings['tiles'])),flush=True)
