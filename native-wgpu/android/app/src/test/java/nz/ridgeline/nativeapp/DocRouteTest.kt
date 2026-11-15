package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocRouteTest{
    private fun at(east:Double,north:Double)=Coordinate(169.0+east/(111_320.0*kotlin.math.cos(Math.toRadians(-44.5))),-44.5+north/111_320.0)
    private fun near(a:Coordinate,b:Coordinate,m:Double=5.0)=DocTracks.meters(a,b)<m
    /** DOC's Kepler Track line (DOC_WebsiteWalkingandTrampingLns_DTO, CC BY 4.0): five pieces. */
    private val kepler=DocTracks.parseLines(File("src/test/fixtures/doc-lines/kepler-f7da33e0.json").readText())
    private val controlGates=Coordinate(167.684982,-45.439866)
    private val luxmore=Coordinate(167.61916,-45.38523);private val irisBurn=Coordinate(167.50950,-45.39904);private val moturau=Coordinate(167.61071,-45.48168)
    private val irisBurnFalls=Coordinate(167.496537,-45.395537);private val luxmoreSpur=Coordinate(167.590072,-45.386348)

    @Test fun theKeplerTrackIsOneLoopFromTheCarPark(){
        val line=DocRoute.route(kepler,WalkRoutes.stated("60 km loop"))
        val total=DocTracks.length(line)
        // The same without DOC's distance.
        assertEquals(total,DocTracks.length(DocRoute.route(kepler)),1.0)
        assertTrue("about DOC's 60 km: $total",total in 57_000.0..61_000.0)
        // Starts and ends at the control gates (the car park), not Iris Burn Falls.
        assertTrue("starts ${line.first()}",near(line.first(),controlGates,50.0));assertTrue("ends ${line.last()}",near(line.last(),controlGates,500.0))
        // Every hut on the loop, in order: Luxmore, Iris Burn, Moturau.
        val a=listOf(luxmore,irisBurn,moturau).map{DocTracks.along(line,it)}
        for(h in listOf(luxmore,irisBurn,moturau))assertTrue("hut $h ${DocTracks.distanceTo(line,h)} m off",DocTracks.distanceTo(line,h)<100)
        assertTrue("in order: $a",a[0]<a[1]&&a[1]<a[2])
        // Side trips are left out: the falls, and the 0.3 km spur near Luxmore (its end 166 m from the track).
        assertTrue("falls ${DocTracks.distanceTo(line,irisBurnFalls)}",DocTracks.distanceTo(line,irisBurnFalls)>500);assertTrue("spur ${DocTracks.distanceTo(line,luxmoreSpur)}",DocTracks.distanceTo(line,luxmoreSpur)>50)
        // No jumps between piece ends: every step on the line is short.
        val jump=line.zipWithNext().maxOf{(p,q)->DocTracks.meters(p,q)};assertTrue("longest step $jump",jump<600)
    }
    @Test fun aHutOnASideTripIsStillListed(){
        val line=DocRoute.route(kepler)
        val falls=DocTracks.Hut("Iris Burn Falls Biv","","","",irisBurnFalls)
        assertTrue(DocTracks.stops(listOf(falls),line).isEmpty())
        val stop=DocTracks.stops(listOf(falls),line,pieces=kepler).single()
        assertEquals(DocTracks.along(line,irisBurn),stop.along,1500.0)
    }
    @Test fun aWalkFromEndToEndInPiecesOutOfOrder(){
        // Three pieces of a 3 km walk west to east, listed middle first, one reversed,
        // with a 400 m side trip north from the middle; DOC's line starts at the west end.
        val west=listOf(at(0.0,0.0),at(1000.0,0.0));val middle=listOf(at(1000.0,0.0),at(2000.0,0.0));val east=listOf(at(3000.0,0.0),at(2000.0,0.0))
        val side=listOf(at(1500.0,0.0),at(1500.0,400.0))
        val line=DocRoute.route(listOf(west,middle,east,side))
        assertTrue(near(line.first(),at(0.0,0.0)));assertTrue(near(line.last(),at(3000.0,0.0)))
        assertEquals(3000.0,DocTracks.length(line),1.0)
    }
    @Test fun aLoopLeftOpenAtItsCarParkIsClosed(){
        // A 4 km square loop whose two ends stop 200 m apart at the car park (walked across it: 4.2 km).
        val loop=listOf(at(100.0,0.0),at(1000.0,0.0),at(1000.0,1000.0),at(0.0,1000.0),at(0.0,0.0),at(-100.0,0.0))
        val line=DocRoute.route(listOf(loop.subList(0,3),loop.subList(2,loop.size)))
        assertTrue(near(line.first(),at(100.0,0.0)))
        assertEquals(4200.0,DocTracks.length(line),5.0)
    }
    @Test fun aLoopWithAnAccessTrackIsInRoundAndOut(){
        // A car park 2 km south of a 4 km square loop; DOC's line starts at the car park;
        // DOC states 8 km (in, round and out).
        val access=listOf(at(0.0,-2000.0),at(0.0,0.0))
        val loop=listOf(at(0.0,0.0),at(1000.0,0.0),at(1000.0,1000.0),at(0.0,1000.0),at(0.0,0.0))
        val pieces=listOf(access,loop.subList(0,3),loop.subList(2,loop.size))
        val line=DocRoute.route(pieces,8000.0)
        assertTrue(near(line.first(),at(0.0,-2000.0)));assertTrue(near(line.last(),at(0.0,-2000.0)))
        assertEquals(2000.0+4000.0+2000.0,DocTracks.length(line),5.0)
        // DOC states the loop alone (4 km): round the loop only.
        assertEquals(4000.0,DocTracks.length(DocRoute.route(pieces,4000.0)),5.0)
    }
}
