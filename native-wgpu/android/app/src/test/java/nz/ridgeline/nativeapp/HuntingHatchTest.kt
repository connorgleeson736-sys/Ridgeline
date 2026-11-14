package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HuntingHatchTest{
    private fun ring(x0:Double,y0:Double,x1:Double,y1:Double)=listOf(VectorTile.Point(x0,y0),VectorTile.Point(x1,y0),VectorTile.Point(x1,y1),VectorTile.Point(x0,y1),VectorTile.Point(x0,y0))
    private val area=HuntingAreas.Area(1,"Block","","","",0.0,listOf(ring(.1,.1,.3,.3),ring(.15,.15,.25,.25)))
    private fun len(p:List<VectorTile.Point>)=hypot(p[1].x-p[0].x,p[1].y-p[0].y)
    @Test fun hatchingFillsTheAreaAndLeavesItsHoleClear(){
        val s=.004;val lines=HuntingAreas.hatch(area,doubleArrayOf(0.0,0.0,1.0,1.0),s)
        assertTrue(lines.isNotEmpty())
        for(l in lines){assertEquals(2,l.size)
            for(f in listOf(.02,.5,.98)){val x=l[0].x+(l[1].x-l[0].x)*f;val y=l[0].y+(l[1].y-l[0].y)*f
                assertTrue("inside the area",area.contains(x,y))}}
        // Lines x+y=k·s are s/√2 apart: their length times that spacing is the area (0.04 − 0.01).
        assertEquals(.03,lines.sumOf(::len)*s/sqrt(2.0),.0015)
    }
    @Test fun hatchingStaysInItsTileAndContinuesAcrossTiles(){
        val s=.004
        val left=HuntingAreas.hatch(area,doubleArrayOf(0.0,0.0,.2,1.0),s);val right=HuntingAreas.hatch(area,doubleArrayOf(.2,0.0,1.0,1.0),s)
        for(l in left)for(p in l)assertTrue(p.x<=.2+1e-12)
        for(l in right)for(p in l)assertTrue(p.x>=.2-1e-12)
        val whole=HuntingAreas.hatch(area,doubleArrayOf(0.0,0.0,1.0,1.0),s)
        assertEquals(whole.sumOf(::len),left.sumOf(::len)+right.sumOf(::len),1e-9)
    }
    @Test fun anAreaOutsideTheTileGivesNothing(){
        assertTrue(HuntingAreas.hatch(area,doubleArrayOf(.5,.5,.6,.6),.004).isEmpty())
    }
}
