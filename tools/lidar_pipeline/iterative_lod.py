"""Fast QEM proposals with LOCAL feedback and independent continuous acceptance."""
from pathlib import Path
import json
import subprocess
import time
import numpy as np
import open3d as o3d
from .state import atomic_bytes,atomic_json,atomic_npz,digest,key
from .reconstruct import scene,distances
from .validate_mesh import open_edge_coordinates,validate
from .topology import split_fans

def run(state,source_path,budget,label,attempts=18):
    source_path=Path(source_path)
    with np.load(source_path) as data:v,f=data['vertices'],data['faces']
    v,f,_,_=split_fans(v,f)
    root=Path(__file__).resolve().parents[2];script=root/'tools/lidar-simplify.mjs'
    signature=key(dict(stage='feedback-lod-v1',input=digest(source_path),budget=budget,
                       script=digest(script),implementation=digest(__file__),wasm=digest(root/'tools/meshopt_simplifier.mjs')))
    task='feedback-lod-'+label+'-'+format(budget,'.6g').replace('.','p')
    with state.lock(task):
        if state.valid(task,signature):return state.task(task)['metrics']
        state.start(task,signature);directory=state.root/'data/feedback'/task;directory.mkdir(parents=True,exist_ok=True)
        started=time.perf_counter();history=[];locks=np.zeros(len(v),np.uint8)
        _,inverse,counts=np.unique(v,axis=0,return_inverse=True,return_counts=True)
        locks[counts[inverse]>1]=1
        atomic_bytes(directory/'vertices.f32',v.astype('<f4').tobytes())
        atomic_bytes(directory/'indices.u32',f.astype('<u4').tobytes())
        edges=np.sort(np.concatenate([f[:,[0,1]],f[:,[1,2]],f[:,[2,0]]]),axis=1)
        unique,counts=np.unique(edges,axis=0,return_counts=True);locks[np.unique(unique[counts==1])]=1
        first_attempt=0
        # Each completed refinement step is independently recoverable. A crash
        # repeats at most the in-flight proposal, not all previous refinements.
        for step in range(attempts):
            step_task=task+'-step-'+str(step)
            step_signature=key(dict(parent=signature,step=step))
            if not state.valid(step_task,step_signature):break
            if state.remote:state.remote.sync_task(state,step_task)
            step_dir=directory/'steps'/str(step)
            with np.load(step_dir/'locks.npz') as saved:locks=saved['locks']
            progress=json.loads((step_dir/'resume.json').read_text())
            history=progress['history'];first_attempt=progress['next_attempt']
        accepted=False
        atomic_json(directory/'progress.json',history)
        try:
            for attempt in range(first_attempt,attempts):
                atomic_bytes(directory/'locks.u8',locks.tobytes())
                options=dict(vertices=str(directory/'vertices.f32'),indices=str(directory/'indices.u32'),
                    locks=str(directory/'locks.u8'),errors=[budget*.7],outputPrefix=str(directory/'candidate'))
                atomic_json(directory/'options.json',options)
                completed=subprocess.run(['node',str(script),str(directory/'options.json')],capture_output=True,text=True,check=True,timeout=120)
                result=json.loads(completed.stdout)['results'][0];candidate=np.fromfile(result['path'],'<u4').reshape(-1,3)
                mesh=o3d.geometry.TriangleMesh(o3d.utility.Vector3dVector(v.astype(float)),
                                               o3d.utility.Vector3iVector(candidate.astype(np.int32)))
                failures=np.asarray(mesh.get_non_manifold_vertices(),np.int64)
                bad_edges=np.asarray(mesh.get_non_manifold_edges(allow_boundary_edges=True),np.int64).reshape(-1)
                e=distances(scene(v,candidate),v);bad=np.unique(np.r_[failures,bad_edges,np.flatnonzero(e>budget*.65)])
                border_equal=np.array_equal(open_edge_coordinates(v,f),open_edge_coordinates(v,candidate))
                record=dict(attempt=attempt,triangles=len(candidate),locked=int(locks.sum()),bad_vertices=len(bad),
                    vertex_max_m=float(e.max(initial=0)),borders_equal=border_equal,proposal_ms=result['milliseconds'])
                accepted=False
                if not len(bad) and border_equal:
                    report=validate(v,f,v,candidate,budget);record['validation']=report
                    if report['accepted']:accepted=True
                    else:
                        bound=report['continuous_bound']
                        if 'forward' in bound:
                            ids=bound['forward'].get('unresolved_reference_faces',[])
                            if ids:bad=np.unique(np.r_[bad,f[ids].ravel()])
                        if 'reverse' in bound:
                            ids=bound['reverse'].get('unresolved_reference_faces',[])
                            if ids:bad=np.unique(np.r_[bad,candidate[ids].ravel()])
                history.append(record);atomic_json(directory/'progress.json',history)
                state.event(task,state='running',**record)
                if accepted:break
                if not len(bad):
                    if not border_equal:bad=np.unique(candidate[np.isin(candidate,np.flatnonzero(locks)).any(axis=1)])
                    else:break
                affected=np.isin(f,bad).any(axis=1);locks[np.unique(f[affected])]=1
                if attempt and locks.sum()==history[-1]['locked']:break
                step_dir=directory/'steps'/str(attempt);step_dir.mkdir(parents=True,exist_ok=True)
                atomic_npz(step_dir/'locks.npz',locks=locks)
                atomic_json(step_dir/'resume.json',dict(history=history,next_attempt=attempt+1,parent_signature=signature))
                step_task=task+'-step-'+str(attempt)
                state.stage(step_task,key(dict(parent=signature,step=attempt)),
                    [step_dir/'locks.npz',step_dir/'resume.json'],dict(next_attempt=attempt+1,locked=int(locks.sum())))
                if state.remote and state.task(step_task)['state']!='durable':
                    raise RuntimeError('Refinement paused until the current step is externally durable')
            if not accepted:
                candidate=f.copy();report=validate(v,f,v,f,budget)
                report.update(fallback='finest',candidate_accepted=False)
            else:report['candidate_accepted']=True
            used,remap=np.unique(candidate,return_inverse=True)
            arrays=directory/'mesh.npz';atomic_npz(arrays,vertices=v[used],faces=remap.reshape(-1,3).astype(np.uint32))
            # The final validation is also referenced inside history. Detach
            # the outer report before attaching history to avoid a JSON cycle.
            report=dict(report)
            report.update(attempts=history,total_seconds=time.perf_counter()-started,publishable=False)
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(task,signature,[arrays,metadata,directory/'progress.json'],report)
            print(json.dumps(dict(task=task,candidate_accepted=report['candidate_accepted'],triangles=len(candidate),seconds=report['total_seconds'])),flush=True)
            return report
        except Exception as error:state.fail(task,error);raise
