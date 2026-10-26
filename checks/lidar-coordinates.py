"""Grid hard-fail, independent vertical sampling, ECEF and glTF axis contracts."""
import json,os
from pathlib import Path
import tempfile
import unittest
import numpy as np
from tools.lidar_pipeline.coordinates import Frame,GRID_NAME

CONFIG=json.loads((Path(__file__).resolve().parents[1]/'tools/lidar_pipeline/aoraki.json').read_text())
GRID=os.environ.get('LIDAR_TEST_GRID')

class Coordinates(unittest.TestCase):
    def test_unverified_grid_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/GRID_NAME;p.write_bytes(b'not the authoritative grid')
            with self.assertRaisesRegex(ValueError,'verified NZGeoid2016'):Frame(CONFIG,p)

    @unittest.skipUnless(GRID,'Set LIDAR_TEST_GRID to the hash-verified official grid for integration checks')
    def test_geoid_sign_roundtrip_projection_and_earth_frame(self):
        frame=Frame(CONFIG,GRID);m=frame.metadata
        self.assertLess(m['independent_geoid_max_error_m'],1e-5)
        self.assertLess(m['vertical_roundtrip_max_error_m'],.001)
        self.assertAlmostEqual(m['geoid_corrections_at_corners_center_m'][-1],13.7373110961,places=7)
        origin=np.asarray(m['anchor_nztm_nzvd'])-np.asarray(CONFIG['origin'])
        localized=np.array([origin,origin+[1,0,0],origin+[0,1,0],origin+[0,0,10]])
        enu=frame.to_enu(localized)
        self.assertLess(np.linalg.norm(enu[0]),1e-7)
        self.assertAlmostEqual(enu[-1,2],10,places=6)
        # NZTM grid north is not true north; a mere origin translation is wrong.
        self.assertGreater(abs(enu[1,1]),.01)
        matrix=np.asarray(frame.transform).reshape(4,4,order='F')
        world=np.column_stack([enu,np.ones(len(enu))])@matrix.T
        llh=frame.to_geographic(localized+np.asarray(CONFIG['origin']))
        expected=np.column_stack(frame.ecef.transform(*llh.T,errcheck=True))
        self.assertTrue(np.allclose(world[:,:3],expected,rtol=0,atol=1e-8))
        gltf=frame.to_gltf(enu)
        self.assertTrue(np.array_equal(frame.from_gltf(gltf),enu))
        # The 3D Tiles runtime's +90deg X glTF axis correction.
        runtime=np.column_stack([gltf[:,0],-gltf[:,2],gltf[:,1]])
        self.assertTrue(np.array_equal(runtime,enu))

if __name__=='__main__':unittest.main()
