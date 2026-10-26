"""Full-XYZ clipping and local-error simplification regression checks."""
import unittest
import numpy as np
import meshoptimizer as mo
from tools.lidar_pipeline.reconstruct import clip_core, scene, distances, boundary


class Geometry(unittest.TestCase):
    def test_clipping_retains_two_heights_at_same_xy(self):
        # Two sampled layers plus a vertical connection. A heightfield would
        # collapse this configuration; triangle clipping must preserve it.
        v=np.array([[-1,0,0],[2,0,0],[-1,2,0],[2,2,0],
                    [-1,0,1],[2,0,1],[-1,2,1],[2,2,1]],np.float32)
        f=np.array([[0,2,1],[1,2,3],[4,5,6],[5,7,6],[1,3,5],[5,3,7]],np.uint32)
        a,b=clip_core(v,f,[0,0,1,2])
        self.assertTrue(np.any(a[:,2]==0));self.assertTrue(np.any(a[:,2]==1))
        self.assertEqual(len(a),len(np.unique(a,axis=0)))
        self.assertTrue(np.all(a[:,0]>=0));self.assertTrue(np.all(a[:,0]<=1))
        q=np.array([[.5,.5,0],[.5,.5,1]],np.float32)
        self.assertLess(float(distances(scene(a,b),q).max()),1e-6)

    def test_local_simplification_uses_absolute_error(self):
        n=25
        y,x=np.mgrid[:n,:n].astype(np.float32)
        z=np.maximum(0,1-np.abs(x-12)/2)*1.5
        v=np.column_stack((x.ravel(),y.ravel(),z.ravel())).astype(np.float32)
        f=[]
        for row in range(n-1):
            for col in range(n-1):
                i=row*n+col;f.extend([[i,i+1,i+n],[i+1,i+n+1,i+n]])
        f=np.array(f,np.uint32);output=np.empty(f.size,np.uint32);error=np.zeros(1,np.float32)
        count=mo.simplify(output,f.ravel(),v,target_index_count=3,target_error=.025,
                          options=mo.SIMPLIFY_LOCK_BORDER|mo.SIMPLIFY_ERROR_ABSOLUTE,result_error=error)
        g=output[:count].reshape(-1,3)
        self.assertLess(len(g),len(f)/3)
        self.assertLessEqual(float(distances(scene(v,g),v).max()),.025+1e-4)
        # Whole-tile roughness is never used; this sharp ridge survives while
        # the planar areas either side simplify heavily.
        self.assertTrue(np.any(v[g,2]==1.5))
        self.assertEqual(boundary(v,f,[0,0,24,24])[2],boundary(v,g,[0,0,24,24])[2])


if __name__=='__main__':unittest.main()
