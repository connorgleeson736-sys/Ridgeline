"""Global grid is seam-consistent across different chunk origins and bounded."""
import io,json,subprocess,tempfile,unittest
from pathlib import Path
import numpy as np
from PIL import Image
from tools.lidar_pipeline.gltf_export import build
from tools.lidar_pipeline.quantization import compact,positions_on_grid,POSITION_STEP
from tools.lidar_pipeline.state import atomic_json
class Quantization(unittest.TestCase):
    def test_shared_points_decode_identically_from_different_chunk_origins(self):
        shared=np.array([[-999.0012,1200.0002,-900.0001]],np.float32)
        a=np.r_[shared,[[-1008.,1190.,-910.]]];b=np.r_[shared,[[-992.,1210.,-890.]]]
        qa,oa,da=positions_on_grid(a);qb,ob,db=positions_on_grid(b)
        self.assertFalse(np.array_equal(oa,ob));self.assertTrue(np.array_equal(da[0],db[0]))
        self.assertTrue(np.array_equal(qa.astype(float)*POSITION_STEP+oa,da))
        self.assertLessEqual(float(np.linalg.norm(da-a,axis=1).max()),np.sqrt(3)*POSITION_STEP/2+1e-12)
        with self.assertRaisesRegex(ValueError,'exceeds Uint16'):positions_on_grid(np.array([[0,0,0],[40,0,0]]))

    def test_real_decoder_and_khronos_validate_compact_geometry_and_visual_attributes(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            v=np.array([[-1000.0001,1200.0002,-900.0001],[-999.0001,1200.0002,-900.0001],[-999.0001,1201.0002,-900.0001],[-1000.0001,1201.0002,-900.0001]],np.float32)
            f=np.array([[0,1,2],[0,2,3]],np.uint32);uv=np.array([[.1,.2],[.9,.2],[.9,.8],[.1,.8]],np.float32)
            b=io.BytesIO();Image.new('RGB',(2,2),'white').save(b,format='PNG')
            g,r=build(v,f,uv,b.getvalue(),dict(ridgeline=dict(lod=dict(certified=True,upper_bound_m=0.))))
            g,r=compact(g,r,POSITION_STEP,extra_error_m=.00005)
            self.assertLess(r['packed_world_bound']['upper_bound_m'],.0005)
            self.assertGreater(r['packed_world_bound']['upper_bound_m'],0)
            self.assertLess(r['display_quantization']['normal_max_angle_degrees'],.002)
            self.assertLessEqual(max(r['display_quantization']['uv_max_error_normalized']),.5/65535+1e-12)
            self.assertLess(r['gpu_attribute_bytes'],r['gpu_vertices']*32)
            (p/'fixture.glb').write_bytes(g);atomic_json(p/'options.json',dict(glb=str(p/'fixture.glb'),**r))
            verifier=Path(__file__).resolve().parents[1]/'tools/lidar-glb-check.mjs'
            result=subprocess.run(['node',str(verifier),str(p/'options.json')],capture_output=True,text=True,check=True)
            checked=json.loads(result.stdout);self.assertTrue(checked['verified'])
            self.assertEqual(checked['khronos_decoded_issues']['numErrors'],0)
            self.assertEqual(checked['khronos_decoded_issues']['numWarnings'],0)
if __name__=='__main__':unittest.main()
