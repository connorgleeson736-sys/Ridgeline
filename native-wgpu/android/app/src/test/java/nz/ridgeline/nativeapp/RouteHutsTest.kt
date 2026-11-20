package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** Huts on a planned route, for its elevation profile. */
class RouteHutsTest {
    private fun place(kind:String,name:String,lon:Double,lat:Double)=PlaceSearch.Place(kind,name,emptyList(),lat,lon,0.0,"","")
    private val places=listOf(
        place("hut","Liverpool Hut",168.6000,-44.5000),
        place("hut","Aspiring Hut",168.7000,-44.4000),
        place("peak","Mt Liverpool",168.6005,-44.5003),
        place("hut","Halfway Hut",168.6500,-44.4505),
        place("hut","Far Hut",168.6500,-44.4700))
    private fun within(b:DoubleArray)=places.filter{it.longitude>=b[0]&&it.longitude<=b[2]&&it.latitude>=b[1]&&it.latitude<=b[3]}
    /** A straight line sampled every ~100 m, with distances along it. */
    private fun profile(from:Coordinate,to:Coordinate):List<NativeGeo.ProfilePoint>{
        val total=DocTracks.meters(from,to);val n=(total/100).toInt().coerceAtLeast(1)
        return (0..n).map{val t=it.toDouble()/n;NativeGeo.ProfilePoint(Coordinate(from.longitude+(to.longitude-from.longitude)*t,from.latitude+(to.latitude-from.latitude)*t),total*t)}
    }
    @Test fun hutsAtTheEndsAndOnTheWayAreMarkedWhereTheyAre(){
        // From ~60 m off Liverpool Hut to ~60 m off Aspiring Hut, past Halfway Hut (55 m off the line).
        val p=profile(Coordinate(168.6007,-44.5002),Coordinate(168.6994,-44.4004))
        val total=p.last().distance
        val marks=RouteHuts.along(p,::within)
        assertEquals(listOf("Liverpool Hut","Halfway Hut","Aspiring Hut"),marks.map{it.second})
        assertEquals(0.0,marks[0].first,100.0);assertEquals(total/2,marks[1].first,300.0);assertEquals(total,marks[2].first,100.0)
    }
    @Test fun hutsFarFromTheLineAndOtherPlacesAreNot(){
        val p=profile(Coordinate(168.6007,-44.5002),Coordinate(168.6994,-44.4004))
        // Far Hut is ~2 km off the line; the peak beside Liverpool Hut is not a hut.
        assertFalse(RouteHuts.along(p,::within).any{it.second=="Far Hut"||it.second=="Mt Liverpool"})
        assertTrue(RouteHuts.along(profile(Coordinate(168.62,-44.52),Coordinate(168.63,-44.53)),::within).isEmpty())
    }
}
