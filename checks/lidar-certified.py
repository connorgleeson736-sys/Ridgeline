"""Exercise actual WASM, both continuous bounds and protected divider facets."""
from pathlib import Path
import tempfile
import unittest
import numpy as np
from tools.lidar_pipeline.state import State,atomic_npz
from tools.lidar_pipeline.certified_lod import run,divider_faces,face_keys

class Certified(unittest.TestCase):
    def test_two_intersecting_sheets_reduce_without_moving_the_dividers(self):
        with tempfile.TemporaryDirectory() as directory:
            state=State(directory,{'fixture':'intersecting-sheets'})
            n=16;x,y=np.meshgrid(np.linspace(0,8,n),np.linspace(0,8,n))
            a=np.column_stack([x.ravel(),y.ravel(),(.03*np.sin(x)*np.sin(y)).ravel()]).astype(np.float32)
            b=np.column_stack([x.ravel(),np.full(x.size,4),y.ravel()-4]).astype(np.float32)
            faces=[]
            for row in range(n-1):
                for column in range(n-1):
                    k=row*n+column;faces.extend([[k,k+1,k+n],[k+1,k+n+1,k+n]])
            f=np.asarray(faces,np.uint32);f=np.r_[f,f+len(a)];v=np.r_[a,b]
            source=state.root/'data/source.npz';atomic_npz(source,vertices=v,faces=f)
            report=run(state,source,[0,0,8,8],.2,'sheets',attempts=4)
            self.assertTrue(report['candidate_accepted']);self.assertTrue(report['continuous_bound']['certified'])
            self.assertLess(report['triangles'],len(f));self.assertLessEqual(report['continuous_bound']['upper_bound_m'],.2)
            task=state.task('certified-lod-sheets-0p2');self.assertTrue(state.valid(task['name'],task['signature']))
            with np.load(state.root/'data/certified'/task['name']/'mesh.npz') as d:
                # Compare coordinates since the accepted mesh compacts indices.
                original=v[f[divider_faces(v,f,[0,0,8,8])]]
                result=d['vertices'][d['faces']]
            def keys(t):return {tuple(sorted(map(tuple,face))) for face in t}
            self.assertTrue(keys(original).issubset(keys(result)))
            cached=run(state,source,[0,0,8,8],.2,'sheets',attempts=4)
            self.assertEqual(cached,report)

if __name__=='__main__':unittest.main()
