package nz.ridgeline.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintedNameTest {
    private fun square(x0:Double,y0:Double,x1:Double,y1:Double,clockwise:Boolean=true)=
        (if(clockwise)listOf(x0 to y0,x1 to y0,x1 to y1,x0 to y1)else listOf(x0 to y0,x0 to y1,x1 to y1,x1 to y0)).map{doubleArrayOf(it.first,it.second)}
    @Test fun aLettersRowsFillItsInsideAndLeaveItsHoles(){
        // An "O": an outer square and a hole wound the other way.
        val o=listOf(square(0.0,0.0,10.0,10.0),square(3.0,3.0,7.0,7.0,clockwise=false))
        assertEquals(listOf(0.0 to 10.0),PaintedName.spans(o,1.0))
        assertEquals(listOf(0.0 to 3.0,7.0 to 10.0),PaintedName.spans(o,5.0))
        assertTrue(PaintedName.spans(o,11.0).isEmpty())
        // Overlapping strokes wound the same way fill as one (non-zero, as fonts are).
        val x=listOf(square(0.0,0.0,6.0,10.0),square(4.0,0.0,10.0,10.0))
        assertEquals(listOf(0.0 to 10.0),PaintedName.spans(x,5.0))
    }
    @Test fun aNameTurnsOnlyOnceClearlyUpsideDown(){
        val east=doubleArrayOf(1.0,0.0)
        assertFalse(PaintedName.reversed(east,0.0,null))
        // Facing west it reads right to left: turned.
        assertTrue(PaintedName.reversed(east,180.0,null))
        // Running down the screen (facing north, a line to the south): kept as it was,
        // either way, until clearly upside down.
        val south=doubleArrayOf(0.0,1.0)
        assertFalse(PaintedName.reversed(south,0.0,false))
        assertTrue(PaintedName.reversed(south,0.0,true))
        // Facing east, south is to the right: reads left to right, not turned.
        assertFalse(PaintedName.reversed(south,90.0,true))
        assertFalse(PaintedName.reversed(south,-5.0,false))
        assertTrue(PaintedName.reversed(south,-20.0,false))
    }
}
