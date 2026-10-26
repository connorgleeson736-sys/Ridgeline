"""Automatic immutable R2 checkpoints through the existing owner-private Site.

The access token lives only in process memory and is sent only to the selected
Site origin. It is never included in configuration, logs, archives or arguments.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from urllib.parse import urlparse,quote
import requests


class Remote:
    def __init__(self,base,token):
        u=urlparse(base)
        if u.scheme!='https' or not u.hostname or not u.hostname.endswith('.chatgpt.site') or u.username or u.password or u.query:
            raise ValueError('Use the exact selected Site HTTPS origin')
        self.base=base.rstrip('/')+'/api/lidar/'
        self._token=token
        self._local=threading.local()

    def request(self,method,path,**kwargs):
        if '://' in path or path.startswith('/'):raise ValueError('Remote paths must stay within the Site')
        session=getattr(self._local,'session',None)
        if session is None:
            session=requests.Session();session.headers['OAI-Sites-Authorization']='Bearer '+self._token
            self._local.session=session
        for attempt in range(5):
            try:
                response=session.request(method,self.base+path,timeout=(20,120),allow_redirects=False,**kwargs)
                if response.status_code in [429,500,502,503,504] and attempt<4:
                    time.sleep(min(2**attempt,8));continue
                if response.is_redirect:raise RuntimeError('Unexpected redirect; credentials were not forwarded')
                if response.status_code not in [200,201,204,206,404]:
                    raise RuntimeError(f'Checkpoint API returned HTTP {response.status_code}')
                return response
            except requests.RequestException:
                if attempt==4:raise RuntimeError('Checkpoint API network request failed') from None
                time.sleep(min(2**attempt,8))

    def record(self,run,kind,name,value):
        data=(json.dumps(value,sort_keys=True,indent=2,allow_nan=False)+'\n').encode()
        sha=hashlib.sha256(data).hexdigest()
        path=f'runs/{run}/{kind}/{quote(name,safe="._-")}/{sha}'
        response=self.request('PUT',path,data=data,headers={'Content-Type':'application/json'})
        if response.status_code!=200 or response.json().get('sha256')!=sha:raise ValueError('Durable record write failed')
        readback=self.request('GET',path)
        if hashlib.sha256(readback.content).hexdigest()!=sha:raise ValueError('Durable record readback hash mismatch')
        return dict(path=path,sha256=sha)

    def file(self,path,mime='application/x-tar'):
        path=Path(path);combined=hashlib.sha256();chunks=[]
        def upload(data):
            sha=hashlib.sha256(data).hexdigest()
            head=self.request('HEAD','blobs/'+sha)
            if head.status_code==404:
                put=self.request('PUT','blobs/'+sha,data=data,headers={'Content-Type':'application/octet-stream'})
                if put.status_code!=200 or put.json().get('sha256')!=sha:raise ValueError('Blob upload hash mismatch')
            elif head.headers.get('X-Content-SHA256')!=sha or int(head.headers.get('Content-Length',-1))!=len(data):
                raise ValueError('Existing persistent blob does not match expected data')
            return dict(sha256=sha,bytes=len(data))
        pending=deque()
        # Four outstanding 8 MiB buffers; hashing/descriptor order always stays
        # in source order even if requests complete in a different order.
        with ThreadPoolExecutor(max_workers=4) as pool,path.open('rb') as source:
            while data:=source.read(8<<20):
                combined.update(data);pending.append(pool.submit(upload,data))
                if len(pending)>=4:chunks.append(pending.popleft().result())
            while pending:chunks.append(pending.popleft().result())
        descriptor=dict(sha256=combined.hexdigest(),bytes=path.stat().st_size,mime=mime,chunks=chunks)
        # The Worker reads every stored chunk and hashes the entire assembled
        # file before accepting this descriptor, including order and byte count.
        response=self.request('PUT','manifests/'+descriptor['sha256'],json=descriptor)
        if response.status_code!=200 or response.json().get('verifiedPersistentSha256')!=descriptor['sha256']:
            raise ValueError('Server-side persistent file readback failed')
        readback=self.request('GET','manifests/'+descriptor['sha256'])
        if readback.json()!=descriptor:raise ValueError('File descriptor readback mismatch')
        return dict(sha256=descriptor['sha256'],bytes=descriptor['bytes'],
                    url=self.base+'files/'+descriptor['sha256'],chunks=len(chunks),verified=True)

    def sync_task(self,state,name):
        task=state.task(name)
        external=task.get('external',{}).get('drive_file_id',{})
        if task['state']=='durable' and isinstance(external,dict) and 'r2' in external:return task['external']
        if task['state'] not in ['staged','durable']:return None
        receipt_path=state.root/'outbox'/(name+'-'+task['signature'][:16]+'.json')
        receipt=json.loads(receipt_path.read_text())
        saved=self.file(receipt['archive'])
        if saved['sha256']!=receipt['sha256']:raise ValueError('Staged checkpoint changed')
        record=self.record(state.run,'tasks',name,dict(task,state='durable',remote=saved))
        state.acknowledge(name,task['signature'],dict(r2=saved,record=record),saved['sha256'])
        return saved

    def sync(self,state,include_events=True):
        self.record(state.run,'configs','config',state.config)
        for p in sorted((state.root/'tasks').glob('*.json')):
            task=json.loads(p.read_text())
            if task['state'] in ['staged','durable']:self.sync_task(state,p.stem)
            elif task['state'] in ['running','failed']:
                self.record(state.run,'tasks',p.stem,task)
        events=sorted((state.root/'events').glob('*.json')) if include_events else []
        for p in events:
            marker=state.root/'remote-receipts/events'/(p.stem+'.json')
            if marker.exists():
                if json.loads(marker.read_text())['sha256']!=hashlib.sha256(p.read_bytes()).hexdigest():
                    raise ValueError('An already mirrored event changed locally')
                continue
            proof=self.record(state.run,'events',p.stem,json.loads(p.read_text()))
            from .state import atomic_json
            atomic_json(marker,proof)
        manifest=state.manifest()
        # Detailed metrics stay in immutable per-task records. A regional
        # status must remain below the Worker's request limit as LODs accumulate.
        summary={k:manifest[k] for k in ['schema','run','updated','counts','plan','render_plan','view'] if k in manifest}
        summary['tasks']={n:{k:t[k] for k in ['state','signature','error'] if k in t}
                          for n,t in manifest['tasks'].items()}
        self.record(state.run,'status',str(time.time_ns()),summary)

    def restore_file(self,reference,destination):
        from .state import digest
        import os
        sha=reference['sha256'];target=Path(destination);temp=target.with_suffix('.download')
        target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists() and digest(target)==sha:return target
        offset=temp.stat().st_size if temp.exists() else 0
        if offset>=reference['bytes']:
            if digest(temp)==sha:temp.replace(target);return target
            temp.unlink();offset=0
        response=self.request('GET','files/'+sha,stream=True,headers={'Range':f'bytes={offset}-'} if offset else {})
        if response.status_code not in [200,206]:raise ValueError('Recovery checkpoint unavailable')
        if offset and (response.status_code!=206 or not response.headers.get('Content-Range','').startswith(f'bytes {offset}-')):offset=0
        with temp.open('ab' if offset else 'wb') as output:
            for data in response.iter_content(8<<20):output.write(data)
            output.flush();os.fsync(output.fileno())
        if digest(temp)!=sha:
            temp.unlink(missing_ok=True);raise ValueError('Recovery download failed SHA-256')
        temp.replace(target)
        return target

    def restore_run(self,run,root,only=None):
        import re
        from .state import State,atomic_json,key,restore
        if not re.fullmatch('[a-f0-9]{64}',run):raise ValueError('Invalid run identity')
        if only is not None and (not isinstance(only,list) or any(not isinstance(n,str) for n in only)):
            raise ValueError('Selective recovery requires a list of task names')
        requested=set(only) if only is not None else None
        root=Path(root);records=[];cursor=None;seen=set()
        while True:
            path='runs/'+run+('?'+'cursor='+quote(cursor,safe='') if cursor else '')
            response=self.request('GET',path)
            if response.status_code!=200:raise ValueError('Recovery run unavailable')
            listing=response.json()
            for entry in listing['objects']:
                parts=entry['key'].split('/')
                if len(parts)!=5 or parts[:2]!=['runs',run] or parts[2] not in ['configs','tasks']:continue
                if parts[2]=='tasks' and requested is not None and parts[3] not in requested:continue
                record=self.request('GET',entry['key'])
                if record.status_code!=200 or hashlib.sha256(record.content).hexdigest()!=parts[-1]:
                    raise ValueError('Recovery journal failed SHA-256')
                records.append((parts,record.json()))
            if not listing.get('truncated'):break
            cursor=listing.get('cursor')
            if not cursor or cursor in seen:raise ValueError('Recovery pagination did not advance')
            seen.add(cursor)
        configs=[value for parts,value in records if parts[2]=='configs' and key(value)==run]
        if not configs:raise ValueError('No hash-verified configuration in recovery journal')
        state=State(root,configs[0]);tasks={}
        for parts,task in records:
            if parts[2]!='tasks' or task.get('state')!='durable' or not task.get('remote'):continue
            if (task.get('run')!=run or task.get('name')!=parts[3]
                or not re.fullmatch('[A-Za-z0-9._-]{1,160}',parts[3])
                or not re.fullmatch('[a-f0-9]{64}',str(task.get('signature','')))):
                raise ValueError('Recovery task identity mismatch')
            reference=task['remote']
            if (not isinstance(reference,dict) or not re.fullmatch('[a-f0-9]{64}',str(reference.get('sha256','')))
                or type(reference.get('bytes')) is not int or not 0<reference['bytes']<=2**31):
                raise ValueError('Invalid recovery file reference')
            name=task['name']
            if only is not None and name not in only:continue
            if (name in tasks and task.get('finished',0)==tasks[name].get('finished',0)
                and (task['signature'],reference['sha256'])!=(tasks[name]['signature'],tasks[name]['remote']['sha256'])):
                raise ValueError('Conflicting recovery task generations')
            if name not in tasks or task.get('finished',0)>tasks[name].get('finished',0):tasks[name]=task
        if requested is not None and requested-set(tasks):
            raise ValueError('Requested durable checkpoints unavailable: '+', '.join(sorted(requested-set(tasks))))
        atomic_json(root/'recovery-scope.json',dict(kind='selected_tasks' if requested is not None else 'all_durable_tasks',
            requested=sorted(requested) if requested is not None else None,
            note='Counts describe this recovered local view; immutable remote journals remain authoritative for all run tasks'))
        restored=[]
        for name,task in sorted(tasks.items()):
            with state.lock(name):
                local=state.task(name) or {}
                proof=local.get('external',{}).get('drive_file_id',{})
                if (state.valid(name,task['signature']) and local.get('state')=='durable'
                    and isinstance(proof,dict) and proof.get('r2',{}).get('sha256')==task['remote']['sha256']):continue
                reference=task['remote'];archive=root/'outbox'/(name+'-'+task['signature'][:16]+'.tar')
                self.restore_file(reference,archive)
                restore(archive,root,reference['sha256'])
                if not state.valid(name,task['signature']):raise ValueError('Recovered task validation failed')
                receipt=dict(task=name,signature=task['signature'],archive=str(archive),bytes=archive.stat().st_size,
                             sha256=reference['sha256'],state='upload_pending')
                atomic_json(archive.with_suffix('.json'),receipt)
                state.acknowledge(name,task['signature'],dict(r2=reference,recovered=True),reference['sha256'])
                restored.append(name)
        state.manifest()
        return dict(run=run,restored=restored,eligible=len(tasks),counts=state.manifest()['counts'])
