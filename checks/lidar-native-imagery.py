"""Keep exact native pixels across windows and reject missing or corrupt data."""
import io
from pathlib import Path
import tempfile
import unittest
import numpy as np
from PIL import Image
from tools.lidar_pipeline.native_imagery import compose
from tools.lidar_pipeline.state import digest


class NativeImagery(unittest.TestCase):
    def fixture(self,directory):
        pixels=np.zeros((4,8,4),np.uint8)
        pixels[:,:,:3]=np.arange(32).reshape(4,8,1)*7;pixels[:,:,3]=255
        sources=[]
        for i,(left,right) in enumerate([(0,5),(4,8)]):
            path=Path(directory)/f'{i}.png';Image.fromarray(pixels[:,left:right]).save(path)
            sources.append(dict(path=path,sha256=digest(path),window=[left,0,right,4],task=f'photo-{i}',signature=str(i)))
        return pixels,sources

    def test_exact_pixels_across_overlap_and_bounded_crop(self):
        with tempfile.TemporaryDirectory() as d:
            pixels,sources=self.fixture(d)
            png,used=compose([2,1,7,4],sources)
            np.testing.assert_array_equal(np.asarray(Image.open(io.BytesIO(png))),pixels[1:4,2:7,:3])
            self.assertEqual(len(used),2)
            with self.assertRaisesRegex(ValueError,'Missing native'):
                compose([2,1,7,4],sources[:1])
            with self.assertRaisesRegex(ValueError,'bounded'):
                compose([0,0,2049,2],sources)

    def test_corrupt_hash_and_disagreeing_overlap_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            pixels,sources=self.fixture(d)
            path=sources[1]['path'];wrong=pixels[:,4:8].copy();wrong[0,0,0]^=1
            Image.fromarray(wrong).save(path)
            with self.assertRaisesRegex(ValueError,'SHA changed'):
                compose([0,0,8,4],sources)
            sources[1]['sha256']=digest(path)
            with self.assertRaisesRegex(ValueError,'overlap disagrees'):
                compose([0,0,8,4],sources)


if __name__=='__main__':unittest.main()
