package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Track names: shown at Capacitor's zooms, repeated along the track, and
 * stable (same keys) as the camera zooms and source tiles change. */
class TrackLabelsTest {
    private val style=TopographyStyle(File("src/main/assets/topography-style.json").readText())
    private val state=MapSettings.State()
    /** A tile at zoom 14 near Wanaka and a long diagonal foot track across it. */
    private val z=14;private val x=16045;private val y=10571
    private fun track(name:String,from:Double=.05,to:Double=.95):VectorTile.Feature{
        val n=1.0/(1 shl z)
        val path=(0..40).map{val t=from+(to-from)*it/40.0;VectorTile.Point((x+t)*n,(y+t*.8+.1*sin(t*9))*n)}
        return VectorTile.Feature("streets",1,2,mapOf("kind" to "track","track_use" to "foot","name" to name),listOf(path))
    }
    private fun names(features:List<VectorTile.Feature>,zoom:Double,tileZoom:Double=z.toDouble())=
        style.build(features,state,zoom,tileZoom).labels.filter{it.key.startsWith(TopographyStyle.TRACK_LABELS+":")}

    @Test fun ordinaryTrackNamesFromTileZoom14(){
        val f=listOf(track("Diamond Lake Track"))
        assertTrue("named at camera 14 on a zoom-14 tile",names(f,14.0).isNotEmpty())
        assertTrue("not on a coarser zoom-13 tile (style filter)",names(f,14.0,13.0).isEmpty())
        assertTrue("not below the layer's minzoom",names(f,11.75).isEmpty())
    }
    @Test fun greatWalkNamesFromZoom12(){
        assertTrue(names(listOf(track("Routeburn Track")),12.0,12.0).isNotEmpty())
        assertTrue(names(listOf(track("Diamond Lake Track")),12.0,12.0).isEmpty())
    }
    @Test fun longTracksRepeatTheirName(){
        val labels=names(listOf(track("Diamond Lake Track")),16.0)
        assertTrue("several candidates along the track: ${labels.size}",labels.size>=4)
        assertEquals(labels.size,labels.map{it.key}.distinct().size)
        assertTrue(labels.all{it.text=="Diamond Lake Track"&&it.spacing>0f})
        assertTrue(labels.size<=TopographyStyle.MAX_REPEATS)
    }
    @Test fun zoomingInKeepsEarlierNames(){
        val f=listOf(track("Diamond Lake Track"))
        for(zoom in listOf(14.0,15.0,16.0)){
            val coarse=names(f,zoom).associate{it.key to it.point};val fine=names(f,zoom+1).associate{it.key to it.point}
            for((key,p) in coarse){val q=fine[key];assertNotNull("$key kept at ${zoom+1}",q);assertEquals(p.x,q!!.x,1e-12);assertEquals(p.y,q.y,1e-12)}
        }
    }
    @Test fun aShortTrackStillHasItsName(){
        val n=1.0/(1 shl z)
        val tiny=VectorTile.Feature("streets",2,2,mapOf("kind" to "track","track_use" to "foot","name" to "Short Track"),listOf(listOf(VectorTile.Point((x+.30)*n,(y+.30)*n),VectorTile.Point((x+.31)*n,(y+.30)*n))))
        assertEquals(1,names(listOf(tiny),14.0).size)
    }
    @Test fun crossingKeysNameTheCoarsestLine(){
        // A horizontal segment across x = 1/2 at zoom 3 (line 4 of 8 = line 1 of 2 at zoom 1).
        val c=TopographyStyle.crossings(listOf(VectorTile.Point(.45,.3),VectorTile.Point(.55,.3)),3)
        assertEquals(1,c.size);assertEquals(.5,c[0].first.x,1e-12);assertTrue(c[0].second,c[0].second.startsWith("v1/1:"))
    }

    /** Real LINZ tiles (TRACK_TILES=dir of z-x-y.pbf): candidates per zoom stay modest. */
    @Test fun realTilesCandidateCounts(){
        val dir=System.getenv("TRACK_TILES")?.let(::File)?:return
        for(zoom in listOf(12.0,13.0,14.0,15.0,16.0,17.0)){var total=0;val named=HashSet<String>()
            for(f in dir.listFiles()!!.filter{it.name.endsWith(".pbf")}){
                val (tz,tx,ty)=f.name.removeSuffix(".pbf").split("-").takeLast(3).map{it.toInt()}
                if(tz!=min(14,floor(zoom).toInt()))continue
                val labels=style.build(VectorTile.decode(f.readBytes(),tz,tx,ty,style.sourceLayers(state,zoom)),state,zoom,tz.toDouble()).labels.filter{it.key.startsWith(TopographyStyle.TRACK_LABELS+":")}
                total+=labels.size;labels.mapTo(named){it.text}
            }
            println("track names at $zoom: $total candidates, ${named.size} names")
        }
    }
}

/** Following a tapped track through real LINZ tiles (Roys Peak Track, nine
 * zoom-14 tiles in src/test/fixtures/tracks), as MapLayers.trackLine does. */
class TrackFollowTest {
    private val tiles=java.io.File("src/test/fixtures/tracks").listFiles()!!.associate{f->
        val (z,x,y)=f.name.removeSuffix(".pbf").split("-").map{it.toInt()};GpuUploadQueue.Id(z,x,y) to VectorTile.decode(f.readBytes(),z,x,y,setOf("streets"))}
    private fun follow(name:String,at:Coordinate):List<Coordinate>{
        val n=1 shl 14;val start=GpuUploadQueue.Id(14,floor((at.longitude+180)/360*n).toInt(),floor(CameraMath.mercatorY(at.latitude)*n).toInt())
        val queued=linkedSetOf(start);val pending=ArrayDeque(listOf(start));val pieces=ArrayList<List<VectorTile.Point>>()
        while(pending.isNotEmpty()){val id=pending.removeFirst();val features=tiles[id]?:continue
            val (parts,next)=MapLayers.trackPieces(id,features,name);pieces.addAll(parts);for(t in next)if(queued.add(t))pending.addLast(t)}
        return DocTracks.chain(pieces.map{p->p.map{Coordinate(it.x*360-180,CameraMath.latitude(it.y))}},at,25.0)
    }
    @Test fun followsTheTrackAcrossTilesIntoOneLine(){
        // Tapped near the summit; the track runs down through other tiles to the car park.
        val line=follow("Roys Peak Track",Coordinate(169.0597,-44.6825))
        val length=DocTracks.length(line)
        println("FOLLOW points ${line.size} length $length")
        assertTrue("one continuous line of $length m",length in 6500.0..9000.0)
        assertTrue(line.zipWithNext().all{(a,b)->DocTracks.meters(a,b)<200})
    }
    @Test fun otherNamesAreNotFollowed(){
        assertTrue(follow("No Such Track",Coordinate(169.0597,-44.6825)).isEmpty())
    }
}
