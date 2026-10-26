"""Native photo mosaic/grid and strict coverage guards, using small real TIFFs."""
from pathlib import Path
import tempfile,unittest,io
import numpy as np
import rasterio
from rasterio.transform import from_origin
from PIL import Image
from tools.lidar_pipeline.source_photos import compose,pixel_window,bounds_for,ANCHOR
class Photos(unittest.TestCase):
    def test_native_pixels_seams_alpha_and_grid(self):
        with tempfile.TemporaryDirectory() as directory:
            sources=[];expected=np.zeros((4,8,4),np.uint8);expected[:,:,:3]=np.arange(8)[None,:,None]*20;expected[:,:,3]=255
            for i in range(2):
                p=Path(directory)/f'{i}.tiff'
                with rasterio.open(p,'w',driver='GTiff',width=4,height=4,count=4,dtype='uint8',crs='EPSG:2193',
                    transform=from_origin(ANCHOR[0]+i*1.2,ANCHOR[1],.3,.3)) as ds:ds.write(expected[:,i*4:i*4+4].transpose(2,0,1))
                sources.append(dict(id=str(i),url=str(p)))
            bounds=[ANCHOR[0],ANCHOR[1]-1.2,ANCHOR[0]+2.4,ANCHOR[1]]
            png,report=compose(bounds,sources,False)
            self.assertTrue(np.array_equal(np.asarray(Image.open(io.BytesIO(png))),expected));self.assertFalse(report['resampled'])
            with self.assertRaisesRegex(ValueError,'uncovered'):compose(bounds,sources[:1],False)
            with rasterio.open(sources[0]['url'],'r+') as ds:
                pixels=ds.read();pixels[3,0,0]=0;ds.write(pixels)
            with self.assertRaisesRegex(ValueError,'non-opaque'):compose(bounds,sources,False)
    def test_real_roi_alignment(self):
        bounds=[1368289,5168127,1370289,5170127]
        window=pixel_window(bounds)
        self.assertEqual([window[2]-window[0],window[3]-window[1]],[6667,6667])
        np.testing.assert_allclose(bounds_for(window),[1368289,5168127,1370289.1,5170127.1],atol=1e-8)
if __name__=='__main__':unittest.main()
