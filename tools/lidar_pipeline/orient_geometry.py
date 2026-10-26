"""Separate nonmanifold sheets without moving or deleting triangle geometry.

Coordinates may be duplicated and triangle winding may reverse. This is an
export topology stage, not surface reconstruction, smoothing or hole filling.
"""
from pathlib import Path
import json
import subprocess
import time
import numpy as np
import open3d as o3d
from .state import atomic_bytes,atomic_json,atomic_npz,key,digest


def ordered_corners(triangles):
    order=np.lexsort((triangles[:,:,2],triangles[:,:,1],triangles[:,:,0]),axis=1)
    return np.take_along_axis(triangles,order[:,:,None],axis=1)


def process(state,source_path,label,native_dir):
    source_path=Path(source_path);binary=Path(native_dir)/'orient-soup'
    signature=key(dict(stage='geometry-preserving-sheet-orientation-v1',input=digest(source_path),
        implementation=digest(__file__),native_sha256=digest(binary)))
    task='oriented-'+label;directory=state.root/'data/oriented'/label
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)
        state.start(task,signature);directory.mkdir(parents=True,exist_ok=True);started=time.perf_counter()
        try:
            with np.load(source_path) as d:v,f=d['vertices'],d['faces']
            rawv=directory/'input.f32';rawf=directory/'input.u32'
            outv=directory/'output.f32';outf=directory/'output.u32';log=directory/'native.log'
            atomic_bytes(rawv,v.astype('<f4').tobytes());atomic_bytes(rawf,f.astype('<u4').tobytes())
            with log.open('w') as output:
                subprocess.run([str(binary),str(rawv),str(rawf),str(outv),str(outf)],
                    stdout=output,stderr=subprocess.STDOUT,check=True,timeout=600)
            ov=np.fromfile(outv,'<f4').reshape(-1,3);of=np.fromfile(outf,'<u4').reshape(-1,3)
            if len(of)!=len(f) or not np.isfinite(ov).all() or of.max()>=len(ov):raise ValueError('Invalid separated geometry')
            for first in range(0,len(f),20000):
                if not np.array_equal(ordered_corners(v[f[first:first+20000]]),ordered_corners(ov[of[first:first+20000]])):
                    raise ValueError('Sheet separation changed triangle coordinates')
            mesh=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(ov.astype(float)),o3d.utility.Vector3iVector(of.astype(np.int32)))
            topology=dict(edge_manifold=mesh.is_edge_manifold(allow_boundary_edges=True),
                vertex_manifold=mesh.is_vertex_manifold(),orientable=mesh.is_orientable())
            if not all(topology.values()):raise ValueError('Sheet separation failed independent topology validation')
            arrays=directory/'mesh.npz';atomic_npz(arrays,vertices=ov,faces=of)
            report=dict(source_sha256=digest(source_path),triangles=len(of),original_vertices=len(v),
                separated_vertices=len(ov),duplicated_vertices=len(ov)-len(v),geometry_identical=True,
                topology=topology,seconds=time.perf_counter()-started,publishable=False,
                limitations='Combinatorial manifold sheets can still intersect geometrically. No inference of solid geology or measured outward orientation. GPU vertex packing is a separate stage.')
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(task,signature,[arrays,metadata,log],report)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Orientation awaits persistent checkpoint')
            for p in [rawv,rawf,outv,outf]:p.unlink(missing_ok=True)
            print(json.dumps(dict(task=task,**report)),flush=True);return state.task(task)
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise
