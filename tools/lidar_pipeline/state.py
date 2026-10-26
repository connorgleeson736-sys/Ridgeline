"""Atomic, checksummed stages with an explicit external-durability barrier.

An uploaded checkpoint is immutable. Mutable manifests are indexes, never the
only recovery record. A crash before acknowledgement repeats at most that task.
"""
from __future__ import annotations
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path,PurePosixPath
import tarfile
import time
import uuid
import io
import zipfile
import threading


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(4 << 20), b''):
            h.update(b)
    return h.hexdigest()


def key(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def atomic_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temp.open('xb') as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, path)
        # Some network filesystems cannot fsync directories. File checksums and
        # immutable commit records remain mandatory on those backends.
        try:
            fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
        except OSError:
            pass
    finally:
        temp.unlink(missing_ok=True)


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n').encode())


def atomic_npz(path, **arrays):
    import numpy as np
    buffer=io.BytesIO()
    np.savez_compressed(buffer,**arrays)
    data=buffer.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.testzip() is not None:raise ValueError('Intermediate array archive failed CRC validation')
    atomic_bytes(path,data)


class State:
    def __init__(self, root, config, remote=None):
        self.root = Path(root).resolve()
        self.config = config
        self.run = key(config)
        self.remote=remote
        self._manifest_cache={}
        self._manifest_lock=threading.RLock()
        existing = self.root / 'config.json'
        if existing.exists() and key(json.loads(existing.read_text())) != self.run:
            raise ValueError('Different pipeline configuration: choose a new run root; never overwrite a recovery generation')
        for name in ['tasks', 'events', 'outbox', 'receipts', 'data']:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        atomic_json(self.root / 'config.json', config)

    @contextlib.contextmanager
    def lock(self, name):
        # Per-task locks allow independent workers, prevent duplicate execution.
        with (self.root / 'tasks' / (name + '.lock')).open('a') as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            yield

    def task(self, name):
        path = self.root / 'tasks' / (name + '.json')
        return json.loads(path.read_text()) if path.exists() else None

    def event(self, name, **values):
        event = dict(time=time.time(), task=name, **values)
        event_name=f'{time.time_ns()}-{uuid.uuid4().hex[:6]}'
        atomic_json(self.root / 'events' / (event_name+'.json'), event)
        if self.remote:
            try:
                proof=self.remote.record(self.run,'events',event_name,event)
                atomic_json(self.root/'remote-receipts/events'/(event_name+'.json'),proof)
            except Exception:pass # Local event remains queued for the next sync.

    def valid(self, name, signature):
        t = self.task(name)
        return bool(t and t.get('signature') == signature and t.get('state') in
                    ['staged', 'durable'] and all((self.root / a['path']).is_file() and
                    (self.root / a['path']).stat().st_size == a['bytes'] and
                    digest(self.root / a['path']) == a['sha256'] for a in t['artifacts']))

    def start(self, name, signature):
        previous = self.task(name) or {}
        atomic_json(self.root / 'tasks' / (name + '.json'), dict(name=name,
                    signature=signature, state='running', started=time.time(),
                    attempt=previous.get('attempt', 0) + 1))
        self.event(name, state='running', signature=signature)
        self.manifest()

    def fail(self, name, error):
        t = self.task(name) or dict(name=name)
        t.update(state='failed', error=str(error), finished=time.time())
        atomic_json(self.root / 'tasks' / (name + '.json'), t)
        if self.remote:
            try:self.remote.record(self.run,'tasks',name,t)
            except Exception:pass
        self.event(name, state='failed', error=str(error))
        self.manifest()

    def stage(self, name, signature, paths, metrics=None):
        artifacts = [dict(path=str(Path(p).resolve().relative_to(self.root)),
                          bytes=Path(p).stat().st_size, sha256=digest(p)) for p in paths]
        t = dict(name=name, signature=signature, state='staged', artifacts=artifacts,
                 metrics=metrics or {}, finished=time.time(), run=self.run)
        atomic_json(self.root / 'tasks' / (name + '.json'), t)
        # Each archive contains everything needed for this stage, independently
        # of a later manifest update. No secrets or unrelated files are included.
        archive = self.root / 'outbox' / (name + '-' + signature[:16] + '.tar')
        temp = archive.with_suffix('.tmp')
        with tarfile.open(temp, 'w') as tar:
            for a in artifacts:
                tar.add(self.root / a['path'], arcname=a['path'])
            tar.add(self.root / 'tasks' / (name + '.json'), arcname='tasks/' + name + '.json')
            tar.add(self.root / 'config.json', arcname='config.json')
        with temp.open('rb') as f: os.fsync(f.fileno())
        os.replace(temp, archive)
        receipt = dict(task=name, signature=signature, archive=str(archive),
                       bytes=archive.stat().st_size, sha256=digest(archive), state='upload_pending')
        atomic_json(archive.with_suffix('.json'), receipt)
        self.event(name, state='staged', **dict(metrics=metrics or {}))
        self.manifest()
        if self.remote:
            try:
                self.remote.sync_task(self,name)
                receipt['state']='durable'
            except Exception as error:
                # Completed computation remains resumable; a transient upload
                # failure must not turn a valid output into repeated work.
                self.event(name,state='upload_pending',error=str(error))
        return receipt

    def acknowledge(self, name, signature, file_id, remote_sha256):
        entry = json.loads((self.root / 'outbox' / (name + '-' + signature[:16] + '.json')).read_text())
        archive = Path(entry['archive'])
        if not remote_sha256 or remote_sha256 != entry['sha256']:
            raise ValueError('External checkpoint readback SHA-256 does not match upload')
        if digest(archive) != entry['sha256']:
            raise ValueError('Checkpoint changed since staging')
        t = self.task(name)
        if not t or t['signature'] != signature:
            raise ValueError('Acknowledgement belongs to a different stage generation')
        entry.update(state='durable', drive_file_id=file_id, verified_sha256=remote_sha256, verified_at=time.time())
        atomic_json(self.root / 'receipts' / (name + '-' + signature[:16] + '.json'), entry)
        t['state'] = 'durable'
        t['external'] = entry
        atomic_json(self.root / 'tasks' / (name + '.json'), t)
        self.event(name, state='durable', file_id=file_id)
        self.manifest()

    def upload_parts(self, name, signature, chunk_bytes=16 << 20):
        """Bound connector request sizes; immutable parts survive partial upload."""
        entry=json.loads((self.root/'outbox'/(name+'-'+signature[:16]+'.json')).read_text())
        archive=Path(entry['archive']);directory=self.root/'outbox'/'parts'/archive.stem
        directory.mkdir(parents=True,exist_ok=True)
        parts=[]
        with archive.open('rb') as source:
            i=0
            while data:=source.read(chunk_bytes):
                path=directory/f'{archive.name}.part-{i:04d}'
                sha=hashlib.sha256(data).hexdigest()
                if not path.exists() or digest(path)!=sha:atomic_bytes(path,data)
                parts.append(dict(index=i,path=str(path),bytes=len(data),sha256=sha))
                i+=1
        return parts

    def acknowledge_part(self, name, signature, part, file_id, remote_sha256):
        if remote_sha256!=part['sha256']:raise ValueError('Remote part checksum mismatch')
        task=self.task(name)
        if task['signature']!=signature:raise ValueError('Part belongs to another generation')
        task.setdefault('upload_parts',{})[str(part['index'])]=dict(index=part['index'],
            bytes=part['bytes'],sha256=remote_sha256,drive_file_id=file_id,verified_at=time.time())
        atomic_json(self.root/'tasks'/(name+'.json'),task)
        self.manifest()

    def manifest(self):
        with self._manifest_lock:return self._manifest_index()

    def _manifest_index(self):
        tasks={}
        # Detailed histories/artifact lists remain in per-task commit records.
        # Rewriting every history into every progress update grows quadratically
        # across regional render clusters. Cache compact summaries by file stamp.
        for p in (self.root/'tasks').glob('*.json'):
            stat=p.stat();stamp=(stat.st_mtime_ns,stat.st_size)
            cached=self._manifest_cache.get(p.stem)
            if cached is None or cached[0]!=stamp:
                task=json.loads(p.read_text())
                summary={k:task[k] for k in ['name','signature','state','error','started','finished','attempt'] if k in task}
                cached=(stamp,summary);self._manifest_cache[p.stem]=cached
            tasks[p.stem]=cached[1]
        counts = {s: sum(t['state'] == s for t in tasks.values()) for s in ['running', 'failed', 'staged', 'durable']}
        value = dict(schema=1, run=self.run, updated=time.time(), counts=counts, tasks=tasks)
        scope=self.root/'recovery-scope.json'
        if scope.exists():value['view']=json.loads(scope.read_text())
        planned=self.root/'data/plan.json'
        if planned.exists():
            plan=json.loads(planned.read_text())
            expected=[t['name'] for t in plan['tasks']]
            pending=[name for name in expected if name not in tasks]
            value['plan']=dict(path='data/plan.json',expected=len(expected),pending=pending,
                counts={s:sum(tasks.get(name,{}).get('state')==s for name in expected)
                        for s in ['running','failed','staged','durable']})
            value['plan']['counts']['pending']=len(pending)
        render=[]
        for path in sorted((self.root/'data/render-plans').glob('*.json')):
            render.extend(json.loads(path.read_text())['tasks'])
        if render:
            expected=sorted(set(render));pending=[n for n in expected if n not in tasks]
            value['render_plan']=dict(expected=len(expected),pending=pending,
                counts={s:sum(tasks.get(name,{}).get('state')==s for name in expected)
                        for s in ['running','failed','staged','durable']})
            value['render_plan']['counts']['pending']=len(pending)
        atomic_json(self.root / 'progress.json', value)
        return value


