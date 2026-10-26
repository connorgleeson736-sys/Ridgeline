import unittest
import numpy as np
from tools.lidar_pipeline.seams import compare

class Seams(unittest.TestCase):
    def test_orientation_does_not_change_shared_edge(self):
        a=np.array([[[1,2,3],[1,4,5]],[[1,4,5],[1,7,9]]],np.float32)
        self.assertTrue(compare(a,a[::-1,::-1])['accepted'])

    def test_opposing_diagonals_are_not_the_same_edge(self):
        a=np.array([[[0,0,0],[0,1,1]]],np.float32)
        b=np.array([[[0,0,1],[0,1,0]]],np.float32)
        self.assertFalse(compare(a,b)['accepted'])

if __name__=='__main__':unittest.main()
