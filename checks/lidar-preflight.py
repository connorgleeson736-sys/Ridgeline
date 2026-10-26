import unittest
import numpy as np
from tools.lidar_pipeline.regional_preflight import counts
class Counts(unittest.TestCase):
    def test_inclusive_edges_and_halos_match_exact_brute_force(self):
        rng=np.random.default_rng(234)
        points=np.r_[rng.uniform([0,0,0],[200,300,10],(1000,3)),[[0,0,0],[100,100,1],[200,300,2],[92,108,3],[92,108,3],[108,92,4]]].astype(np.float32)
        for halo in [0,8]:
            actual=counts(points,2,3,100,halo);expected=[]
            for iy in range(3):
                for ix in range(2):
                    p=points;expected.append(((p[:,0]>=ix*100-halo)&(p[:,0]<=(ix+1)*100+halo)&(p[:,1]>=iy*100-halo)&(p[:,1]<=(iy+1)*100+halo)).sum())
            np.testing.assert_array_equal(actual,expected)
    def test_invalid_domain_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'domain'):counts(np.array([[-.1,0,0]]),2,2,100,8)
if __name__=='__main__':unittest.main()
