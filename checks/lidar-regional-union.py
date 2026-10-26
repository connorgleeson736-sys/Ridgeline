import unittest
import numpy as np
from tools.lidar_pipeline.regional_union import merge
class Union(unittest.TestCase):
    def test_exact_divider_geometry_has_one_owner_and_all_input_witnesses(self):
        west=np.array([[1,0,0],[1,1,0],[1,0,1],[0,0,0]],np.float32)
        east=np.array([[1,0,0],[1,1,0],[1,0,1],[2,0,0]],np.float32)
        wf=np.array([[0,1,2],[0,2,3]],np.uint32);ef=np.array([[2,1,0],[0,3,2]],np.uint32)
        arrays,report=merge([(1,west,wf),(2,east,ef)],[(0,1)])
        self.assertEqual(report['input_triangles'],4);self.assertEqual(report['parent_triangles'],3)
        self.assertEqual(report['duplicate_divider_triangles_removed'],1)
        self.assertEqual(arrays['input_face_to_parent'][0],arrays['input_face_to_parent'][2])
        self.assertEqual(arrays['owner_patch'][arrays['input_face_to_parent'][0]],1)
        self.assertFalse(report['vertices_moved']);self.assertTrue(report['every_input_triangle_has_exact_parent_witness'])
    def test_unexplained_overlap_cannot_be_silently_removed(self):
        v=np.array([[0,0,0],[1,0,0],[0,1,0]],np.float32);f=np.array([[0,1,2]],np.uint32)
        with self.assertRaisesRegex(ValueError,'away from'):merge([(1,v,f),(2,v,f)],[(0,2)])
    def test_nearby_distinct_measurements_are_never_tolerance_welded(self):
        v=np.array([[0,0,0],[1,0,0],[0,1,0]],np.float32);f=np.array([[0,1,2]],np.uint32)
        arrays,report=merge([(1,v,f),(2,v+np.float32(.00001),f)],[])
        self.assertEqual(report['parent_triangles'],2);self.assertEqual(len(arrays['vertices']),6)
if __name__=='__main__':unittest.main()
