"""Full-XYZ clustering preserves every triangle and survives reload."""
import tempfile,unittest
import numpy as np
from tools.lidar_pipeline.state import State,atomic_npz,digest
from tools.lidar_pipeline.clusters import partition,process,extract
class Clusters(unittest.TestCase):
    def test_vertical_layers_and_centroid_ties_are_lossless(self):
        v=np.array([[0,0,0],[0,1,0],[0,1,1],[0,0,1],[0,0,2],[1,0,2]],np.float32)
        f=np.tile(np.array([[0,1,2],[0,2,3],[3,4,5]],np.uint32),(17,1))
        a,o,b=partition(v,f,5);again=partition(v,f,5)
        self.assertTrue(np.array_equal(a,again[0]));self.assertTrue(np.array_equal(np.sort(a),np.arange(len(f))))
        self.assertLessEqual(int(np.diff(o).max()),5)
        with tempfile.TemporaryDirectory() as directory:
            state=State(directory,{'fixture':'vertical-clusters'});source=state.root/'data/source.npz'
            atomic_npz(source,vertices=v,faces=f);saved=process(state,source,'fixture',5);ids=[]
            for i in range(saved['metrics']['clusters']):
                path=extract(state,'fixture',i);sha=digest(path)
                with np.load(path) as d:
                    self.assertTrue(np.array_equal(d['vertices'][d['faces']],v[f[d['original_face_ids']]]))
                    ids.extend(d['original_face_ids'].tolist())
                self.assertEqual(digest(extract(state,'fixture',i)),sha)
            self.assertEqual(sorted(ids),list(range(len(f))));self.assertEqual(process(state,source,'fixture',5),saved)
if __name__=='__main__':unittest.main()
