package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** NZ Topo stops at z17: LINZ's z18 printed-map tiles are its own 2x upscale. */
class TopoZoomTest {
    @Test fun closestTopoZoomUsesZ17TilesNotZ18(){
        val pose=CameraPose(170.1415,-43.5950,16.0,0f,0f,0f)
        val tiles=FlatSelection.plan(400,800,pose,128.0,128,TOPO_MAX_ZOOM,roundZoom=true)!!
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all{it.z==17})
        // Uncapped, the same view needs z18: over twice as many downloads (4x per area).
        val uncapped=FlatSelection.plan(400,800,pose,128.0,512,18,roundZoom=true)!!
        assertTrue(uncapped.all{it.z==18})
        assertTrue("${tiles.size} vs ${uncapped.size}",tiles.size*2<uncapped.size)
    }
}
