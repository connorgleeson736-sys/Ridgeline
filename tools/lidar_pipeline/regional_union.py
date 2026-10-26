"""Exact-coordinate patch union with deterministic ownership of duplicate facets.

Only exactly identical geometry on an internal processing divider may merge.
No spatial tolerance weld, smoothing, gap filling, or source-mesh mutation.
The saved inverse map witnesses every input face in the parent triangle union.
"""
from pathlib import Path
import json
import numpy as np
from .state import atomic_npz,atomic_json,key,digest

def merge(meshes,internal_planes):
    if not meshes or len(meshes)>4:raise ValueError('A bounded parent requires one to four patches')
    vertices=[];faces=[];owners=[];voffset=0;offsets=[0]
    for owner,v,f in meshes:
        v=np.asarray(v,np.float32);f=np.asarray(f,np.uint32)
        if not len(f) or not np.isfinite(v).all() or f.max()>=len(v):raise ValueError('Invalid original patch geometry')
        vertices.append(v);faces.append(f.astype(np.uint64)+voffset);owners.append(np.full(len(f),owner,np.uint32))
        voffset+=len(v);offsets.append(offsets[-1]+len(f))
    if offsets[-1]>4_000_000:raise ValueError('Regional parent exceeds bounded four-million-face unit')
    original=np.concatenate(vertices);v,remap=np.unique(original,axis=0,return_inverse=True)
    if not np.array_equal(v[remap],original):raise AssertionError('Exact-coordinate vertex welding moved geometry')
    f=remap[np.concatenate(faces)].astype(np.uint32);owner=np.concatenate(owners)
    keys=np.sort(f,axis=1);unique,first,inverse,counts=np.unique(keys,axis=0,return_index=True,return_inverse=True,return_counts=True)
    duplicates=unique[counts>1]
    if len(duplicates):
        triangles=v[duplicates];allowed=np.zeros(len(duplicates),bool)
        for axis,value in internal_planes:allowed|=(triangles[:,:,axis]==value).all(axis=1)
        if not allowed.all():raise ValueError('Duplicate geometry away from an internal divider; overlapping inputs need investigation')
    # Inputs are supplied in ascending patch ID, so first occurrence is the one
    # deterministic owner. Preserve its winding; all input sources remain saved.
    parent=f[first]
    if not np.array_equal(np.sort(parent[inverse],axis=1),keys):raise AssertionError('Parent lost an input triangle')
    if len(np.unique(inverse))!=len(parent):raise AssertionError('Parent has geometry without an input witness')
    return dict(vertices=v,faces=parent,owner_patch=owner[first],input_face_to_parent=inverse.astype(np.uint32),
        input_face_offsets=np.asarray(offsets,np.uint32)),dict(input_triangles=len(f),parent_triangles=len(parent),
        duplicate_divider_triangles_removed=int(len(f)-len(parent)),duplicate_geometric_facets=len(duplicates),
        every_input_triangle_has_exact_parent_witness=True,every_parent_triangle_has_input_witness=True,
        vertices_moved=False,weld_tolerance_m=0,source_meshes_modified=False)

def process(state,tiles=(189,190,209,210),delta=1.35):
    tiles=sorted(set(map(int,tiles)));size=state.config['tile_size_m']
    nx=int((state.config['bounds'][2]-state.config['bounds'][0])/size)
    ny=int((state.config['bounds'][3]-state.config['bounds'][1])/size)
    if not 1<=len(tiles)<=4 or any(t<0 or t>=nx*ny for t in tiles):raise ValueError('Invalid bounded parent tile group')
    cells=[divmod(t,nx) for t in tiles];ys=sorted({y for y,x in cells});xs=sorted({x for y,x in cells})
    if len(xs)>2 or len(ys)>2 or len(cells)!=len(xs)*len(ys) or xs[-1]-xs[0]!=len(xs)-1 or ys[-1]-ys[0]!=len(ys)-1:
        raise ValueError('Parent patches must form a complete contiguous rectangle')
    label=format(delta,'.6g').replace('.','p');inputs=[];paths=[]
    for tile in tiles:
        name=f'local-surface-{tile:04d}-d{label}';task=state.task(name)
        if not task or task['state']!='durable' or not state.valid(name,task['signature']):raise ValueError('Durable source patch required: '+name)
        path=state.root/'data/local'/label/f'{tile:04d}'/'finest.npz'
        inputs.append(dict(tile=tile,task=name,signature=task['signature'],mesh_sha256=digest(path)));paths.append(path)
    name='regional-union-'+key(tiles)[:12]+'-d'+label;directory=state.root/'data/regional'/name
    bounds=[xs[0]*size,ys[0]*size,(xs[-1]+1)*size,(ys[-1]+1)*size]
    signature=key(dict(stage='exact-owned-regional-union-v1',inputs=inputs,implementation=digest(__file__)))
    with state.lock(name):
        if state.valid(name,signature):
            if state.remote:state.remote.sync_task(state,name)
            return state.task(name)['metrics']
        state.start(name,signature);directory.mkdir(parents=True,exist_ok=True)
        try:
            meshes=[]
            for tile,path in zip(tiles,paths):
                with np.load(path) as data:meshes.append((tile,data['vertices'],data['faces']))
            planes=[(0,x*size) for x in xs[1:]]+[(1,y*size) for y in ys[1:]]
            arrays,report=merge(meshes,planes);mesh=directory/'finest.npz';atomic_npz(mesh,**arrays)
            report.update(bounds=bounds,tiles=tiles,inputs=inputs,internal_planes=planes,
                vertices=len(arrays['vertices']),mesh=str(mesh.relative_to(state.root)),publishable=False,
                limits='Exact parent union only. Child ownership masks/mixed-LOD regional seams remain separate gates.')
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(name,signature,[mesh,metadata],report)
            if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Regional union awaits external checkpoint')
            return report
        except Exception as error:
            if not state.valid(name,signature):state.fail(name,error)
            raise

if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private regional union JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    report=process(state,settings.get('tiles',[189,190,209,210]),settings.get('delta',1.35));print(json.dumps(report),flush=True)
    if settings.get('budget') is not None:
        from .certified_lod import run
        label='regional-'+key(report['tiles'])[:12]+'-d'+format(settings.get('delta',1.35),'.6g').replace('.','p')
        result=run(state,root/report['mesh'],report['bounds'],float(settings['budget']),label)
        print(json.dumps(dict(regional_lod_label=label,result=result)),flush=True)
