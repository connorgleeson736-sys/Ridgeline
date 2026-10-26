"""Deterministic full-XYZ render clusters; each finest triangle exactly once."""
from pathlib import Path
import hashlib
import numpy as np
from .state import atomic_json,atomic_npz,key,digest


def partition(vertices,faces,maximum=16384,max_extent=None):
    if type(maximum) is not int or maximum<1:raise ValueError('Positive integer cluster budget required')
    if max_extent is not None and (not np.isfinite(max_extent) or max_extent<=0):raise ValueError('Positive spatial extent required')
    v=np.asarray(vertices);f=np.asarray(faces)
    if not len(f) or f.max()>=len(v) or not np.isfinite(v).all():raise ValueError('Invalid cluster geometry')
    centers=v[f].astype(np.float64).mean(axis=1);leaves=[]
    def split(ids):
        span=float(np.ptp(v[f[ids]].reshape(-1,3),axis=0).max())
        if len(ids)<=maximum and (max_extent is None or span<=max_extent):leaves.append(ids);return
        if len(ids)==1:raise ValueError('One source triangle exceeds the render-cluster spatial bound')
        axis=int(np.argmax(np.ptp(centers[ids],axis=0)))
        order=np.lexsort((ids,centers[ids,axis]));ordered=ids[order];middle=len(ordered)//2
        split(ordered[:middle]);split(ordered[middle:])
    split(np.arange(len(f),dtype=np.uint32))
    order=np.concatenate(leaves);offsets=np.r_[0,np.cumsum([len(ids) for ids in leaves])].astype(np.uint32)
    if not np.array_equal(np.sort(order),np.arange(len(f))):raise AssertionError('Clustering lost or duplicated triangles')
    bounds=[]
    for ids in leaves:
        points=v[f[ids]].reshape(-1,3);bounds.append([points.min(0),points.max(0)])
    return order,offsets,np.asarray(bounds,dtype=np.float64)


def process(state,source_path,label,maximum=16384,max_extent=31.5,spatial_vertices=None):
    source_path=Path(source_path);directory=state.root/'data/clusters'/label;task='clusters-'+label
    spatial_hash=hashlib.sha256(np.ascontiguousarray(spatial_vertices).tobytes()).hexdigest() if spatial_vertices is not None else None
    signature=key(dict(stage='spatial-full-xyz-clusters-v2',source=digest(source_path),maximum=maximum,
        max_extent=max_extent,spatial_vertices=spatial_hash,implementation=digest(__file__)))
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)
        state.start(task,signature);directory.mkdir(parents=True,exist_ok=True)
        try:
            with np.load(source_path) as d:v,f=d['vertices'],d['faces']
            space=v if spatial_vertices is None else np.asarray(spatial_vertices)
            if len(space)!=len(v):raise ValueError('Spatial/source vertex counts differ')
            order,offsets,bounds=partition(space,f,maximum,max_extent)
            arrays=directory/'plan.npz';atomic_npz(arrays,order=order,offsets=offsets,bounds=bounds)
            report=dict(source=str(source_path.resolve().relative_to(state.root)),source_sha256=digest(source_path),
                triangles=len(f),clusters=len(offsets)-1,maximum_triangles=maximum,
                maximum_source_axis_extent_m=max_extent,
                spatial_reference='Common local ENU' if spatial_vertices is not None else 'Source localized XYZ',
                every_original_triangle_exactly_once=True,geometry_moved=False,publishable=False,
                note='3D spatial clustering only; no simplification, surface closure or new measurements')
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(task,signature,[arrays,metadata],report)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Cluster plan awaits persistent checkpoint')
            return state.task(task)
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise


def extract(state,label,cluster):
    task=state.task('clusters-'+label)
    if not task or not state.valid(task['name'],task['signature']):raise ValueError('Valid cluster plan required')
    if state.remote and task['state']!='durable':raise ValueError('Externally durable cluster plan required')
    directory=state.root/'data/clusters'/label;report=task['metrics'];source=state.root/report['source']
    if digest(source)!=report['source_sha256']:raise ValueError('Cluster source changed')
    with np.load(directory/'plan.npz') as d:order,offsets=d['order'],d['offsets']
    if not 0<=cluster<len(offsets)-1:raise ValueError('Cluster outside planned range')
    with np.load(source) as d:v,f=d['vertices'],d['faces']
    face_ids=order[offsets[cluster]:offsets[cluster+1]];selected=f[face_ids]
    used,inverse=np.unique(selected,return_inverse=True)
    output=directory/f'{cluster:04d}.npz'
    atomic_npz(output,vertices=v[used],faces=inverse.reshape(-1,3).astype(np.uint32),original_face_ids=face_ids)
    return output
