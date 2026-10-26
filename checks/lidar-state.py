"""Recovery tests: checksums, interrupted writes, unsafe archives, durable barrier."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import tarfile
import io
from tools.lidar_pipeline.state import State, atomic_bytes, digest, key, restore


class Recovery(unittest.TestCase):
    def test_checkpoint_roundtrip_and_corruption(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'run'; state=State(root, {'version':1})
            output=root/'data'/'observations.bin';atomic_bytes(output,b'measured points')
            signature=key({'input':'authentic'})
            state.start('tile',signature)
            receipt=state.stage('tile',signature,[output],{'points':1})
            self.assertTrue(state.valid('tile',signature))
            self.assertEqual(state.task('tile')['state'],'staged')
            with self.assertRaises(ValueError): state.acknowledge('tile',signature,'remote','wrong')
            sha=digest(receipt['archive'])
            state.acknowledge('tile',signature,'remote',sha)
            self.assertEqual(state.task('tile')['state'],'durable')
            recovered=Path(d)/'recovered'
            restore(receipt['archive'],recovered,receipt['sha256'])
            self.assertEqual((recovered/'data'/'observations.bin').read_bytes(), b'measured points')
            # Interrupted temporary writes do not replace the committed artifact.
            output.with_suffix('.tmp').write_bytes(b'interrupted')
            self.assertTrue(state.valid('tile',signature))
            output.write_bytes(b'corruption')
            self.assertFalse(state.valid('tile',signature))
            self.assertFalse(state.valid('tile',key({'input':'changed'})))
            with self.assertRaises(ValueError): restore(receipt['archive'],recovered,'0'*64)

    def test_task_failure_is_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            s=State(d,{'version':1});s.start('job','a');s.fail('job',RuntimeError('test interruption'))
            self.assertEqual(s.manifest()['counts']['failed'],1)
            self.assertFalse(s.valid('job','a'))

    def test_configuration_generation_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            State(d,{'version':1})
            with self.assertRaises(ValueError):State(d,{'version':2})

    def test_bad_artifact_rejected_before_any_existing_file_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            source=State(Path(directory)/'source',{'version':1});p=source.root/'data/points.bin'
            atomic_bytes(p,b'authentic points');receipt=source.stage('tile',key('input'),[p])
            damaged=Path(directory)/'damaged.tar'
            with tarfile.open(receipt['archive']) as original,tarfile.open(damaged,'w') as output:
                for member in original.getmembers():
                    data=original.extractfile(member).read()
                    if member.name=='data/points.bin':data=b'corrupt payload!'
                    member.size=len(data);output.addfile(member,io.BytesIO(data))
            target=State(Path(directory)/'target',{'version':1});prior=target.root/'data/points.bin'
            atomic_bytes(prior,b'previous committed result')
            with self.assertRaises(ValueError):restore(damaged,target.root,digest(damaged))
            self.assertEqual(prior.read_bytes(),b'previous committed result')
            self.assertFalse((target.root/'tasks/tile.json').exists())


if __name__=='__main__': unittest.main()
