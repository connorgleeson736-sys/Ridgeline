package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** A followed track adds its points as line only (no waypoint dots), in one undo step. */
class FollowedRouteTest{
    private fun c(i:Int)=Coordinate(170.0+i*0.001,-43.5)
    @Test fun aFollowedTrackIsOneStepWithDotsOnlyAtItsEnds(){
        val e=RouteEditor()
        assertEquals(RouteEditor.Result.CHANGED,e.append(c(0)))
        assertTrue(e.shape.isEmpty())
        assertEquals(RouteEditor.Result.CHANGED,e.appendPath(listOf(c(1),c(2),c(3))))
        assertEquals(listOf(c(0),c(1),c(2),c(3)).map{it.rounded()},e.points)
        assertEquals(listOf(false,true,true,false),e.shape)
        assertEquals(3,e.selected)
        assertFalse("points along the track are not selectable",e.select(1))
        assertTrue(e.select(0))
        // Inserting and deleting keep the flags with their points.
        e.insertAfter(0,c(5));assertEquals(listOf(false,false,true,true,false),e.shape)
        e.select(1);e.deleteSelected();assertEquals(listOf(false,true,true,false),e.shape)
        e.undo();e.undo();assertEquals(listOf(false,true,true,false),e.shape)
        e.undo();assertEquals(listOf(c(0).rounded()),e.points);assertTrue(e.shape.isEmpty())
    }
    @Test fun pointsCloseToThePreviousAreDropped(){
        val e=RouteEditor();e.append(c(0))
        // The path starts at the last point (dropped) and has a repeated point.
        assertEquals(RouteEditor.Result.CHANGED,e.appendPath(listOf(c(0),c(1),c(1),c(2))))
        assertEquals(3,e.points.size);assertEquals(listOf(false,true,false),e.shape)
        assertEquals(RouteEditor.Result.TOO_CLOSE,e.appendPath(listOf(c(2))))
    }
    @Test fun flagsAreSavedAndOldRoutesStillRead(){
        val points=listOf(c(0),c(1),c(2));val shape=listOf(false,true,false)
        val bytes=RouteBlob.encode(points,shape)
        assertEquals(8+3*17,bytes.size)
        assertEquals(points to shape,RouteBlob.decodeAll(bytes))
        assertEquals(points,RouteBlob.decode(bytes))
        // No flags: the original format, read back without any.
        val plain=RouteBlob.encode(points,listOf(false,false,false))
        assertEquals(8+3*16,plain.size);assertEquals(points to emptyList<Boolean>(),RouteBlob.decodeAll(plain))
        val bad=bytes.copyOf();bad[bad.size-1]=7
        assertThrows(IllegalArgumentException::class.java){RouteBlob.decodeAll(bad)}
    }
    @Test fun draggingAPointReplacesThePiecesEitherSide(){
        val e=RouteEditor();e.append(c(0));e.appendPath(listOf(c(1),c(2),c(3)));e.appendPath(listOf(c(4),c(5)))
        // Waypoints at 0, 3 and 5; drag 3.
        assertTrue(e.beginDrag(3))
        assertEquals(c(0).rounded() to c(5).rounded(),e.dragNeighbours())
        // Along the track: new pieces either side of the dragged point.
        val p=Coordinate(170.0035,-43.5);val q=Coordinate(170.0045,-43.5)
        assertTrue(e.previewReroute(listOf(c(1),p),listOf(q)))
        assertEquals(listOf(c(0),c(1),p,q,c(5)).map{it.rounded()},e.points)
        assertEquals(listOf(false,true,false,true,false),e.shape)
        assertEquals(2,e.selected)
        // Pulled off the track: straight to both neighbours.
        val off=Coordinate(170.003,-43.49)
        assertTrue(e.previewReroute(listOf(off),emptyList()))
        assertEquals(listOf(c(0),off,c(5)).map{it.rounded()},e.points);assertTrue(e.shape.isEmpty())
        // One undo step for the whole drag; cancel would restore it all.
        assertTrue(e.commitDrag());assertTrue(e.undo())
        assertEquals(listOf(false,true,true,false,true,false),e.shape);assertEquals(6,e.points.size)
    }
    @Test fun cancellingADragRestoresTheTrack(){
        val e=RouteEditor();e.append(c(0));e.appendPath(listOf(c(1),c(2)))
        assertTrue(e.beginDrag(2));assertEquals(c(0).rounded() to null,e.dragNeighbours())
        e.previewReroute(listOf(Coordinate(170.01,-43.49)),emptyList())
        assertTrue(e.cancelDrag());assertEquals(listOf(false,true,false),e.shape);assertEquals(3,e.points.size)
    }
}
