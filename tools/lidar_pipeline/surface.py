"""Bounded full-XYZ original-point patches; independently durable per patch."""
from pathlib import Path
import json
import subprocess
import time
import numpy as np
from .state import atomic_bytes,atomic_json,atomic_npz,key,digest
from .reconstruct import gather,clip_core,scene,distances,boundary
from .topology import split_fans

def process(state,name,bounds,native_dir):
    points,inputs=gather(state,bounds);cfg=state.config
    if len(points)>cfg['max_points_per_job']:
        x0,y0,x1,y1=bounds
        if min(x1-x0,y1-y0)<=12.5:raise MemoryError('Minimum patch too dense; no point decimation applied')
        xm,ym=(x0+x1)/2,(y0+y1)/2
        signature=key(dict(stage='split-surface-v1',bounds=bounds,inputs=inputs,
                           point_limit=cfg['max_points_per_job'],halo_m=cfg['halo_m']))
        task='surface-'+name;directory=state.root/'data/adaptive'/name
        with state.lock(task):
            if state.valid(task,signature):
                if state.remote:state.remote.sync_task(state,task)
                return state.task(task)
            state.start(task,signature)
            try:
                children=[process(state,name+str(i),b,native_dir) for i,b in enumerate(
                    [(x0,y0,xm,ym),(xm,y0,x1,ym),(x0,ym,xm,y1),(xm,ym,x1,y1)])]
                if state.remote and any(t['state']!='durable' for t in children):
                    raise RuntimeError('Split patch waits for durable child jobs')
                manifest=directory/'children.json'
                value=dict(bounds=bounds,children=[dict(name=t['name'],signature=t['signature'],
                    artifacts=t['artifacts'],external=t.get('external')) for t in children])
                atomic_json(manifest,value)
                state.stage(task,signature,[manifest],dict(kind='branch',bounds=bounds,children=[t['name'] for t in children]))
                return state.task(task)
            except Exception as error:
                if not state.valid(task,signature):state.fail(task,error)
                raise
    binary=Path(native_dir)/'advancing-front';recipe=dict(algorithm='CGAL6.2.1-advancing-front',maximum_edge_m=2.,
        radius_ratio_bound=5.,beta=.52,halo_m=cfg['halo_m'],binary_sha256=digest(binary))
    signature=key(dict(stage='measured-surface-v1',bounds=bounds,inputs=inputs,recipe=recipe))
    task='surface-'+name;directory=state.root/'data/adaptive'/name
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)
        directory.mkdir(parents=True,exist_ok=True);state.start(task,signature);started=time.perf_counter()
        try:
            before=len(points);points=np.unique(points,axis=0)
            raw=directory/'input.f32';indices=directory/'triangles.partial.u32';log=directory/'native.log'
            atomic_bytes(raw,points.astype('<f4').tobytes())
            with log.open('w') as output:
                job=subprocess.Popen([str(binary),str(raw),str(indices),'2'],stdout=output,stderr=subprocess.STDOUT)
                try:
                    while True:
                        try:
                            code=job.wait(timeout=30)
                            if code:raise subprocess.CalledProcessError(code,'advancing-front')
                            break
                        except subprocess.TimeoutExpired:
                            elapsed=time.perf_counter()-started
                            state.event(task,state='running',native_elapsed_seconds=elapsed,
                                log_tail=log.read_text(errors='replace')[-2000:])
                            if elapsed>1200:raise TimeoutError('Bounded patch exceeded 20 minute budget')
                finally:
                    if job.poll() is None:job.kill();job.wait()
            faces=np.fromfile(indices,'<u4').reshape(-1,3)
            if not len(faces) or faces.max()>=len(points):raise ValueError('Invalid reconstructed index buffer')
            v,f=clip_core(points,faces,bounds);v,f,_,topology=split_fans(v,f)
            x0,y0,x1,y1=bounds
            core=points[(points[:,0]>=x0)&(points[:,0]<=x1)&(points[:,1]>=y0)&(points[:,1]<=y1)]
            errors=distances(scene(v,f),core);_,bad,open_edges=boundary(v,f,bounds)
            if bad:raise ValueError('Nonmanifold finest surface')
            report=dict(recipe=recipe,bounds=bounds,inputs=inputs,input_returns_with_halo=before,
                unique_points_with_halo=len(points),core_observations=len(core),vertices=len(v),triangles=len(f),
                source_distance_quantiles_m={str(q):float(np.quantile(errors,q)) for q in [.5,.95,.99,1]},
                observations_unconnected_over_025m=int((errors>.25).sum()),open_edges=open_edges,topology=topology,
                seconds=time.perf_counter()-started,publishable=False,
                pending=['adjacent and mixed-LOD seams','compression and quantization','rendered inspection'],
                limitations='Unconnected observations remain in the source arrays and original LAZ. No unsupported gap closure. Reconstruction error is separate from LOD error and survey accuracy.')
            arrays=directory/'finest.npz';atomic_npz(arrays,vertices=v,faces=f,source_points=core,source_distances=errors)
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(task,signature,[arrays,metadata,log],report)
            raw.unlink(missing_ok=True);indices.unlink(missing_ok=True)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Patch awaits external checkpoint before advancing')
            print(json.dumps(dict(task=task,state=state.task(task)['state'],triangles=len(f),seconds=report['seconds'])),flush=True)
            return state.task(task)
        except Exception as error:
            # A valid staged computation must remain staged after upload failure.
            if not state.valid(task,signature):state.fail(task,error)
            raise
