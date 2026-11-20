package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PrintMapTest {
    // About 12 x 16 km around Aoraki / Mount Cook.
    private val area=TileLoader.Bounds(170.05,-43.70,170.20,-43.56)
    @Test fun a4MapReachesPrintResolutionWithinTheTileBudget(){
        val mapWidthPt=595.0-60
        val plan=PrintMap.plan(area,mapWidthPt,TOPO_MAX_ZOOM)
        val dpi=plan.width/(mapWidthPt/72)
        // A screen capture of the frame gave about 75 dpi.
        assertTrue("$dpi dpi",dpi>=150)
        assertTrue(plan.tiles.size<=480);assertTrue(plan.zoom<=TOPO_MAX_ZOOM)
        // Never finer than the tiles: at most 256 px per tile width.
        assertTrue(plan.width<=(plan.x1-plan.x0)*256*(1 shl plan.zoom)+1)
        // The map keeps the area's shape.
        assertEquals((plan.y1-plan.y0)/(plan.x1-plan.x0),plan.height.toDouble()/plan.width,.01)
        // Every tile overlaps the area.
        val n=(1 shl plan.zoom).toDouble()
        assertTrue(plan.tiles.all{(it.x+1)/n>plan.x0&&it.x/n<plan.x1&&(it.y+1)/n>plan.y0&&it.y/n<plan.y1})
    }
    @Test fun hugeAreasDropZoomToStayWithinBudget(){
        val big=TileLoader.Bounds(168.0,-46.0,172.0,-42.0)
        val plan=PrintMap.plan(big,535.0,19)
        assertTrue(plan.tiles.size<=480);assertTrue(plan.width.toLong()*plan.height<=12_000_000)
    }
}
