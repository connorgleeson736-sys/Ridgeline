"""Crash/restart must reuse durable surfaces and preserve the full pending plan."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from tools.lidar_pipeline.state import State, atomic_bytes, atomic_json, digest, key
from tools.lidar_pipeline import regional_batch as batch


class DurableFixture:
    def __init__(self):self.records=[]
    def record(self, run, kind, name, value):
        self.records.append((kind,name,value))
        return dict(path=f'runs/{run}/{kind}/{name}/'+key(value),sha256=key(value))
    def sync_task(self,state,name):
        task=state.task(name)
        if task['state']=='durable':return
        receipt=json.loads((state.root/'outbox'/(name+'-'+task['signature'][:16]+'.json')).read_text())
        state.acknowledge(name,task['signature'],{'r2':{'sha256':receipt['sha256']}},receipt['sha256'])


class Batch(unittest.TestCase):
    def test_crash_then_resume_reuses_durable_patch(self):
        with tempfile.TemporaryDirectory() as directory:
            remote=DurableFixture();config=dict(bounds=[0,0,2000,2000],tile_size_m=100,halo_m=8,origin=[0,0,0])
            state=State(directory,config,remote)
            def staged(name,paths,metrics):
                signature=key(name);state.start(name,signature);state.stage(name,signature,paths,metrics)
            native=state.root/'data/native/local-ballmerge';atomic_bytes(native,b'fixture binary')
            staged('native-fixture',[native],dict(binary_sha256={'local-ballmerge':digest(native)}))
            audit=state.root/'data/preflight.json';atomic_bytes(audit,b'capacity fixture')
            staged('regional-preflight-r2',[audit],dict(all_native_jobs_within_record_cap=True))
            inventory=state.root/'data/shards/fixture/inventory.json'
            atomic_json(inventory,dict(source_sha256='a'*64))
            staged('partition-fixture',[inventory],{})
            catalog=state.root/'data/source-audit.json'
            atomic_json(catalog,dict(sources=[dict(source={'name':'fixture.laz'},crop_sha256='a'*64,
                bounds=[[0,0,0],[2000,2000,2000]])]))
            staged('source-audit',[catalog],{})
            calls=[];fail=True
            def reconstruct(state,tile,native_dir,delta,revision):
                nonlocal fail
                # A durable plan and externally written pending snapshot must
                # already exist before any expensive geometry is attempted.
                self.assertEqual(state.task('regional-plan-r2')['state'],'durable')
                self.assertTrue(any(kind=='status' for kind,_,_ in remote.records))
                calls.append(tile);name=f'local-surface-{tile:04d}-d1p35-r2'
                iy,ix=divmod(tile,20);bounds=[ix*100,iy*100,(ix+1)*100,(iy+1)*100]
                recipe=dict(native_sha256=digest(native),python_implementation=digest(batch.local_trial.__file__),
                    clipping_implementation=digest(batch.reconstruct.__file__),halo_m=8,delta=1.35,
                    revision='r2',maximum_edge_m=2.,original_vertices=True)
                signature=key(dict(recipe=recipe,bounds=bounds,inputs={'fixture.laz':key('partition-fixture')},validation_schema=2))
                state.start(name,signature)
                if tile==190 and fail:
                    fail=False;state.fail(name,RuntimeError('simulated runtime loss'));raise RuntimeError('simulated runtime loss')
                path=state.root/'data'/f'{tile}.npz';atomic_bytes(path,b'measured geometry fixture')
                state.stage(name,signature,[path],dict(triangles=10,recipe=recipe,bounds=bounds))
            with patch.object(batch.local_trial,'process',reconstruct):
                with self.assertRaisesRegex(RuntimeError,'simulated runtime loss'):
                    batch.process(state,'native-fixture',limit=4)
                result=batch.process(State(directory,config,remote),'native-fixture',limit=1)
            self.assertEqual(calls,[189,190,190]);self.assertEqual(result['completed'],[189,190])
            self.assertEqual(len(result['pending']),398);self.assertEqual(result['failed'],[])
            self.assertFalse(result['publishable'])
            # A newer partition generation must not silently reuse old surfaces,
            # even though their files remain internally checksum-valid.
            state=State(directory,config,remote);signature=key('new partition generation')
            state.start('partition-fixture',signature);state.stage('partition-fixture',signature,[inventory],{})
            with self.assertRaisesRegex(ValueError,'another reconstruction generation'):
                batch.process(state,'native-fixture',limit=1)

    def test_centre_priority_covers_every_patch_once(self):
        tiles=batch.order(20,20)
        self.assertEqual(tiles[:4],[189,190,209,210])
        self.assertEqual(sorted(tiles),list(range(400)))


if __name__=='__main__':unittest.main()
