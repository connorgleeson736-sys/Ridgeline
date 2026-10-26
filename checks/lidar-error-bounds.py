import unittest
import numpy as np
from tools.lidar_pipeline.error_bounds import symmetric,corner_distances,collect


class Bounds(unittest.TestCase):
    def test_alternate_triangulations_have_same_surface(self):
        v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0]],np.float32)
        a=np.array([[0,1,2],[0,2,3]],np.uint32)
        b=np.array([[0,1,3],[1,2,3]],np.uint32)
        result=symmetric(v,a,v,b,.025,max_tests=100000)
        self.assertTrue(result['certified']);self.assertLessEqual(result['upper_bound_m'],.025)

    def test_a_geometric_displacement_is_rejected(self):
        v=np.array([[0,0,0],[1,0,0],[0,1,0]],np.float32);f=np.array([[0,1,2]],np.uint32)
        shifted=v.copy();shifted[:,2]=.1
        self.assertFalse(symmetric(v,f,shifted,f,.025,max_depth=2,max_tests=10000)['certified'])
        result=symmetric(v,f,shifted,f,.11,max_tests=10000)
        self.assertTrue(result['certified']);self.assertGreaterEqual(result['upper_bound_m'],.1)

    def test_convex_distance_bound_handles_vertical_and_overhang_faces(self):
        triangle=np.array([[[0,0,0],[0,1,0],[0,0,1]]],float)
        points=triangle+np.array([.025,0,0])
        self.assertTrue(np.allclose(corner_distances(points,triangle),.025))

    def test_collect_reports_disjoint_failed_regions_after_first_batch(self):
        v=np.array([[0,0,0],[1,0,0],[0,1,0],[4,0,0],[5,0,0],[4,1,0],[8,0,0],[9,0,0],[8,1,0]],np.float32)
        f=np.arange(9,dtype=np.uint32).reshape(-1,3);other=v.copy();other[:3,2]=.3;other[6:,2]=.3
        result=collect(v,f,other,f,.1,batch=1)
        self.assertFalse(result['certified']);self.assertEqual(result['unresolved_reference_faces'],[0,2])

    def test_collect_work_cap_never_accepts_unvisited_faces(self):
        v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0]],np.float32)
        f=np.array([[0,1,2],[0,2,3]],np.uint32)
        result=collect(v,f,v,f,.1,batch=1,max_tests=1)
        self.assertFalse(result['certified']);self.assertTrue(result['work_cap_reached'])
        self.assertEqual(result['unresolved_reference_faces'],[1])

    def test_collect_certifies_alternative_flat_triangulation(self):
        v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0]],np.float32)
        a=np.array([[0,1,2],[0,2,3]],np.uint32);b=np.array([[0,1,3],[1,2,3]],np.uint32)
        result=collect(v,a,v,b,.05,max_tests=100000)
        self.assertTrue(result['certified']);self.assertLessEqual(result['upper_bound_m'],.05)


if __name__=='__main__':unittest.main()
