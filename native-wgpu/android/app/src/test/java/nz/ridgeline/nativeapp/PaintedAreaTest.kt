package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PaintedAreaTest {
    private val cell=1.0/PaintedArea.GRID
    // Mount Cook village area, in Mercator.
    private val mx=(170.1+180)/360;private val my=CameraMath.mercatorY(-43.73)
    @Test fun strokesPaintOverwriteEraseAndUndo(){
        val a=PaintedArea()
        a.begin();a.dab(mx,my,cell*3,0);a.end()
        val painted=a.size;assertTrue(painted in 20..40)
        a.begin();a.dab(mx,my,cell*1.2,3);a.end()
        assertEquals(painted,a.size);assertTrue(a.snapshot().values.count{it==3} in 3..9)
        a.begin();a.dab(mx,my,cell*1.2,-1);a.end()
        assertTrue(a.size<painted)
        assertTrue(a.undo());assertEquals(painted,a.size)
        assertTrue(a.undo());assertTrue(a.snapshot().values.all{it==0})
        assertTrue(a.undo());assertTrue(a.isEmpty());assertFalse(a.undo())
    }
    @Test fun aCancelledStrokeLeavesNoTraceAndATinyBrushStillPaints(){
        val a=PaintedArea()
        a.begin();a.dab(mx,my,cell*5,2);a.cancel()
        assertTrue(a.isEmpty());assertFalse(a.canUndo())
        a.begin();a.dab(mx,my,cell*.01,2);a.end();assertEquals(1,a.size)
    }
    @Test fun tilesCoverEveryPaintedCellDownToItsDetail(){
        val a=PaintedArea();a.begin();a.dab(mx,my,cell*.01,3);a.end()  // one cell at Maximum (z17)
        val tiles=a.tiles()
        // Zooms 0..16 one tile each, then the cell's four z17 children.
        assertEquals(17+4,tiles.size);assertEquals((0..17).toList(),tiles.map{it.z}.distinct())
        val c=tiles.single{it.z==16}
        assertEquals(setOf(c.x*2 to c.y*2,c.x*2+1 to c.y*2,c.x*2 to c.y*2+1,c.x*2+1 to c.y*2+1),tiles.filter{it.z==17}.map{it.x to it.y}.toSet())
        val b=PaintedArea();b.begin();b.dab(mx,my,cell*3,0);b.end()  // Overview (z13) only
        assertTrue(b.tiles().all{it.z<=13});assertTrue(b.tiles().count{it.z==13}<=4)
    }
    @Test fun encodeRoundTripsAndRejectsGarbage(){
        val a=PaintedArea();a.begin();a.dab(mx,my,cell*6,1);a.dab(mx+cell*20,my,cell*2,3);a.end()
        val text=a.encode();assertEquals(a.snapshot(),PaintedArea.decode(text))
        assertTrue(text.length<a.size*3)
        assertNull(PaintedArea.decode("not base64 gzip"))
    }
    @Test fun corridorFollowsARouteAsOneUndoableStroke(){
        val a=PaintedArea()
        val route=listOf(Coordinate(170.10,-43.73),Coordinate(170.14,-43.70),Coordinate(170.18,-43.69))
        a.corridor(route,500.0,2)
        // About 8 km long and 1 km wide, in cells about 440 m square: roughly 40-60.
        assertTrue("${a.size} cells",a.size in 30..120);assertTrue(a.snapshot().values.all{it==2})
        val b=a.bounds()!!;assertTrue(b.west<170.10&&b.east>170.18&&b.south<-43.73&&b.north>-43.69)
        assertTrue(a.undo());assertTrue(a.isEmpty())
    }
    @Test fun storedZoomsMapToTheSamePaintColours(){
        assertEquals(-1,PaintedArea.detailForZoom(12));assertEquals(0,PaintedArea.detailForZoom(13))
        assertEquals(1,PaintedArea.detailForZoom(14));assertEquals(2,PaintedArea.detailForZoom(16));assertEquals(3,PaintedArea.detailForZoom(18))
    }
}
