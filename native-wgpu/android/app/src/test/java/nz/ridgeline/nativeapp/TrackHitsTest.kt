package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class TrackHitsTest{
    private fun p(x:Double,y:Double)=VectorTile.Point(x,y)
    @Test fun nearestPointOnTheLine(){
        val line=listOf(p(0.0,0.0),p(10.0,0.0),p(10.0,10.0))
        assertEquals(2.0,TrackHits.distance(line,5.0,2.0),1e-12)   // beside the first segment
        assertEquals(3.0,TrackHits.distance(line,13.0,5.0),1e-12)  // beside the second
        assertEquals(5.0,TrackHits.distance(line,-3.0,4.0),1e-12)  // past the start: to the end point
    }
    @Test fun degenerateLines(){
        assertTrue(TrackHits.distance(emptyList(),0.0,0.0).isInfinite())
        assertEquals(5.0,TrackHits.distance(listOf(p(0.0,0.0)),3.0,4.0),1e-12)
        assertEquals(5.0,TrackHits.distance(listOf(p(0.0,0.0),p(0.0,0.0)),3.0,4.0),1e-12)
    }
}
