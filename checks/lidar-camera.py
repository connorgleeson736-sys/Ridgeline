import unittest
import numpy as np
from tools.lidar_pipeline.inspection_camera import select
class Camera(unittest.TestCase):
    def test_visible_measured_vertical_wall_and_clearance(self):
        v=np.array([[0,0,0],[0,30,0],[0,30,30],[0,0,30]],np.float32)
        f=np.array([[0,1,2],[0,2,3]],np.uint32)
        result=select(v,f)
        self.assertGreaterEqual(result['camera_clearance_m'],5)
        self.assertLess(result['first_hit_target_difference_m'],.1)
        self.assertAlmostEqual(result['source_slope_degrees'],90)
        self.assertIn(result['visible_source_face'],[0,1])
    def test_no_fabricated_cliff_camera_on_flat_data(self):
        with self.assertRaisesRegex(ValueError,'No supported steep'):
            select(np.array([[0,0,0],[30,0,0],[0,30,0]],np.float32),np.array([[0,1,2]],np.uint32))
if __name__=='__main__':unittest.main()