def restore(archive, root, expected_sha):
    archive, root = Path(archive), Path(root).resolve()
    if digest(archive) != expected_sha:
        raise ValueError('Recovery archive checksum mismatch')
    with tarfile.open(archive) as tar:
        members={}
        for member in tar.getmembers():
            target = (root / member.name).resolve()
            parts=PurePosixPath(member.name).parts
            if (not target.is_relative_to(root) or not member.isfile() or member.name in members
                or not parts or any(p in ['.','..'] for p in member.name.split('/'))
                or '\\' in member.name or str(PurePosixPath(member.name))!=member.name):
                raise ValueError('Unsafe checkpoint member')
            members[member.name]=member
        config=json.load(tar.extractfile(members['config.json']))
        if (root/'config.json').exists() and key(json.loads((root/'config.json').read_text()))!=key(config):
            raise ValueError('Recovery configuration generation mismatch')
        tasks=[json.load(tar.extractfile(m)) for name,m in members.items() if name.startswith('tasks/')]
        if len(tasks)!=1:raise ValueError('Checkpoint must contain exactly one task record')
        expected={'config.json'}
        for task in tasks:
            if task.get('run')!=key(config):raise ValueError('Checkpoint run mismatch')
            task_path='tasks/'+task['name']+'.json'
            if task_path not in members:raise ValueError('Checkpoint task path mismatch')
            expected.add(task_path)
            for a in task['artifacts']:
                if not a['path'].startswith('data/'):raise ValueError('Artifact outside data tree')
                member=members.get(a['path'])
                if member is None or member.size!=a['bytes']:raise ValueError('Missing recovery artifact')
                expected.add(a['path'])
                h=hashlib.sha256()
                with tar.extractfile(member) as source:
                    for chunk in iter(lambda:source.read(4<<20),b''):h.update(chunk)
                if h.hexdigest()!=a['sha256']:raise ValueError('Restored artifact checksum mismatch')
        if set(members)!=expected:raise ValueError('Checkpoint has untracked members')
        ordered=sorted(members,key=lambda name:name.startswith('tasks/'))
        for name in ordered:
            with tar.extractfile(members[name]) as source:
                atomic_bytes(root/name,source.read())
