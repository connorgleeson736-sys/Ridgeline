package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Track sections named by their larger DOC walk, on real data: the map's
 * "Siberia Track" is part of DOC's Gillespie Pass Circuit (eight LINZ tiles,
 * DOC's walk lines for the zoom-10 data tile, and the walks they belong to). */
class DocWalksTest {
    private val dir="src/test/fixtures/gillespie"
    private val infos=DocWalks.parseList(File("$dir/doc-walks.json").readText())
    private val index=DocWalks.Index(DocWalks.lines(DocTracks.parseWalkLines(File("$dir/doc-walk-lines-10-992-652.json").readText())))
    private val tiles=File(dir).listFiles()!!.filter{it.name.endsWith(".pbf")}.associate{f->
        val (z,x,y)=f.name.removeSuffix(".pbf").split("-").map{it.toInt()};GpuUploadQueue.Id(z,x,y) to VectorTile.decode(f.readBytes(),z,x,y,setOf("streets"))}
    private val circuit get()=infos.values.single{it.name=="Gillespie Pass Circuit"}

    @Test fun largeWalksAreCircuitsTraversesAndMultiDayWalksNotCollections(){
        assertTrue(circuit.large)
        assertTrue(infos.values.single{it.name.startsWith("Wilkin Valley - East")}.large)
        assertFalse(infos.values.single{it.name=="Wilkin Valley tracks"}.large)
        assertFalse(DocWalks.Info("x","Mount Iron Track","Easy","1 hr 30 min","").large)
        assertTrue(DocWalks.Info("x","St James Walkway","","5 days","").large)
    }
    @Test fun siberiaTrackBelongsToTheGillespiePassCircuit(){
        var siberia=0;var marked=0;var wrong=0
        for((_,features) in tiles)for(f in DocWalks.mark(features,index,infos)){
            if(f.properties["name"]=="Siberia Track"){siberia++;if(f.properties[DocWalks.WALK_ID]==circuit.guid)marked++}
            if(f.properties[DocWalks.WALK]=="Wilkin Valley tracks")wrong++
        }
        assertTrue("$marked of $siberia Siberia Track pieces",siberia>0&&marked>=siberia*.75)
        assertEquals(0,wrong)
    }
    @Test fun sectionsShowTheCircuitsNameUntilZoom15(){
        val style=TopographyStyle(File("src/main/assets/topography-style.json").readText());val state=MapSettings.State()
        val (id,features)=tiles.entries.first{e->e.value.any{it.properties["name"]=="Siberia Track"}}
        val marked=DocWalks.mark(features,index,infos)
        fun names(zoom:Double,tileZoom:Double=14.0)=style.build(marked,state,zoom,tileZoom).labels.filter{it.key.startsWith(TopographyStyle.TRACK_LABELS+":")}
        val far=names(14.0)
        assertTrue(far.any{it.text=="Gillespie Pass Circuit"&&it.ref==circuit.guid&&it.priority==TopographyStyle.WALK_PRIORITY})
        assertTrue(far.none{it.text=="Siberia Track"})
        assertTrue("circuit named from tile zoom 12 like a Great Walk",names(12.5,12.0).any{it.text=="Gillespie Pass Circuit"})
        val near=names(15.5)
        assertTrue(near.any{it.text=="Siberia Track"&&it.ref.isEmpty()})
        assertTrue(id.z==14)
    }
    @Test fun nothingChangesWithoutWalkData(){
        val features=tiles.values.first()
        assertSame(features,DocWalks.mark(features,null,infos))
        assertSame(features,DocWalks.mark(features,index,emptyMap()))
    }
}
