"""Compact progress stays fresh across independent workers and planned renders."""
import tempfile,unittest
from tools.lidar_pipeline.state import State,atomic_json,atomic_bytes,key
class Manifest(unittest.TestCase):
    def test_cross_worker_updates_and_large_histories_are_not_duplicated(self):
        with tempfile.TemporaryDirectory() as directory:
            a=State(directory,{'fixture':'manifest'});b=State(directory,a.config)
            atomic_json(a.root/'data/render-plans/patch.json',dict(tasks=['leaf-a','leaf-b']))
            a.start('leaf-a',key('a'));self.assertEqual(a.manifest()['render_plan']['counts']['running'],1)
            p=a.root/'data/leaf.bin';atomic_bytes(p,b'geometry')
            b.stage('leaf-a',key('a'),[p],dict(history=['large history']*10000))
            snapshot=a.manifest();self.assertEqual(snapshot['render_plan']['counts']['staged'],1)
            self.assertEqual(snapshot['render_plan']['pending'],['leaf-b'])
            self.assertNotIn('metrics',snapshot['tasks']['leaf-a'])
            self.assertLess((a.root/'progress.json').stat().st_size,2000)
            self.assertEqual(len(a.task('leaf-a')['metrics']['history']),10000)
if __name__=='__main__':unittest.main()
