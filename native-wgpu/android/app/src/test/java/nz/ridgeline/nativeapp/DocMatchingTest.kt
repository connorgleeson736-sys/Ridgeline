package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.floor

/** Finding a track's DOC walk by place when the names differ, and reading
 * DOC's page text. Real data: the map's "Rabbit Pass Route" (eleven LINZ
 * tiles) and DOC's walk lines around it; two doc.govt.nz page excerpts. */
class DocMatchingTest {
    private fun follow(dir:String,name:String):List<Coordinate>{
        val tiles=File(dir).listFiles()!!.filter{it.name.endsWith(".pbf")}.associate{f->
            val (z,x,y)=f.name.removeSuffix(".pbf").split("-").map{it.toInt()};GpuUploadQueue.Id(z,x,y) to VectorTile.decode(f.readBytes(),z,x,y,setOf("streets"))}
        val start=tiles.entries.first{e->e.value.any{it.properties["name"]==name}}
        val p=start.value.first{it.properties["name"]==name}.paths[0].let{it[it.size/2]}
        val at=Coordinate(p.x*360-180,CameraMath.latitude(p.y))
        val queued=linkedSetOf(start.key);val pending=ArrayDeque(listOf(start.key));val pieces=ArrayList<List<VectorTile.Point>>()
        while(pending.isNotEmpty()){val id=pending.removeFirst();val f=tiles[id]?:continue
            val (parts,next)=MapLayers.trackPieces(id,f,name);pieces.addAll(parts);for(t in next)if(queued.add(t))pending.addLast(t)}
        return DocTracks.chain(pieces.map{q->q.map{Coordinate(it.x*360-180,CameraMath.latitude(it.y))}},at,25.0)
    }
    @Test fun rabbitPassRouteIsPartOfTheWilkinEastMatukitukiTraverse(){
        val line=follow("src/test/fixtures/rabbit","Rabbit Pass Route")
        assertTrue("followed ${DocTracks.length(line)} m",DocTracks.length(line) in 15000.0..18000.0)
        val lines=DocTracks.parseWalkLines(File("src/test/fixtures/rabbit/doc-walk-lines.json").readText())
        val walk=DocTracks.alongTrack(lines,line)
        assertEquals("276d1bde-9a04-4ff9-9e09-c1207ae39450",walk?.guid)
        assertTrue(java.net.URLDecoder.decode(DocTracks.walkByGuidUrl(walk!!.guid),"UTF-8").contains("LIKE '%276d1bde9a044ff99e09c1207ae39450%'"))
    }
    private fun straight(lon0:Double,lon1:Double,n:Int=40)=(0..n).map{Coordinate(lon0+(lon1-lon0)*it/n,-44.3)}
    @Test fun theShorterWalkWinsWhenBothCoverTheTrack(){
        val track=straight(168.80,168.82)
        val valley=DocTracks.WalkLine("valley",30000.0,listOf(straight(168.70,168.95)))
        val side=DocTracks.WalkLine("side",2500.0,listOf(straight(168.799,168.821)))
        assertEquals("side",DocTracks.alongTrack(listOf(valley,side),track)?.guid)
    }
    @Test fun aWalkThatOnlyTouchesTheTrackIsNotItsWalk(){
        val track=straight(168.80,168.90)
        val crossing=DocTracks.WalkLine("crossing",3000.0,listOf((0..20).map{Coordinate(168.85,-44.31+it*.001)}))
        val parallelFar=DocTracks.WalkLine("far",8000.0,listOf((0..40).map{Coordinate(168.80+it*.0025,-44.31)}))
        assertNull(DocTracks.alongTrack(listOf(crossing,parallelFar),track))
    }
    @Test fun readsDocPageSections(){
        val roys=DocPages.parse(File("src/test/fixtures/docpages/roys-peak-track.html").readText())
        assertEquals(listOf("Description","Getting there","Know before you go"),roys.map{it.title})
        val d=roys[0].blocks
        assertEquals(DocPages.Heading("About the track"),d[0])
        assertEquals(3,d.takeWhile{it !is DocPages.Paragraph}.count{it is DocPages.Bullet})
        assertTrue(d.any{it.text=="The track starts at Roys Peak car park, on Wānaka – Mount Aspiring Road."})
        assertTrue(roys.flatMap{it.blocks}.none{"<" in it.text||"&" in it.text&&";" in it.text})
        val traverse=DocPages.parse(File("src/test/fixtures/docpages/wilkin-east-matukituki-traverse.html").readText())
        val text=traverse.first{it.title=="Description"}.blocks
        assertTrue(text.contains(DocPages.Heading("Top Forks Hut to Ruth Flat")))
        assertTrue(text.any{it is DocPages.Paragraph&&"Rabbit Pass (1430 metres)" in it.text})
        // Photo galleries and hut tiles are not text blocks.
        assertTrue(traverse.flatMap{it.blocks}.none{"David W Noble" in it.text||"16-bunk" in it.text})
    }
    @Test fun decodesEntitiesAndBreaks(){
        assertEquals("A – B’s “c” ā 5°\nnext",DocPages.text("A &ndash; B&rsquo;s &ldquo;c&rdquo; &#257; 5&deg;<br/>next"))
        assertEquals("&bogus; &#99999999;",DocPages.decode("&bogus; &#99999999;"))
    }
}
