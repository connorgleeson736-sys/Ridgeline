import tempfile
import unittest
from tools.lidar_pipeline.state import State
from tools.lidar_pipeline.plan import create

class Plan(unittest.TestCase):
    def test_pending_expected_work_survives_manifest_and_uses_both_dimensions(self):
        with tempfile.TemporaryDirectory() as directory:
            s=State(directory,dict(bounds=[0,0,200,300],tile_size_m=100,lod_errors_m=[0,.2,2]))
            plan=create(s);self.assertEqual(len(plan['tasks']),18)
            before=s.manifest()['plan'];self.assertEqual(before['counts']['pending'],18)
            s.start('surface-0004','signature');after=s.manifest()['plan']
            self.assertEqual(after['counts']['pending'],17);self.assertEqual(after['counts']['running'],1)
            self.assertNotIn('surface-0004',after['pending'])

if __name__=='__main__':unittest.main()
