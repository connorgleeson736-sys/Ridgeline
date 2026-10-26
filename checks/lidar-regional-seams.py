"""A shared endpoint set is insufficient: compare original full facet sets."""
import unittest
import numpy as np
from tools.lidar_pipeline.regional_seams import canonical,pairs


class Seams(unittest.TestCase):
    def test_order_and_winding_ignored_but_diagonal_and_multiplicity_preserved(self):
        v=np.array([[0,0,0],[0,1,0],[0,1,1],[0,0,1]],np.float32)
        a=v[[[0,1,2],[0,2,3]]];b=a[::-1,::-1]
        np.testing.assert_array_equal(canonical(a),canonical(b))
        # Same four endpoints, different actual triangulation must fail.
        c=v[[[0,1,3],[1,2,3]]]
        self.assertFalse(np.array_equal(canonical(a),canonical(c)))
        self.assertFalse(np.array_equal(canonical(a),canonical(np.r_[a,a[:1]])))
        self.assertEqual(canonical(np.zeros((0,3,3))).shape,(0,9))

    def test_grid_adjacency_never_wraps_rows(self):
        self.assertEqual(pairs([19,20,39,40],20),[(19,39,1),(20,40,1)])


if __name__=='__main__':unittest.main()
