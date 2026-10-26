"""Lossless native/browser-codec crosscheck on vertical, multilayer geometry."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import numpy as np
from tools.lidar_pipeline.state import State,atomic_npz,atomic_json
from tools.lidar_pipeline.mesh_codec import process

class Codec(unittest.TestCase):
    def test_native_and_real_browser_decoder_preserve_positions_and_winding(self):
        with tempfile.TemporaryDirectory() as directory:
            state=State(directory,{'fixture':'full-xyz'})
            v=np.array([[0,0,0],[0,1,0],[0,1,1],[0,0,1],[0,0,2],[1,0,2],[1,1,2]],np.float32)
            f=np.array([[2,0,1],[3,0,2],[6,4,5]],np.uint32)
            source=state.root/'data/source.npz';atomic_npz(source,vertices=v,faces=f)
            saved=process(state,source,'fixture');r=saved['metrics']
            self.assertTrue(r['position_bits_identical']);self.assertEqual(r['additional_geometric_error_m'],0.)
            self.assertTrue(r['browser_codec_node']['verified']);self.assertTrue(state.valid(saved['name'],saved['signature']))
            self.assertEqual(process(state,source,'fixture'),saved)
            p=state.root/'data/codec/fixture';d=json.loads((p/'geometry.meshopt.json').read_text())
            d['position_sha256']='0'*64;atomic_json(p/'incorrect.json',d)
            atomic_json(p/'verify-options.json',dict(description=str(p/'incorrect.json'),buffer=str(p/'geometry.meshopt.bin')))
            verifier=Path(__file__).resolve().parents[1]/'tools/lidar-decode-check.mjs'
            result=subprocess.run(['node',str(verifier),str(p/'verify-options.json')],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertIn('Position bytes changed',result.stderr)

if __name__=='__main__':unittest.main()
