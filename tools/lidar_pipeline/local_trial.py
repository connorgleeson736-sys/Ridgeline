"""Evaluate bounded, local Voronoi-ball filtering before replacing AFS tiles.

Independent implementation from published mathematical descriptions. This is
an experiment; local selection may be nonmanifold and must pass geometry-only
sheet separation and all independent gates before renderer integration.
"""
from pathlib import Path
import json
import subprocess
import time
import re
import numpy as np
from .reconstruct import gather,clip_core,scene,distances,boundary
from .state import atomic_bytes,atomic_json,atomic_npz,key,digest

def process(state,tile,native_dir,delta=1.35,revision=None):
    if revision is not None and not re.fullmatch('[a-z0-9]{1,12}',revision):raise ValueError('Safe algorithm revision required')
    size=state.config['tile_size_m'];nx=int((state.config['bounds'][2]-state.config['bounds'][0])/size)
    iy,ix=divmod(tile,nx);bounds=[ix*size,iy*size,(ix+1)*size,(iy+1)*size]
    points,inputs=gather(state,bounds)
    if len(points)>state.config['max_points_per_job']:raise MemoryError('Local trial exceeds bounded point count')
    points=np.unique(points,axis=0);binary=Path(native_dir)/'local-ballmerge'
    label=format(delta,'.6g').replace('.','p')+('-'+revision if revision else '');name=f'local-surface-{tile:04d}-d{label}'
    recipe=dict(algorithm='local-voronoi-intersection-filter-v1',delta=delta,maximum_edge_m=2.,
        halo_m=state.config['halo_m'],native_sha256=digest(binary),original_vertices=True,
        python_implementation=digest(__file__),clipping_implementation=digest(Path(__file__).with_name('reconstruct.py')),
        planar_case='Intrinsic CGAL Delaunay, original XYZ, exact dimension2 only, same maximum edge',revision=revision)
    signature=key(dict(recipe=recipe,bounds=bounds,inputs=inputs,validation_schema=2))
    directory=state.root/'data/local'/label/f'{tile:04d}'
    with state.lock(name):
        if state.valid(name,signature):
            if state.remote:state.remote.sync_task(state,name)
            return state.task(name)
        state.start(name,signature);directory.mkdir(parents=True,exist_ok=True);started=time.perf_counter()
        try:
            raw=directory/'input.f32';indices=directory/'indices.partial.u32';log=directory/'native.log'
            atomic_bytes(raw,points.astype('<f4').tobytes())
            with log.open('w') as output:
                subprocess.run([str(binary),str(raw),str(indices),'2',str(delta)],stdout=output,stderr=subprocess.STDOUT,check=True,timeout=600)
            dimension=re.search(r'Intrinsic dimension: ([23])\b',log.read_text())
            if not dimension:raise ValueError('Native intrinsic dimension was not audited')
            f=np.fromfile(indices,'<u4').reshape(-1,3)
            if not len(f) or f.max()>=len(points):raise ValueError('Invalid local triangulation')
            # Original facets intersecting each divider let seam validation
            # distinguish missing geometry from an existing nonmanifold edge
            # or a measured face lying exactly on the divider.
            triangles=points[f];crossings={}
            for number,(axis,value) in enumerate([(0,bounds[0]),(0,bounds[2]),(1,bounds[1]),(1,bounds[3])]):
                other=1-axis;lo=bounds[other];hi=bounds[other+2]
                mask=((triangles[:,:,axis].min(axis=1)<=value)&(triangles[:,:,axis].max(axis=1)>=value)
                    &(triangles[:,:,other].max(axis=1)>=lo)&(triangles[:,:,other].min(axis=1)<=hi))
                crossings['plane_'+str(number)]=triangles[mask]
            provenance=directory/'seam-triangles.npz';atomic_npz(provenance,**crossings)
            v,f=clip_core(points,f,bounds)
            x0,y0,x1,y1=bounds
            core=points[(points[:,0]>=x0)&(points[:,0]<=x1)&(points[:,1]>=y0)&(points[:,1]<=y1)]
            e=distances(scene(v,f),core);_,bad,open_edges=boundary(v,f,bounds)
            arrays=directory/'finest.npz';atomic_npz(arrays,vertices=v,faces=f,source_points=core,source_distances=e)
            report=dict(recipe=recipe,bounds=bounds,native_dimension=int(dimension.group(1)),vertices=len(v),triangles=len(f),core_points=len(core),
                source_distance_quantiles_m={str(q):float(np.quantile(e,q)) for q in [.5,.95,.99,1]},
                nonmanifold_edges=bad,open_edges=open_edges,seconds=time.perf_counter()-started,publishable=False,
                pending=['shared boundary consistency','geometry-preserving orientable sheet separation','independent LOD verification','compression and browser validation'])
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(name,signature,[arrays,metadata,log,provenance],report)
            raw.unlink(missing_ok=True);indices.unlink(missing_ok=True)
            if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Local reconstruction awaits persistent checkpoint')
            print(json.dumps(dict(task=name,state=state.task(name)['state'],report=report)),flush=True)
            return state.task(name)
        except Exception as error:
            if not state.valid(name,signature):state.fail(name,error)
            raise
