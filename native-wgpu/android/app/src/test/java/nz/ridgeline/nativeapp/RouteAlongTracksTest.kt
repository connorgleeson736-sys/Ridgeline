package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class RouteAlongTracksTest{
    /** A point [east], [north] metres from a place near Wanaka. */
    private fun at(east:Double,north:Double)=Coordinate(169.0+east/(111_320.0*kotlin.math.cos(Math.toRadians(-44.5))),-44.5+north/111_320.0)
    private fun near(a:Coordinate,b:Coordinate)=DocTracks.meters(a,b)<1.0
    // An L-shaped track (east 1 km, then north 1 km) and a short spur off it.
    private val tracks=listOf("Valley Track" to listOf(at(0.0,0.0),at(1000.0,0.0),at(1000.0,1000.0)),
        "Spur" to listOf(at(500.0,0.0),at(500.0,-300.0)))
    @Test fun followsTheTrackRoundTheCorner(){
        val line=TrackFollow.route(at(0.0,30.0),at(1030.0,900.0),tracks)!!
        // From the tap, onto the track beside it, round the corner, to the tap.
        assertTrue(near(line.first(),at(0.0,30.0)));assertTrue(near(line.last(),at(1030.0,900.0)))
        assertTrue(line.any{near(it,at(1000.0,0.0))})
        assertEquals(30.0+1900.0+30.0,DocTracks.length(line),5.0)
        // Straight pieces need only their ends.
        assertTrue(line.size<=6)
    }
    @Test fun joinsTheTrackBesideTheTapNotAPieceEnd(){
        val line=TrackFollow.route(at(260.0,20.0),at(500.0,-280.0),tracks)!!
        // 20 m onto the track (beside the tap, within one 20 m split), 240 m along, 280 m down the spur.
        assertEquals(20.0+240.0+280.0,DocTracks.length(line),10.0)
    }
    @Test fun noTrackNearbyGivesNothing(){
        assertNull(TrackFollow.route(at(0.0,500.0),at(1000.0,0.0),tracks))
        assertNull(TrackFollow.route(at(0.0,0.0),at(1000.0,0.0),emptyList()))
        // Separate tracks further apart than the network bridges.
        val apart=listOf("A" to listOf(at(0.0,0.0),at(100.0,0.0)),"B" to listOf(at(800.0,0.0),at(900.0,0.0)))
        assertNull(TrackFollow.route(at(0.0,0.0),at(900.0,0.0),apart))
    }
}

/** From here to there on real LINZ tiles: Roys Peak Track (src/test/fixtures/tracks). */
class RouteAlongRealTracksTest{
    private val tracks=ArrayList<Pair<String,List<Coordinate>>>().also{out->
        for(f in java.io.File("src/test/fixtures/tracks").listFiles()!!){val (z,x,y)=f.name.removeSuffix(".pbf").split("-").map{it.toInt()}
            WalkOptions.read(GpuUploadQueue.Id(z,x,y),VectorTile.decode(f.readBytes(),z,x,y,WalkOptions.LAYERS),out,ArrayList(),ArrayList())}}
    @Test fun upTheZigzagsNotStraightUpTheHill(){
        val roys=tracks.filter{it.first=="Roys Peak Track"}.flatMap{it.second}
        // Taps a little off the track: near its lowest point and near the summit.
        val bottom=roys.minByOrNull{it.latitude}!!;val top=Coordinate(169.0597,-44.6825)
        val from=Coordinate(bottom.longitude+0.0004,bottom.latitude);val line=TrackFollow.route(from,top,tracks)!!
        val straight=DocTracks.meters(from,top);val along=DocTracks.length(line)
        println("ROUTE ${line.size} points, ${along.toInt()} m along vs ${straight.toInt()} m straight")
        assertTrue("follows the zigzags: $along m vs $straight m",along>1.5*straight)
        // Every point after the first stays on the track, until the tap at the end.
        assertTrue(line.drop(1).dropLast(1).all{p->tracks.any{t->DocTracks.distanceTo(t.second,p)<3}})
        assertTrue("simplified: ${line.size}",line.size<400)
    }
}
