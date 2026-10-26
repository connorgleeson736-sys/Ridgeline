"""Exercise the actual WASM proposal, geometric gates and final checkpoint."""
import json
from pathlib import Path
import tempfile
import unittest
import numpy as np
from tools.lidar_pipeline.state import State,atomic_npz
from tools.lidar_pipeline.iterative_lod import run

class Feedback(unittest.TestCase):
    def test_sharp_ridge_reduction_and_serializable_final_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            state=State(directory,{'fixture':'ridge'})
            n=24;x,y=np.meshgrid(np.linspace(0,8,n),np.linspace(0,8,n))
            z=np.maximum(x-4,0)*2+.05*np.sin(x*3)*np.sin(y*3)
            v=np.column_stack([x.ravel(),y.ravel(),z.ravel()]).astype(np.float32)
            faces=[]
            for row in range(n-1):
                for column in range(n-1):
                    a=row*n+column;faces.extend([[a,a+1,a+n],[a+1,a+n+1,a+n]])
            f=np.array(faces,np.uint32);source=state.root/'data/source.npz'
            atomic_npz(source,vertices=v,faces=f)
            report=run(state,source,.2,'ridge',attempts=4)
            self.assertTrue(report['accepted']);self.assertTrue(report['candidate_accepted'])
            self.assertLess(report['triangles'],len(f))
            self.assertTrue(report['exact_open_borders'])
            task=state.task('feedback-lod-ridge-0p2')
            self.assertTrue(state.valid(task['name'],task['signature']))
            saved=json.loads((state.root/'data/feedback'/task['name']/'validation.json').read_text())
            self.assertEqual(saved['triangles'],report['triangles'])
            self.assertTrue(saved['attempts'][-1]['validation']['accepted'])

if __name__=='__main__':unittest.main()
