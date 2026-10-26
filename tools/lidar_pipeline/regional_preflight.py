"""Count exact per-job halo records before scheduling regional native work.

Each source's compact counts are independently durable and resumable. No point
sampling/downsampling; multiplicity and inclusive job boundaries match gather().
"""
from pathlib import Path
import json
import numpy as np
from .partition import DTYPE
from .state import key,digest,atomic_npz,atomic_json

def counts(points,nx,ny,size,halo):
    p=np.asarray(points);xy=p[:,:2]
    if not np.isfinite(xy).all() or (xy<0).any() or (xy>np.array([nx*size,ny*size])).any():raise ValueError('Invalid localized source domain')
    base=np.clip(np.floor(xy/size).astype(np.int64),0,np.array([nx-1,ny-1]));result=np.zeros(nx*ny,np.uint64)
    if not 0<=halo<size/2:raise ValueError('Count recipe requires halo smaller than half a processing cell')
    for dy in [-1,0,1]:
        iy=base[:,1]+dy
        for dx in [-1,0,1]:
            ix=base[:,0]+dx
            valid=(ix>=0)&(ix<nx)&(iy>=0)&(iy<ny)&(xy[:,0]>=ix*size-halo)&(xy[:,0]<=(ix+1)*size+halo)&(xy[:,1]>=iy*size-halo)&(xy[:,1]<=(iy+1)*size+halo)
            result+=np.bincount((iy[valid]*nx+ix[valid]),minlength=nx*ny).astype(np.uint64)
    return result

def process(state):
    catalog=state.root/'data/source-audit.json';audit=state.task('source-audit')
    if not audit or audit['state']!='durable' or not state.valid('source-audit',audit['signature']):raise ValueError('Durable original-source audit required')
    sources=json.loads(catalog.read_text())['sources'];size=state.config['tile_size_m'];halo=state.config['halo_m']
    bounds=state.config['bounds'];nx=int((bounds[2]-bounds[0])/size);ny=int((bounds[3]-bounds[1])/size)
    inputs=[];totals=np.zeros((2,nx*ny),np.uint64);selected=0
    directory=state.root/'data/regional/preflight-r2';directory.mkdir(parents=True,exist_ok=True)
    for source in sources:
        stem=Path(source['source']['name']).stem;name='preflight-r2-'+stem;parent=state.task('partition-'+stem)
        if not parent or parent['state']!='durable' or not state.valid(parent['name'],parent['signature']):raise ValueError('Durable verified partition required: '+stem)
        shard=state.root/'data/shards'/stem;inventory=json.loads((shard/'inventory.json').read_text())
        if inventory['source_sha256']!=source['crop_sha256']:raise ValueError('Partition/source generation mismatch')
        signature=key(dict(stage='regional-halo-preflight-v1',partition=parent['signature'],source=source['crop_sha256'],
            config=state.config,implementation=digest(__file__)))
        path=directory/(stem+'.npz')
        with state.lock(name):
            if state.valid(name,signature):
                if state.remote:state.remote.sync_task(state,name)
            else:
                state.start(name,signature)
                try:
                    values=np.zeros((2,nx*ny),np.uint64);seen=0
                    for label,amount in sorted(inventory['counts'].items(),key=lambda p:int(p[0])):
                        data=np.fromfile(shard/(f'{int(label):04d}'+'.bin'),DTYPE)
                        if len(data)!=amount:raise ValueError('Incomplete source shard')
                        p=data['position'];values[0]+=counts(p,nx,ny,size,0);values[1]+=counts(p,nx,ny,size,halo);seen+=len(p)
                    if seen!=inventory['selected']:raise ValueError('Preflight did not count every selected source record')
                    atomic_npz(path,counts=values,selected_records=np.array(seen,np.uint64))
                    state.stage(name,signature,[path],dict(selected_records=seen,maximum_source_halo_records=int(values[1].max())))
                except Exception as error:
                    if not state.valid(name,signature):state.fail(name,error)
                    raise
            if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Per-source preflight awaits checkpoint')
            with np.load(path) as data:totals+=data['counts'];selected+=int(data['selected_records'])
        inputs.append(dict(task=name,signature=signature));print(json.dumps(dict(source=stem,selected_records=selected,completed_sources=len(inputs),required_sources=len(sources))),flush=True)
    name='regional-preflight-r2';signature=key(dict(stage='summed-regional-preflight-v1',inputs=inputs,implementation=digest(__file__)))
    report=dict(selected_records=selected,processing_patches=nx*ny,halo_m=halo,
        maximum_core_inclusive_records=int(totals[0].max()),maximum_halo_records=int(totals[1].max()),
        max_records_per_native_job=state.config['max_points_per_job'],
        all_native_jobs_within_record_cap=bool((totals[1]<=state.config['max_points_per_job']).all()),
        dense_patch_ids=np.flatnonzero(totals[1]>state.config['max_points_per_job']).tolist(),
        counts_before_duplicate_xyz_removal=True,source_records_sampled=False,publishable=False,
        note='Capacity/preparation audit only. Geometry, seams and regional publication remain independent gates.')
    with state.lock(name):
        if not state.valid(name,signature):
            state.start(name,signature);path=directory/'totals.npz';atomic_npz(path,core=totals[0],halo=totals[1])
            metadata=directory/'validation.json';atomic_json(metadata,report);state.stage(name,signature,[path,metadata],report)
        elif state.remote:state.remote.sync_task(state,name)
        if state.remote and state.task(name)['state']!='durable':raise RuntimeError('Regional preflight awaits checkpoint')
    return report

if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private regional preflight JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    print(json.dumps(process(state)),flush=True)
