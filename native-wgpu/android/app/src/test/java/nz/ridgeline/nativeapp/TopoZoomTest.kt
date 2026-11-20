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
    @Test fun topoSwitchesToFinerTilesOneZoomLaterAndStillReachesZ17(){
        fun zoomAt(camera:Double)=FlatSelection.plan(400,800,CameraPose(170.1415,-43.5950,camera,0f,0f,0f),TOPO_TARGET_PX,512,TOPO_MAX_ZOOM,roundZoom=true)!!.maxOf{it.z}
        // Previously round(camera + 2); now round(camera + 1).
        assertEquals(13,zoomAt(12.0));assertEquals(13,zoomAt(12.4));assertEquals(14,zoomAt(12.6))
        assertEquals(17,zoomAt(16.0))
    }
}
