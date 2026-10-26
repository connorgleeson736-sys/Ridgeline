"""Exercise the real pinned native binary on horizontal/vertical/sloped planes."""
import os,tempfile,subprocess,unittest
from pathlib import Path
import numpy as np
BINARY=os.environ.get('LIDAR_LOCAL_BINARY')
@unittest.skipUnless(BINARY,'Set LIDAR_LOCAL_BINARY to the built local-ballmerge binary')
class Planar(unittest.TestCase):
    def run_mesh(self,points):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'points';out=Path(directory)/'triangles';points.astype('<f4').tofile(p)
            result=subprocess.run([BINARY,str(p),str(out),'2','1.35'],capture_output=True,text=True)
            return result,np.fromfile(out,'<u4').reshape(-1,3) if out.exists() else None
    def test_exact_planes_keep_original_xyz_in_all_orientations(self):
        x,y=np.meshgrid(np.arange(8,dtype=np.float32)*.25,np.arange(8,dtype=np.float32)*.25)
        for points in [np.c_[x.ravel(),y.ravel(),np.zeros(x.size)],np.c_[np.zeros(x.size),x.ravel(),y.ravel()],np.c_[x.ravel(),y.ravel(),(x+y).ravel()]]:
            result,f=self.run_mesh(points);self.assertEqual(result.returncode,0,result.stderr);self.assertIn('Intrinsic dimension: 2',result.stderr)
            self.assertEqual(len(f),98);self.assertEqual(len(np.unique(f)),len(points));self.assertLess(f.max(),len(points))
            self.assertLessEqual(np.linalg.norm(points[f]-points[np.roll(f,1,axis=1)],axis=2).max(),2)
    def test_no_long_bridge_across_unobserved_planar_gap(self):
        x,y=np.meshgrid(np.arange(3,dtype=np.float32)*.25,np.arange(3,dtype=np.float32)*.25)
        a=np.c_[x.ravel(),y.ravel(),np.zeros(x.size)];points=np.r_[a,a+[6,0,0]]
        result,f=self.run_mesh(points);self.assertEqual(result.returncode,0,result.stderr)
        self.assertTrue(np.all((f<9).all(1)|(f>=9).all(1)))
    def test_line_only_observations_do_not_create_fake_surface(self):
        result,f=self.run_mesh(np.c_[np.arange(8)*.25,np.zeros((8,2))]);self.assertNotEqual(result.returncode,0)
        self.assertIn('Point/line-only',result.stderr)
if __name__=='__main__':unittest.main()
