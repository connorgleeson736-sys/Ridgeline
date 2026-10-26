"""Real browser decode + decoded Khronos validation; source-photo boundary crop."""
import io,json,subprocess,tempfile
from pathlib import Path
import unittest
import numpy as np
from PIL import Image
from tools.lidar_pipeline.gltf_export import build
from tools.lidar_pipeline.imagery import bake
from tools.lidar_pipeline.state import atomic_json

class Export(unittest.TestCase):
    def test_full_xyz_glb_crossdecode_and_attribute_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            v=np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0],[0,0,1]],np.float32)
            f=np.array([[0,1,2],[0,2,3],[0,4,1]],np.uint32);uv=np.zeros((5,2),np.float32)
            b=io.BytesIO();Image.new('RGB',(2,2),'white').save(b,format='PNG')
            glb,r=build(v,f,uv,b.getvalue(),{});path=root/'fixture.glb';path.write_bytes(glb)
            options=root/'options.json';atomic_json(options,dict(glb=str(path),**r))
            verifier=Path(__file__).resolve().parents[1]/'tools/lidar-glb-check.mjs'
            result=subprocess.run(['node',str(verifier),str(options)],capture_output=True,text=True,check=True)
            checked=json.loads(result.stdout)
            self.assertTrue(checked['verified']);self.assertEqual(checked['khronos_decoded_issues']['numErrors'],0)
            self.assertEqual(checked['khronos_decoded_issues']['numWarnings'],0)
            self.assertLess(r['gpu_vertices'],r['unwelded_vertices'])
            r['image_sha256']='0'*64;atomic_json(options,dict(glb=str(path),**r))
            broken=subprocess.run(['node',str(verifier),str(options)],capture_output=True,text=True)
            self.assertNotEqual(broken.returncode,0);self.assertIn('imagery changed',broken.stderr)

    def test_photo_crops_use_measured_footprint_and_actual_neighbour_cores(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'photos').mkdir()
            meta=dict(gridSize=2049,worldCenter=[.5,.5],worldStep=1e-6,attribution='fixture')
            atomic_json(root/'region.json',meta)
            colors={0:(255,0,0),1:(0,255,0),16:(0,0,255),17:(255,255,255)}
            for tile,color in colors.items():Image.new('RGB',(388,388),color).save(root/'photos'/(str(tile)+'.webp'),lossless=True)
            px=np.array([[383.25,383.25],[384.75,384.75]])
            origin=np.array(meta['worldCenter'])-1024*meta['worldStep']
            world=origin+px/3*meta['worldStep']
            geo=np.column_stack([world[:,0]*360-180,np.rad2deg(np.arctan(np.sinh(np.pi*(1-2*world[:,1]))))])
            uv,png,report=bake(geo,root/'region.json')
            self.assertEqual(len(report['input_tiles']),4)
            im=np.asarray(Image.open(io.BytesIO(png)))
            self.assertTrue(np.array_equal(im[0,0],colors[0]));self.assertTrue(np.array_equal(im[-1,-1],colors[17]))
            self.assertGreater(float(uv.min()),0);self.assertLess(float(uv.max()),1)
            with self.assertRaisesRegex(ValueError,'outside authentic imagery'):
                bake(np.array([[170.,-43.]]),root/'region.json')

if __name__=='__main__':unittest.main()
