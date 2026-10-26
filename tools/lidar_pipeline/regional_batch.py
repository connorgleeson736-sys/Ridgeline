"""Sequential, externally checkpointed regional reconstruction dispatcher.

The 400-job plan is durable before execution. Each surface is independently
durable before scheduling another. A bounded invocation stops between jobs;
reinvocation skips valid completed geometry and retries unfinished work.
"""
import json
from pathlib import Path
import resource
import time
from . import local_trial, reconstruct
from . import source_generations
from .state import atomic_json, digest, key


def order(nx, ny):
    return sorted(range(nx*ny), key=lambda i: ((i%nx-(nx-1)/2)**2+(i//nx-(ny-1)/2)**2, i))


def process(state, native_task, limit=4, max_seconds=1800):
    if type(limit) is not int or not 0 <= limit <= 400:
        raise ValueError('Bounded job limit must be between0 and400;0 verifies completed work only')
    if not isinstance(max_seconds, (int, float)) or not 1 <= max_seconds <= 86400:
        raise ValueError('Bounded processing duration required')
    preflight=state.task('regional-preflight-r2')
    if not preflight or preflight['state']!='durable' or not state.valid(preflight['name'],preflight['signature']):
        raise ValueError('Durable complete regional capacity audit required')
    if not preflight['metrics']['all_native_jobs_within_record_cap']:
        raise ValueError('Dense jobs require subdivision before regional dispatch')
    native=state.task(native_task)
    if not native or native['state']!='durable' or not state.valid(native_task,native['signature']):
        raise ValueError('Verified durable exact native toolchain required')
    binary_artifacts=[a for a in native['artifacts'] if Path(a['path']).name=='local-ballmerge']
    if len(binary_artifacts)!=1:
        raise ValueError('Native checkpoint must contain exactly one reconstruction executable')
    binary=state.root/binary_artifacts[0]['path']
    if digest(binary)!=native['metrics']['binary_sha256']['local-ballmerge']:
        raise ValueError('Native reconstruction executable identity changed')
    binary.chmod(0o755)
    size=state.config['tile_size_m'];bounds=state.config['bounds']
    nx=int((bounds[2]-bounds[0])/size);ny=int((bounds[3]-bounds[1])/size)
    tiles=order(nx,ny);delta=1.35;revision='r2'
    source_records=source_generations.prepare(state)
    recipe=dict(stage='regional-sequential-reconstruction-v1',revision=revision,delta=delta,tiles=tiles,
        preflight_signature=preflight['signature'],native_task=native_task,native_signature=native['signature'],
        reconstruction_sha256=digest(local_trial.__file__),clipping_sha256=digest(reconstruct.__file__),
        source_audit_signature=state.task('source-audit')['signature'],
        source_generation_verifier=digest(source_generations.__file__),
        implementation_sha256=digest(__file__),concurrency=1,geometry_sampling=False,
        note='Full-XYZ surface experiments. Region release requires seams, coverage, LOD/export and device gates.')
    signature=key(recipe);plan_name='regional-plan-r2';directory=state.root/'data/regional/batch-r2'
    with state.lock(plan_name):
        if not state.valid(plan_name,signature):
            state.start(plan_name,signature);path=directory/'plan.json';atomic_json(path,recipe)
            state.stage(plan_name,signature,[path],dict(required_patches=len(tiles),native_task=native_task))
        elif state.remote:
            state.remote.sync_task(state,plan_name)
        if state.remote and state.task(plan_name)['state']!='durable':
            raise RuntimeError('Regional plan awaits external checkpoint')

    def snapshot(current=None, error=None):
        completed=[];pending=[];failed=[];triangles=0
        for tile in tiles:
            name=f'local-surface-{tile:04d}-d1p35-r2';task=state.task(name)
            if task and task['state']=='durable' and state.valid(name,task['signature']):
                # Fail closed if code or native generation was replaced.
                r=task['metrics']['recipe']
                if (r['native_sha256']!=recipe_native or r['python_implementation']!=recipe['reconstruction_sha256']
                    or r['clipping_implementation']!=recipe['clipping_sha256']
                    or task['signature']!=source_generations.expected(state,tile,task,source_records)):
                    raise ValueError('Existing r2 surface belongs to another reconstruction generation')
                completed.append(tile);triangles+=task['metrics']['triangles']
            else:
                pending.append(tile)
                if task and task['state']=='failed':failed.append(tile)
        value=dict(schema=1,run=state.run,plan_signature=signature,updated=time.time(),
            required_patches=len(tiles),completed=completed,pending=pending,failed=failed,current=current,
            error=error,completed_surface_triangles=triangles,publishable=False,
            peak_python_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            peak_native_child_rss_kib=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
            memory_scope='Linux process lifetime maxima, separate parent/child; not summed concurrent or browser GPU memory')
        if state.remote:
            proof=state.remote.record(state.run,'status','regional-r2-'+str(time.time_ns()),value)
            value['external_proof']=proof
        atomic_json(directory/'progress.json',value)
        print(json.dumps(dict(completed=len(completed),pending=len(pending),failed=failed,current=current,
                              triangles=triangles,proof=value.get('external_proof'))),flush=True)
        return value

    recipe_native=native['metrics']['binary_sha256']['local-ballmerge']
    value=snapshot();started=time.monotonic();executed=0
    for tile in value['pending']:
        if executed>=limit or time.monotonic()-started>=max_seconds:
            break
        snapshot(current=tile)
        try:
            local_trial.process(state,tile,binary.parent,delta,revision)
            executed+=1;snapshot()
        except Exception as error:
            snapshot(current=tile,error=str(error));raise
    value=snapshot();value['new_tasks_this_invocation']=executed
    return value


if __name__=='__main__':
    from .state import State
    from .remote import Remote
    print('Ready for private regional batch JSON on hidden stdin.',flush=True)
    settings=json.loads(input());root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    result=process(state,settings['native_task'],settings.get('limit',4),settings.get('max_seconds',1800))
    print(json.dumps(dict(completed=len(result['completed']),pending=len(result['pending']),
        failed=result['failed'],new_tasks=result['new_tasks_this_invocation'],publishable=False)),flush=True)
