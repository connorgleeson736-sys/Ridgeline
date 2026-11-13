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
    @Test fun namesAreTheSameWalkWithoutGenericWords(){
        assertTrue(DocWalks.sameName("Gillespie Pass Circuit Track","Gillespie Pass Circuit"))
        assertTrue(DocWalks.sameName("Wilkin Valley Track","Wilkin Valley tracks"))
        assertTrue(DocWalks.sameName("Mt Iron Track","Mount Iron Track"))
        assertFalse(DocWalks.sameName("Siberia Track","Gillespie Pass Circuit"))
        assertFalse(DocWalks.sameName("Rees Track","Rees-Dart Track"))
        assertFalse(DocWalks.sameName("Track","Walk"))
    }
    @Test fun ownNameThenMostSpecificLargeWalkThenMostSpecificWalk(){
        fun p(lon:Double)=VectorTile.Point((lon+180)/360,CameraMath.mercatorY(-44.3))
        val track=(0..20).map{p(169.0+it*.001)}
        fun line(guid:String,km:Double,whole:Boolean=false)=DocWalks.Line(guid,km*1000,listOf((0..40).map{p(168.99+it*.001)}),whole)
        val infos=mapOf("circuit" to DocWalks.Info("circuit","Big Pass Circuit","","3 days",""),"trail" to DocWalks.Info("trail","Long Te Araroa Route","","8 days",""),
            "valley" to DocWalks.Info("valley","Side Valley tracks","","4 days",""),"own" to DocWalks.Info("own","Siberia Track","","2 hr",""))
        // A section of a circuit carries the circuit's name (not the longer through-trail's).
        val (a,named)=DocWalks.walkFor(track,"Siberia Track",DocWalks.Index(listOf(line("circuit",50.0),line("trail",120.0))),infos)!!
        assertEquals("circuit",a.guid);assertTrue(named)
        // Its own DOC walk wins, under its own name.
        val (b,namedB)=DocWalks.walkFor(track,"Siberia Track",DocWalks.Index(listOf(line("circuit",50.0),line("own",5.0))),infos)!!
        assertEquals("own",b.guid);assertFalse(namedB)
        // DOC's whole line of a large walk (side trips) never renames: a collection opens instead.
        val (c,namedC)=DocWalks.walkFor(track,"Upper Valley Track",DocWalks.Index(listOf(line("circuit",50.0,whole=true),line("valley",30.0))),infos)!!
        assertEquals("valley",c.guid);assertFalse(namedC)
    }
    @Test fun nothingChangesWithoutWalkData(){
        val features=tiles.values.first()
        assertSame(features,DocWalks.mark(features,null,infos))
        assertSame(features,DocWalks.mark(features,index,emptyMap()))
    }
}
