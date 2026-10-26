"""Reconcile a bounded selection of immutable checkpoints after runtime loss.

Inventory is evidence of journal state, not artifact verification or completion
of the region. restore_run independently verifies the selected archives/files.
Credentials enter through hidden stdin and are never saved in the snapshot.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import quote

from .state import atomic_json, key


def inventory(remote, run, prefixes):
    if not re.fullmatch('[a-f0-9]{64}', run):
        raise ValueError('Invalid run identity')
    if (not isinstance(prefixes, list) or not prefixes
        or any(not isinstance(p, str) or not re.fullmatch('[A-Za-z0-9._-]{1,160}', p) for p in prefixes)):
        raise ValueError('Explicit safe task prefixes required')
    paths = set(); cursor = None; seen = set(); pages = 0
    while True:
        path = 'runs/' + run + ('?cursor=' + quote(cursor, safe='') if cursor else '')
        response = remote.request('GET', path)
        if response.status_code != 200:
            raise ValueError('Recovery run unavailable')
        listing = response.json(); pages += 1
        for entry in listing['objects']:
            parts = entry['key'].split('/')
            if (len(parts) == 5 and parts[:3] == ['runs', run, 'tasks']
                and any(parts[3].startswith(p) for p in prefixes)):
                if not re.fullmatch('[A-Za-z0-9._-]{1,160}', parts[3]) or not re.fullmatch('[a-f0-9]{64}', parts[4]):
                    raise ValueError('Invalid journal path')
                paths.add(entry['key'])
        if not listing.get('truncated'):
            break
        cursor = listing.get('cursor')
        if not isinstance(cursor, str) or not cursor or cursor in seen:
            raise ValueError('Recovery pagination did not advance')
        seen.add(cursor)

    def read(path):
        response = remote.request('GET', path)
        if response.status_code != 200 or hashlib.sha256(response.content).hexdigest() != path.split('/')[-1]:
            raise ValueError('Recovery journal failed SHA-256')
        task = response.json()
        if task.get('run', run) != run or task.get('name') != path.split('/')[-2]:
            raise ValueError('Recovery task identity mismatch')
        if task.get('state') == 'durable' and task.get('run') != run:
            raise ValueError('Durable task missing run identity')
        return path, task

    tasks = {}
    with ThreadPoolExecutor(max_workers=4) as pool:
        for path, task in pool.map(read, sorted(paths)):
            item = tasks.setdefault(task['name'], {'durable': None, 'latest_attempt': None})
            # A later failed/running attempt must not hide an earlier durable
            # generation. This mirrors restore_run's completed-generation rule.
            stamp = max(task.get('started', 0), task.get('finished', 0))
            if item['latest_attempt'] is None or stamp > item['latest_attempt']['timestamp']:
                item['latest_attempt'] = dict(state=task['state'], timestamp=stamp, journal=path)
            if task.get('state') == 'durable' and task.get('remote'):
                ref = task['remote']; signature = task.get('signature')
                if (not re.fullmatch('[a-f0-9]{64}', str(signature))
                    or not isinstance(ref, dict) or not re.fullmatch('[a-f0-9]{64}', str(ref.get('sha256')))
                    or type(ref.get('bytes')) is not int or not 0 < ref['bytes'] <= 2**31):
                    raise ValueError('Invalid recovery file reference')
                candidate = dict(signature=signature, finished=task.get('finished', 0),
                                 archive_sha256=ref['sha256'], bytes=ref['bytes'], journal=path,
                                 metrics=task.get('metrics', {}))
                old = item['durable']
                if old and old['finished'] == candidate['finished'] and (old['signature'], old['archive_sha256']) != (signature, ref['sha256']):
                    raise ValueError('Conflicting recovery task generations')
                if old is None or candidate['finished'] > old['finished']:
                    item['durable'] = candidate
    return dict(schema=1, run=run, prefixes=prefixes, pages=pages, journal_records=len(paths),
                observed_at=time.time(), tasks=dict(sorted(tasks.items())),
                restore_names=sorted(n for n, item in tasks.items() if item['durable']),
                artifact_verification='pending independent restore_run validation',
                note='Selected task prefixes only; not regional completion. Durable generations remain reusable after later failed attempts.')


def main():
    from .remote import Remote
    print('Ready for private recovery inventory JSON on hidden stdin.', flush=True)
    settings = json.loads(input()); remote = Remote(settings['base'], settings.pop('token'))
    result = inventory(remote, settings['run'], settings['prefixes'])
    sha = key(result)
    # External readback is mandatory before describing the inventory as saved.
    proof = remote.record(result['run'], 'events', 'recovery-inventory-' + sha[:16], result)
    output = Path(settings['root']) / 'data/recovery' / ('inventory-' + sha + '.json')
    atomic_json(output, dict(result, external_proof=proof))
    print(json.dumps(dict(path=str(output), proof=proof, restore_names=result['restore_names'],
                         matched_tasks=len(result['tasks']), journal_records=result['journal_records'])), flush=True)
    if settings.get('restore'):
        print(json.dumps(remote.restore_run(result['run'], settings['root'], result['restore_names'])), flush=True)


if __name__ == '__main__':
    main()
