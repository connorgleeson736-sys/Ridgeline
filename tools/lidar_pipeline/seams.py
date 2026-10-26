"""Compare shared measured patch boundaries before allowing a regional release."""
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from .reconstruct import boundary
from .state import atomic_json,key,digest

def edges(path,bounds,axis,value):
    with np.load(path) as data:v,f=data['vertices'],data['faces']
    planes,_,_=boundary(v,f,bounds)
    p=next(p for p in planes if p['axis']==axis and p['value']==value)
    return p['positions'].reshape(-1,2,3)

def compare(a,b):
    def canonical(e,scale):
        q=np.rint(e*scale).astype(np.int64)
        a,b=q[:,0],q[:,1]
        swap=(a[:,0]>b[:,0])|((a[:,0]==b[:,0])&(a[:,1]>b[:,1]))|((a[:,0]==b[:,0])&(a[:,1]==b[:,1])&(a[:,2]>b[:,2]))
        q[swap]=q[swap,::-1]
        return sorted(map(tuple,q.reshape(-1,6)))
    if not len(a) or not len(b):return dict(accepted=False,reason='missing measured border')
    pa,pb=a.reshape(-1,3),b.reshape(-1,3)
    mismatch=max(float(cKDTree(pa).query(pb)[0].max()),float(cKDTree(pb).query(pa)[0].max()))
    exact=canonical(a,1e6)==canonical(b,1e6)
    quantized=canonical(a,2048)==canonical(b,2048)
    return dict(accepted=exact,edges_a=len(a),edges_b=len(b),exact_at_micrometre_keys=exact,
        same_topology_at_0488mm_grid=quantized,endpoint_hausdorff_m=mismatch,
        note='Endpoint check alone is not a continuous seam proof. A mismatch blocks release.')

def test_four(state,local_delta=None,tiles=None):
    tiles=[361,362,381,382] if tiles is None else sorted(set(map(int,tiles)))
    size=state.config['tile_size_m']
    nx=int((state.config['bounds'][2]-state.config['bounds'][0])/size)
    ny=int((state.config['bounds'][3]-state.config['bounds'][1])/size)
    if any(t<0 or t>=nx*ny for t in tiles):raise ValueError('Seam tile outside configured region')
    if local_delta is None and tiles!=[361,362,381,382]:raise ValueError('Legacy AFS seam test supports its original four trials only')
    records=[];paths={}
    for tile in tiles:
        if local_delta is not None:
            paths[tile]=state.root/'data/local'/format(local_delta,'.6g').replace('.','p')/f'{tile:04d}'/'finest.npz'
        elif tile==381:
            paths[tile]=state.root/'data/experiments/0381/afs-2/surface.npz'
        else:paths[tile]=state.root/'data/adaptive'/f'{tile:04d}'/'finest.npz'
    def bounds(tile):
        iy,ix=divmod(tile,nx);return [ix*size,iy*size,(ix+1)*size,(iy+1)*size]
    pairs=[]
    for tile in tiles:
        iy,ix=divmod(tile,nx)
        if ix+1<nx and tile+1 in paths:pairs.append((tile,tile+1,0,(ix+1)*size))
        if iy+1<ny and tile+nx in paths:pairs.append((tile,tile+nx,1,(iy+1)*size))
    if not pairs:raise ValueError('No adjacent tiles to compare')
    for left,right,axis,value in pairs:
        a=edges(paths[left],bounds(left),axis,value);b=edges(paths[right],bounds(right),axis,value)
        record=dict(tiles=[left,right],axis=axis,value=value,**compare(a,b))
        if local_delta is not None:
            with np.load(paths[left].with_name('seam-triangles.npz')) as p:ta=p['plane_'+str(1 if axis==0 else 3)]
            with np.load(paths[right].with_name('seam-triangles.npz')) as p:tb=p['plane_'+str(0 if axis==0 else 2)]
            def canonical_faces(t):
                order=np.lexsort((t[:,:,2],t[:,:,1],t[:,:,0]),axis=1)
                t=np.take_along_axis(t,order[:,:,None],axis=1).reshape(-1,9)
                return t[np.lexsort(tuple(t[:,i] for i in range(8,-1,-1)))]
            ca,cb=canonical_faces(ta),canonical_faces(tb)
            record['crossing_original_facets_a']=len(ta);record['crossing_original_facets_b']=len(tb)
            record['original_facet_sets_identical']=np.array_equal(ca,cb)
            # Original facets are a stronger geometry check than boundary-edge
            # incidence on a nonmanifold soup. Keep both results explicit.
            record['accepted']=bool(record['original_facet_sets_identical'])
            record['note']='Original crossing facet equality proves the same measured triangles are present. Nonmanifold sheet separation and coplanar ownership remain separate release gates.'
        records.append(record)
    report=dict(accepted=all(p['accepted'] for p in records),pairs=records,publishable=False)
    task='seams-four' if local_delta is None else 'seams-local-'+format(local_delta,'.6g').replace('.','p')
    if tiles!=[361,362,381,382]:task+='-'+key(tiles)[:12]
    path=state.root/'data/experiments'/(task+'.json');atomic_json(path,report)
    signature=key(dict(stage='shared-boundary-v2',implementation=digest(__file__),
        inputs={str(k):dict(mesh=digest(v),crossing_facets=digest(v.with_name('seam-triangles.npz'))
            if local_delta is not None else None) for k,v in paths.items()}))
    state.stage(task,signature,[path],report)
    print(report,flush=True);return report
