"""Recover from disk loss, interrupted transfer and corruption using journals."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from tools.lidar_pipeline.remote import Remote
from tools.lidar_pipeline.state import State,atomic_bytes,key
import threading

class Response:
    def __init__(self,data,status=200,headers=None):
        self.content=data;self.status_code=status;self.headers=headers or {}
    def json(self):return json.loads(self.content)
    def iter_content(self,size):
        for first in range(0,len(self.content),size):yield self.content[first:first+size]

class MemoryRemote(Remote):
    def __init__(self):self.objects={};self.reads=[]
    def record(self,run,kind,name,value):
        data=(json.dumps(value,sort_keys=True,indent=2)+'\n').encode();sha=hashlib.sha256(data).hexdigest()
        path=f'runs/{run}/{kind}/{name}/{sha}';self.objects[path]=data
    def request(self,method,path,**kwargs):
        assert method=='GET';self.reads.append(path)
        if path.startswith('runs/') and len(path.split('?')[0].split('/'))==2:
            keys=sorted(k for k in self.objects if k.startswith(path.split('?')[0]+'/'))
            index=int(path.split('cursor=')[1]) if 'cursor=' in path else 0
            return Response(json.dumps({'objects':[{'key':k} for k in keys[index:index+1]],
                'truncated':index+1<len(keys),'cursor':str(index+1)}).encode())
        data=self.objects.get(path)
        if data is None:return Response(b'',404)
        ranges=kwargs.get('headers',{}).get('Range')
        if ranges:
            first=int(ranges[6:-1]);return Response(data[first:],206,{'Content-Range':f'bytes {first}-{len(data)-1}/{len(data)}'})
        return Response(data)

class Recovery(unittest.TestCase):
    def test_selective_recovery_skips_unrequested_records_and_labels_partial_view(self):
        with tempfile.TemporaryDirectory() as directory:
            remote=MemoryRemote();config={'version':1};run=key(config)
            remote.record(run,'configs','config',config)
            # Its advertised hash does not match; an unrequested checkpoint
            # must not be fetched/parsed during explicitly selective recovery.
            bad=f'runs/{run}/tasks/unrequested/'+('0'*64)
            remote.objects[bad]=b'corrupt unrelated record'
            target=Path(directory)/'partial'
            result=remote.restore_run(run,target,only=[])
            self.assertEqual(result['restored'],[]);self.assertNotIn(bad,remote.reads)
            self.assertEqual(State(target,config).manifest()['view']['kind'],'selected_tasks')
            with self.assertRaisesRegex(ValueError,'Requested durable checkpoints unavailable'):
                remote.restore_run(run,Path(directory)/'missing',only=['absent'])
            with self.assertRaisesRegex(ValueError,'list of task names'):
                remote.restore_run(run,Path(directory)/'wrong',only='unrequested')

    def test_parallel_chunk_upload_preserves_order_and_reuses_persisted_chunks(self):
        class ChunkRemote(Remote):
            def __init__(self):
                self.base='https://fixture.chatgpt.site/api/lidar/';self.blobs={};self.manifests={};self.writes=0
                self.lock=threading.Lock()
            def request(self,method,path,**kwargs):
                kind,sha=path.split('/')
                with self.lock:
                    if kind=='blobs':
                        if method=='HEAD':
                            data=self.blobs.get(sha)
                            return Response(b'',200 if data is not None else 404,
                                {'X-Content-SHA256':sha,'Content-Length':str(len(data))} if data is not None else {})
                        data=kwargs['data'];assert hashlib.sha256(data).hexdigest()==sha
                        self.blobs[sha]=data;self.writes+=1
                        return Response(json.dumps({'sha256':sha}).encode())
                    if method=='PUT':
                        descriptor=kwargs['json'];combined=hashlib.sha256()
                        for part in descriptor['chunks']:combined.update(self.blobs[part['sha256']])
                        assert combined.hexdigest()==sha
                        self.manifests[sha]=descriptor
                        return Response(json.dumps({'verifiedPersistentSha256':sha}).encode())
                    return Response(json.dumps(self.manifests[sha]).encode())
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'checkpoint.tar';payload=b'A'*(8<<20)+b'B'*(8<<20)+b'C'*(1<<20)
            path.write_bytes(payload);remote=ChunkRemote();proof=remote.file(path)
            self.assertEqual(proof['sha256'],hashlib.sha256(payload).hexdigest())
            self.assertEqual(proof['chunks'],3);self.assertEqual(remote.writes,3)
            self.assertEqual(remote.file(path)['sha256'],proof['sha256']);self.assertEqual(remote.writes,3)

    def test_disk_loss_partial_transfer_and_idempotence(self):
        with tempfile.TemporaryDirectory() as directory:
            source=State(Path(directory)/'source',{'version':1});output=source.root/'data/points.bin'
            atomic_bytes(output,b'original xyz points');signature=key('input')
            source.start('tile',signature);receipt=source.stage('tile',signature,[output])
            remote=MemoryRemote();remote.record(source.run,'configs','config',source.config)
            reference={'sha256':receipt['sha256'],'bytes':receipt['bytes'],'verified':True}
            remote.objects['files/'+receipt['sha256']]=Path(receipt['archive']).read_bytes()
            remote.record(source.run,'tasks','tile',dict(source.task('tile'),state='durable',remote=reference))
            recovered=Path(directory)/'recovered';partial=recovered/'outbox'/('tile-'+signature[:16]+'.download')
            partial.parent.mkdir(parents=True);partial.write_bytes(Path(receipt['archive']).read_bytes()[:128])
            result=remote.restore_run(source.run,recovered)
            self.assertEqual(result['restored'],['tile']);self.assertEqual(result['counts']['durable'],1)
            self.assertEqual((recovered/'data/points.bin').read_bytes(),output.read_bytes())
            reads=sum(p.startswith('files/') for p in remote.reads)
            self.assertEqual(remote.restore_run(source.run,recovered)['restored'],[])
            self.assertEqual(sum(p.startswith('files/') for p in remote.reads),reads)
            (recovered/'data/points.bin').write_bytes(b'corrupt')
            self.assertEqual(remote.restore_run(source.run,recovered)['restored'],['tile'])
            self.assertEqual(sum(p.startswith('files/') for p in remote.reads),reads)

    def test_corrupt_journal_never_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            remote=MemoryRemote();run=key({'version':1})
            remote.objects[f'runs/{run}/configs/config/'+('0'*64)]=b'{"version":1}'
            target=Path(directory)/'recovered'
            with self.assertRaises(ValueError):remote.restore_run(run,target)
            self.assertFalse(target.exists())

if __name__=='__main__':unittest.main()
