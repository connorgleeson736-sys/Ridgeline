"""Resume reconstruction from durable selected shards without raw LAZ downloads."""
import json,tempfile,unittest
import numpy as np
from tools.lidar_pipeline.state import State,atomic_json,atomic_bytes,key
from tools.lidar_pipeline.partition import DTYPE
from tools.lidar_pipeline.reconstruct import gather

class Catalog(unittest.TestCase):
    def test_audited_catalog_and_shards_are_sufficient_and_corruption_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            config=dict(bounds=[0,0,100,100],origin=[0,0,0],tile_size_m=100,halo_m=8)
            state=State(directory,config)
            def save(name,paths):
                signature=key(name);state.stage(name,signature,paths)
                receipt=json.loads((state.root/'outbox'/(name+'-'+signature[:16]+'.json')).read_text())
                state.acknowledge(name,signature,'fixture',receipt['sha256'])
            source=dict(name='tile.laz')
            catalog=state.root/'data/source-audit.json'
            atomic_json(catalog,dict(sources=[dict(source=source,crop_sha256='source-generation',bounds=[[0,0,0],[100,100,20]])]))
            save('source-audit',[catalog])
            folder=state.root/'data/shards/tile';inventory=folder/'inventory.json'
            atomic_json(inventory,dict(source_sha256='source-generation'))
            points=np.zeros(2,DTYPE);points['position']=[[25,25,10],[75,75,20]]
            path=folder/'0000.bin';atomic_bytes(path,points.tobytes());save('partition-tile',[inventory,path])
            self.assertFalse((state.root/'data/raw').exists())
            self.assertFalse((state.root/'data/sources.json').exists())
            result,inputs=gather(state,[0,0,100,100]);self.assertTrue(np.array_equal(result,points['position']))
            self.assertIn('tile.laz',inputs)
            catalog.write_text('{}')
            with self.assertRaisesRegex(ValueError,'Source audit catalog'):gather(state,[0,0,100,100])
if __name__=='__main__':unittest.main()
