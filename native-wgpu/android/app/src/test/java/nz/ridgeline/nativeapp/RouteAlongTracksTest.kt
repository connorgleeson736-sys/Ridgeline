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
    @Test fun pointToPointAlongTheTrackEndingOnIt(){
        // From a hut 30 m off the track to a tap 30 m beside it further on.
        val line=TrackFollow.route(at(0.0,30.0),at(1030.0,900.0),tracks,50.0)!!
        assertTrue(near(line.first(),at(0.0,30.0)))
        // Ends on the track beside the tap, not branching off to the tap itself.
        assertTrue("ends on the track: ${line.last()}",near(line.last(),at(1000.0,900.0)))
        assertTrue(line.any{near(it,at(1000.0,0.0))})
        assertEquals(30.0+1000.0+900.0,DocTracks.length(line),1.0)
        assertTrue(line.size<=5)
    }
    @Test fun joinsAndLeavesTheTrackBesideTheTaps(){
        // Exactly beside each tap, wherever the track's points are.
        val line=TrackFollow.route(at(263.0,20.0),at(483.0,-271.0),listOf(tracks[0],"Spur" to listOf(at(500.0,0.0),at(500.0,-300.0))),40.0)!!
        assertTrue(near(line[1],at(263.0,0.0)));assertTrue(near(line.last(),at(500.0,-271.0)))
        assertEquals(20.0+237.0+271.0,DocTracks.length(line),1.0)
    }
    @Test fun alongOnePieceOfTrack(){
        val line=TrackFollow.route(at(3.0,5.0),at(9.0,-4.0),tracks,10.0)!!
        assertEquals(listOf(at(3.0,5.0),at(3.0,0.0),at(9.0,0.0)).size,line.size)
        assertTrue(near(line.last(),at(9.0,0.0)))
    }
    @Test fun aLongLegFollowsTheTrack(){
        // A 32 km valley track with 2 km bends; taps 40 m beside each end.
        val bends=(0..16).map{i->at(i*2000.0,if(i%2==0)0.0 else 600.0)}
        val line=TrackFollow.route(at(0.0,40.0),at(32_000.0,40.0),listOf("Long Valley Track" to bends),60.0)!!
        assertTrue(near(line.last(),at(32_000.0,0.0)))
        assertEquals(DocTracks.length(bends)+40.0,DocTracks.length(line),2.0)
        assertTrue("the bends only, not every 20 m: ${line.size}",line.size<=20)
    }
    @Test fun longLegsReadCoarserTiles(){
        val a=Coordinate(169.0,-44.5)
        // [km] east and [km] north: a diagonal leg, the largest area for its length.
        fun b(km:Double)=Coordinate(169.0+km*1000/(111_320.0*kotlin.math.cos(Math.toRadians(-44.5))),-44.5+km*1000/111_320.0)
        assertEquals(14,MapLayers.areaZoom(listOf(a,b(3.0)),1000.0,MapLayers.FOLLOW_ZOOMS))
        // 25 km each way (3 km margin): about 360 zoom-14 tiles, so zoom 13 (about 100).
        assertEquals(13,MapLayers.areaZoom(listOf(a,b(25.0)),3000.0,MapLayers.FOLLOW_ZOOMS))
        assertEquals(12,MapLayers.areaZoom(listOf(a,b(50.0)),3000.0,MapLayers.FOLLOW_ZOOMS))
        assertNull(MapLayers.areaZoom(listOf(a,b(300.0)),3000.0,MapLayers.FOLLOW_ZOOMS))
    }
    @Test fun aTapNotCloseToATrackIsNotFollowed(){
        // 60 m off the track with a 50 m reach: drawn straight instead.
        assertNull(TrackFollow.route(at(0.0,0.0),at(600.0,60.0),tracks,50.0))
        // The route's last point too far from any track.
        assertNull(TrackFollow.route(at(0.0,500.0),at(1000.0,0.0),tracks,50.0))
        assertNull(TrackFollow.route(at(0.0,0.0),at(1000.0,0.0),emptyList(),50.0))
        // Separate tracks further apart than the network bridges.
        val apart=listOf("A" to listOf(at(0.0,0.0),at(100.0,0.0)),"B" to listOf(at(800.0,0.0),at(900.0,0.0)))
        assertNull(TrackFollow.route(at(0.0,0.0),at(900.0,0.0),apart,50.0))
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
        val from=Coordinate(bottom.longitude+0.0004,bottom.latitude);val line=TrackFollow.route(from,top,tracks,200.0)!!
        val straight=DocTracks.meters(from,top);val along=DocTracks.length(line)
        println("ROUTE ${line.size} points, ${along.toInt()} m along vs ${straight.toInt()} m straight")
        assertTrue("follows the zigzags: $along m vs $straight m",along>1.5*straight)
        // Every point after the first is on the track, the end too.
        assertTrue(line.drop(1).all{p->tracks.any{t->DocTracks.distanceTo(t.second,p)<1}})
        assertTrue("simplified: ${line.size}",line.size<400)
    }
}
