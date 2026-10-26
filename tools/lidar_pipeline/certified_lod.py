"""Local QEM feedback for original-point triangle unions, with continuous gates.

Internal sheet intersections need not be manifold for distance certification.
Preserve the entire geographic divider facet set, rather than freezing every
internal sheet boundary. Geometry-preserving orientation/export is a separate
stage. Neither QEM's own error estimate nor sample distances accept a LOD.
"""
from pathlib import Path
import json
import subprocess
import time
import numpy as np
from . import error_bounds
from .reconstruct import scene,distances
from .state import atomic_bytes,atomic_json,atomic_npz,digest,key


def face_keys(faces):
    sorted_faces=np.ascontiguousarray(np.sort(faces,axis=1),dtype=np.uint32)
    return sorted_faces.view(np.dtype((np.void,12))).reshape(-1)


def divider_faces(v,f,bounds):
    protected=np.zeros(len(f),bool)
    for axis,value in [(0,bounds[0]),(0,bounds[2]),(1,bounds[1]),(1,bounds[3])]:
        protected|=(np.abs(v[f,axis]-value)<1e-4).any(axis=1)
    return protected


def run(state,source_path,bounds,budget,label,attempts=16):
    source_path=Path(source_path)
    with np.load(source_path) as data:
        v=np.asarray(data['vertices'],np.float32);f=np.asarray(data['faces'],np.uint32)
    if not np.isfinite(budget) or budget<=1e-5:raise ValueError('Invalid geometric error budget')
    if not len(f) or not np.isfinite(v).all() or f.max()>=len(v):raise ValueError('Invalid source mesh')
    root=Path(__file__).resolve().parents[2];script=root/'tools/lidar-simplify.mjs'
    signature=key(dict(stage='certified-triangle-union-v1',source=digest(source_path),bounds=bounds,
        budget=budget,attempts=attempts,implementation=digest(__file__),certificate=digest(error_bounds.__file__),
        proposer=digest(script),wasm=digest(root/'tools/meshopt_simplifier.mjs')))
    task='certified-lod-'+label+'-'+format(budget,'.6g').replace('.','p')
    directory=state.root/'data/certified'/task;protected=divider_faces(v,f,bounds)
    with state.lock(task):
        if state.valid(task,signature):
            if state.remote:state.remote.sync_task(state,task)
            return state.task(task)['metrics']
        state.start(task,signature);directory.mkdir(parents=True,exist_ok=True)
        started=time.perf_counter();history=[];first_attempt=0;locks=np.zeros(len(v),np.uint8)
        locks[np.unique(f[protected])]=1;protected_keys=face_keys(f[protected])
        atomic_bytes(directory/'vertices.f32',v.astype('<f4').tobytes())
        atomic_bytes(directory/'indices.u32',f.astype('<u4').tobytes())
        for step in range(attempts):
            step_task=task+'-step-'+str(step)
            if not state.valid(step_task,key(dict(parent=signature,step=step))):break
            if state.remote:state.remote.sync_task(state,step_task)
            step_dir=directory/'steps'/str(step)
            with np.load(step_dir/'locks.npz') as saved:locks=saved['locks']
            progress=json.loads((step_dir/'resume.json').read_text())
            history=progress['history'];first_attempt=progress['next_attempt']
        atomic_json(directory/'progress.json',history)
        accepted=False;candidate=f.copy();proof={}
        try:
            for attempt in range(first_attempt,attempts):
                atomic_bytes(directory/'locks.u8',locks.tobytes())
                options=dict(vertices=str(directory/'vertices.f32'),indices=str(directory/'indices.u32'),
                    locks=str(directory/'locks.u8'),errors=[budget*.7],outputPrefix=str(directory/'candidate'),mode='permissive')
                atomic_json(directory/'options.json',options)
                completed=subprocess.run(['node',str(script),str(directory/'options.json')],capture_output=True,text=True,check=True,timeout=120)
                result=json.loads(completed.stdout)['results'][0]
                candidate=np.fromfile(result['path'],'<u4').reshape(-1,3)
                if not len(candidate) or candidate.max()>=len(v):raise ValueError('Invalid proposal')
                missing=~np.isin(protected_keys,face_keys(candidate))
                bad=np.unique(f[protected][missing])
                vertex_e=distances(scene(v,candidate),v)
                bad=np.union1d(bad,np.flatnonzero(vertex_e+1e-5>budget))
                proof={};record=dict(attempt=attempt,triangles=len(candidate),locked=int(locks.sum()),
                    missing_divider_faces=int(missing.sum()),vertex_max_m=float(vertex_e.max(initial=0)),
                    proposal_ms=result['milliseconds'],vertex_gate_failed=len(bad)>0)
                if not len(bad):
                    forward=error_bounds.collect(v,f,v,candidate,budget)
                    reverse=error_bounds.collect(v,candidate,v,f,budget)
                    accepted=forward['certified'] and reverse['certified']
                    proof=dict(certified=bool(accepted),upper_bound_m=max(forward['upper_bound_m'],reverse['upper_bound_m']),
                        forward={k:x for k,x in forward.items() if k!='unresolved_reference_faces'},
                        reverse={k:x for k,x in reverse.items() if k!='unresolved_reference_faces'})
                    if not accepted:
                        a=forward['unresolved_reference_faces'];b=reverse['unresolved_reference_faces']
                        bad=np.unique(np.r_[f[a].ravel() if a else np.array([],np.uint32),
                                            candidate[b].ravel() if b else np.array([],np.uint32)])
                    record['continuous_bound']=proof
                history.append(record);atomic_json(directory/'progress.json',history)
                state.event(task,state='running',**record)
                print(json.dumps(dict(task=task,**record)),flush=True)
                if accepted:break
                before=int(locks.sum())
                if len(bad):locks[np.unique(f[np.isin(f,bad).any(axis=1)])]=1
                if locks.sum()==before:break
                step_dir=directory/'steps'/str(attempt);step_dir.mkdir(parents=True,exist_ok=True)
                atomic_npz(step_dir/'locks.npz',locks=locks)
                atomic_json(step_dir/'resume.json',dict(history=history,next_attempt=attempt+1,parent_signature=signature))
                step_task=task+'-step-'+str(attempt)
                state.stage(step_task,key(dict(parent=signature,step=attempt)),
                    [step_dir/'locks.npz',step_dir/'resume.json'],dict(next_attempt=attempt+1,locked=int(locks.sum())))
                if state.remote and state.task(step_task)['state']!='durable':raise RuntimeError('Refinement awaits persistent checkpoint')
            if not accepted:
                candidate=f.copy();proof=dict(certified=True,upper_bound_m=0.,method='Identical finest fallback')
            used,remap=np.unique(candidate,return_inverse=True)
            arrays=directory/'mesh.npz';atomic_npz(arrays,vertices=v[used],faces=remap.reshape(-1,3).astype(np.uint32))
            report=dict(candidate_accepted=bool(accepted),budget_m=budget,continuous_bound=proof,
                reference_triangles=len(f),triangles=len(candidate),vertices=len(used),
                reduction_fraction=1-len(candidate)/len(f),exact_divider_facets=True,divider_facets=int(protected.sum()),
                history=history,total_seconds=time.perf_counter()-started,publishable=False,
                pending=['geometry-preserving sheet orientation and owned coplanar facets','compressed hierarchy','renderer and device benchmarks'],
                accuracy_note='Error relative to finest reconstructed triangle union; not survey accuracy or point-cloud completeness')
            metadata=directory/'validation.json';atomic_json(metadata,report)
            state.stage(task,signature,[arrays,metadata,directory/'progress.json'],report)
            if state.remote and state.task(task)['state']!='durable':raise RuntimeError('Final geometry awaits persistent checkpoint')
            return report
        except Exception as error:
            if not state.valid(task,signature):state.fail(task,error)
            raise
