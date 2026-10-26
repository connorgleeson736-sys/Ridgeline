"""3D Tiles axes/frame consistency, full coverage and safe private asset refs."""
import copy,unittest
import numpy as np
from tools.lidar_pipeline.hierarchy import assemble
class Hierarchy(unittest.TestCase):
    def test_replace_tree_coverage_and_frame_gates(self):
        frame={'enu_to_ecef_column_major':np.eye(4).flatten(order='F').tolist()}
        def report(sha,n,lo,hi,error):return dict(triangles=n,coordinate_frame=frame,enu_min=lo,enu_max=hi,
            coordinate_rounding_max_m=.00008,
            geometry_unchanged_by_packing=True,triangle_topology_preserved=True,finest_reference=True,validation={'verified':True},
            continuous_world_bound={'certified':True,'upper_bound_m':error},asset={'sha256':sha*64,'verified':True})
        coarse=report('a',2,[0,0,0],[2,2,2],2.005)
        leaves=[report('b',3,[0,0,0],[1,1,1],0),report('c',4,[1,1,1],[2,2,2],0)]
        t=assemble(coarse,leaves,7);self.assertEqual(t['asset']['version'],'1.1')
        self.assertEqual(t['root']['refine'],'REPLACE');self.assertEqual(len(t['root']['children']),2)
        self.assertGreaterEqual(t['root']['geometricError'],2.005+.00008)
        self.assertEqual(t['extras']['ridgeline']['reference_float32_rounding_max_m'],.00008)
        for child in t['root']['children']:
            self.assertTrue(child['content']['uri'].startswith('/api/lidar/files/'))
            self.assertNotIn('transform',child)
        with self.assertRaisesRegex(ValueError,'coverage'):assemble(coarse,leaves,8)
        bad=copy.deepcopy(leaves);bad[0]['coordinate_frame']={'other':True}
        with self.assertRaisesRegex(ValueError,'Mixed geographic'):assemble(coarse,bad,7)
        bad=copy.deepcopy(leaves);bad[0]['asset']['verified']=False
        with self.assertRaisesRegex(ValueError,'Verified persistent'):assemble(coarse,bad,7)
        middle=report('d',4,[0,0,0],[2,2,2],.602)
        t=assemble(coarse,leaves,7,middle)
        self.assertEqual(len(t['root']['children']),1)
        self.assertEqual(len(t['root']['children'][0]['children']),2)
        self.assertLess(t['root']['children'][0]['geometricError'],t['root']['geometricError'])
        middle['continuous_world_bound']['upper_bound_m']=3
        with self.assertRaisesRegex(ValueError,'must decrease'):assemble(coarse,leaves,7,middle)
if __name__=='__main__':unittest.main()
