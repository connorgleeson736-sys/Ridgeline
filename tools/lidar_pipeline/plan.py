"""Durable expected-work inventory, separate from tasks already attempted."""
import math
from .state import atomic_json,key

def create(state):
    c=state.config;size=c['tile_size_m']
    nx=math.ceil((c['bounds'][2]-c['bounds'][0])/size);ny=math.ceil((c['bounds'][3]-c['bounds'][1])/size)
    tasks=[]
    for tile in range(nx*ny):
        tasks.append(dict(name=f'surface-{tile:04d}',kind='finest',tile=tile))
        for budget in c['lod_errors_m']:
            if budget<=0:continue
            label=format(budget,'.6g').replace('.','p')
            tasks.append(dict(name=f'lod-{tile:04d}-{label}',kind='lod',tile=tile,budget_m=budget))
    plan=dict(schema=1,run=state.run,columns=nx,rows=ny,tasks=tasks,
        note='Processing units, not render nodes. Dense patches may expand into durable child jobs; a parent commit references their outputs. Publishing and geometric validation remain separate gates.')
    signature=key(plan)
    if state.valid('plan',signature):
        if state.remote:state.remote.sync_task(state,'plan')
        return plan
    path=state.root/'data/plan.json';atomic_json(path,plan)
    state.stage('plan',signature,[path],dict(expected_tasks=len(tasks),finest_jobs=nx*ny))
    return plan
