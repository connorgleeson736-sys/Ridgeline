package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

/** Huts at a planned route's start and end, for its elevation profile. */
class RouteHutsTest {
    private fun place(kind:String,name:String,lon:Double,lat:Double)=PlaceSearch.Place(kind,name,emptyList(),lat,lon,0.0,"","")
    private val places=listOf(
        place("hut","Liverpool Hut",168.6000,-44.5000),
        place("hut","Aspiring Hut",168.7000,-44.4000),
        place("peak","Mt Liverpool",168.6005,-44.5003),
        place("hut","Far Hut",168.6500,-44.4500))
    private fun within(b:DoubleArray)=places.filter{it.longitude>=b[0]&&it.longitude<=b[2]&&it.latitude>=b[1]&&it.latitude<=b[3]}
    @Test fun aRouteFromHutToHutMarksBothEnds(){
        // Each end ~60 m from DOC's hut position (the map's symbol is a little off it).
        val route=listOf(Coordinate(168.6007,-44.5002),Coordinate(168.65,-44.46),Coordinate(168.6994,-44.4004))
        assertEquals(listOf(0.0 to "Liverpool Hut",12345.0 to "Aspiring Hut"),RouteHuts.ends(route,12345.0,::within))
    }
    @Test fun onlyHutsCloseToAnEndAreMarked(){
        // Starts at a hut, ends 1 km from any; passing Far Hut midway is not an end.
        val route=listOf(Coordinate(168.6,-44.5),Coordinate(168.65,-44.45),Coordinate(168.66,-44.44))
        assertEquals(listOf(0.0 to "Liverpool Hut"),RouteHuts.ends(route,9000.0,::within))
        assertTrue(RouteHuts.ends(listOf(Coordinate(168.62,-44.52),Coordinate(168.63,-44.53)),1500.0,::within).isEmpty())
    }
}
