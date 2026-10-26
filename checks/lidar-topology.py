import unittest
import numpy as np
from tools.lidar_pipeline.topology import split_fans

class Topology(unittest.TestCase):
    def test_pinched_fans_keep_identical_triangle_geometry(self):
        v=np.array([[0,0,0],[1,0,0],[0,1,0],[-1,0,1],[0,-1,1]],np.float32)
        f=np.array([[0,1,2],[0,3,4]],np.uint32)
        out,faces,_,report=split_fans(v,f)
        self.assertEqual(report['split_vertices'],1)
        np.testing.assert_array_equal(out[faces],v[f])

    def test_connected_surface_is_unchanged(self):
        v=np.array([[0,0,0],[1,0,0],[1,1,1],[0,1,1]],np.float32)
        f=np.array([[0,1,2],[0,2,3]],np.uint32)
        out,faces,_,report=split_fans(v,f)
        self.assertEqual(report['split_vertices'],0)
        np.testing.assert_array_equal(out,v);np.testing.assert_array_equal(faces,f)

if __name__=='__main__':unittest.main()
