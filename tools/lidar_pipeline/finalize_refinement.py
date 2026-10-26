"""Recover completed, independently validated geometry after metadata failure.

Never regenerate or silently bless a partial candidate. Load CRC-valid arrays,
recheck every release gate against the durable original reconstruction, and
commit the original job only after all gates pass again.
"""
import json
import time
import numpy as np
from .state import atomic_json,digest
from .topology import split_fans
from .validate_mesh import validate


def recover(state,label='0381',budget=.2):
    name='feedback-lod-'+label+'-'+format(budget,'.6g').replace('.','p')
    with state.lock(name):
        task=state.task(name)
        if not task or task['state'] not in ['failed','staged','durable']:
            raise ValueError('No interrupted finalization to recover')
        if state.valid(name,task['signature']):
            if state.remote:state.remote.sync_task(state,name)
            return state.task(name)['metrics']
        source=state.task('compare-'+label+'-afs-2')
        if not source or source['state']!='durable' or not state.valid(source['name'],source['signature']):
            raise ValueError('Original reconstruction is not intact and durable')
        directory=state.root/'data/feedback'/name
        history=json.loads((directory/'progress.json').read_text())
        if not history or not history[-1].get('validation',{}).get('accepted'):
            raise ValueError('Interrupted trial has no accepted validation')
        source_path=state.root/'data/experiments'/label/'afs-2/surface.npz'
        with np.load(source_path) as data:v,f=data['vertices'],data['faces']
        v,f,_,_=split_fans(v,f)
        arrays=directory/'mesh.npz'
        with np.load(arrays) as data:cv,cf=data['vertices'],data['faces']
        started=time.perf_counter();report=validate(v,f,cv,cf,budget)
        if not report['accepted']:raise ValueError('Recovered arrays fail independent revalidation')
        report.update(candidate_accepted=True,attempts=history,publishable=False,
            recovery=dict(geometry_modified=False,metadata_repaired=True,
                original_task_signature=task['signature'],source_sha256=digest(source_path),
                revalidation_seconds=time.perf_counter()-started,
                prior_error=task.get('error'),prior_finished=task.get('finished')))
        metadata=directory/'validation.json';atomic_json(metadata,report)
        state.stage(name,task['signature'],[arrays,metadata,directory/'progress.json'],report)
        print(json.dumps(dict(task=name,state=state.task(name)['state'],triangles=len(cf),
            continuous_bound_m=report['continuous_bound']['upper_bound_m'])),flush=True)
        return report
