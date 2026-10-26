"""Persist a comparison of independently restored and original task artifacts."""
import json
from pathlib import Path
from .state import State, atomic_json, key


def verify(reference, restored):
    if reference.root==restored.root or reference.run!=restored.run:
        raise ValueError('Distinct recovery directory with identical run required')
    tasks=[]
    for path in sorted((restored.root/'tasks').glob('*.json')):
        task=restored.task(path.stem);original=reference.task(path.stem)
        if (not original or task['state']!='durable' or original['state']!='durable'
            or task['signature']!=original['signature']
            or not restored.valid(path.stem,task['signature'])
            or not reference.valid(path.stem,task['signature'])
            or task['artifacts']!=original['artifacts']
            or not task.get('external',{}).get('drive_file_id',{}).get('recovered')):
            raise ValueError('Recovered checkpoint differs from verified original: '+path.stem)
        tasks.append(dict(task=path.stem,signature=task['signature'],artifacts=task['artifacts']))
    if not tasks:raise ValueError('No independently restored tasks to verify')
    return dict(schema=1,run=reference.run,tasks=tasks,verified=True,
        recovered_tasks=len(tasks),verified_artifact_bytes=sum(a['bytes'] for t in tasks for a in t['artifacts']),
        verification='Remote.restore_run journal/archive/artifact SHA checks plus independent original/restored byte-hash equality.',
        limitation='Persistence/recovery verification only; not a browser, GPU or geographic accuracy benchmark.')


if __name__=='__main__':
    from .remote import Remote
    print('Ready for private recovery proof JSON on hidden stdin.',flush=True)
    settings=json.loads(input());remote=Remote(settings['base'],settings.pop('token'))
    root=Path(settings['root']);recovered=Path(settings['recovered_root'])
    original=State(root,json.loads((root/'config.json').read_text()))
    restored=State(recovered,json.loads((recovered/'config.json').read_text()))
    report=verify(original,restored);sha=key(report)
    proof=remote.record(original.run,'events','verified-regional-recovery-'+sha[:16],report)
    path=root/'data/recovery'/('verified-'+sha+'.json');atomic_json(path,dict(report,external_proof=proof))
    print(json.dumps(dict(path=str(path),tasks=report['recovered_tasks'],bytes=report['verified_artifact_bytes'],proof=proof)),flush=True)
