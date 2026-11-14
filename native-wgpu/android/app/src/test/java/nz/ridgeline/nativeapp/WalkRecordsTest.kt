package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test

class WalkRecordsTest{
    @Test fun linesRoundTripToATenthOfAMetre(){
        val line=listOf(Coordinate(168.656166,-45.028835),Coordinate(168.648119,-45.026844),Coordinate(168.615440,-45.007140),Coordinate(-0.000001,0.000001))
        val back=WalkRecords.decode(WalkRecords.encode(line))
        assertEquals(line.size,back.size)
        for((a,b) in line.zip(back))assertTrue(DocTracks.meters(a,b)<.2)
    }
    @Test fun parsesVerifiedAndFlaggedWalks(){
        val line=WalkRecords.encode(listOf(Coordinate(168.0,-45.0),Coordinate(168.01,-45.0)))
        val json="""{"v":1,"walks":{"g1":{"status":"verified","kind":"loop","flags":[],"ways":[{"label":"Loop","from":"Car park","to":"Car park","shape":"loop","main":true,"time":"1 hr 30 min","distance":"4.5 km loop","line":"$line","stops":[{"name":"Car park","at":0},{"name":"Summit","at":400},{"name":"Car park","at":790}]}],"huts":[{"name":"Greenstone Hut","lon":168.26,"lat":-44.98,"link":"https://www.doc.govt.nz/link/x.aspx"}]},
            "g2":{"status":"flagged","kind":"one_way","flags":["main way: start: X is not placed"],"ways":[],"huts":[]}}}"""
        val r=WalkRecords.parse(json)
        val a=r["g1"]!!;assertTrue(a.verified);assertEquals(1,a.ways.size);assertEquals("loop",a.ways[0].shape);assertEquals(2,a.ways[0].line.size)
        assertEquals(listOf("Car park","Summit","Car park"),a.ways[0].stops.map{it.first});assertEquals("Greenstone Hut",a.huts[0].name)
        val b=r["g2"]!!;assertFalse(b.verified);assertTrue(b.ways.isEmpty());assertEquals(1,b.flags.size)
    }
    /** The asset the app ships: it must be at the name the app opens and parse. */
    @Test fun shippedRecordsLoad(){
        val r=WalkRecords.parse(java.io.File("src/main/assets/${WalkRecords.ASSET}").readText())
        assertTrue(r.size>100);assertTrue(r.values.count{it.verified}>50)
        for(w in r.values.filter{it.verified}){assertTrue(w.ways.count{it.main}==1);assertTrue(w.ways.all{it.line.size>=2})}
        assertEquals(setOf("Mid Caples Hut","Mckellar Hut","Greenstone Hut"),r["cb036644-8249-42e1-9485-4614f7cdcc1d"]!!.huts.map{it.name}.toSet())
        // DOC's hut data lists many huts twice; each named hut must still be found once.
        assertEquals(setOf("Shelter Rock Hut","Dart Hut","Daleys Flat Hut"),r["e57f2343-e606-41ff-9ccc-9c8cc0df1824"]!!.huts.map{it.name}.toSet())
        assertEquals(3,r["e57f2343-e606-41ff-9ccc-9c8cc0df1824"]!!.huts.size)
        // Gillespie Pass Circuit: the loop from Makarora, fording the Makarora both ways and out
        // along Wilkin Road (DOC: 56 km), with its fly-out and jet-boat exits.
        val gillespie=r["515e0210-ba1a-4750-8297-a892aba9a23e"]!!
        assertTrue(gillespie.verified)
        val loop=gillespie.ways.single{it.main}
        assertEquals("loop",loop.shape);assertEquals(55.7,DocTracks.length(loop.line)/1000,1.0)
        assertEquals(2,loop.stops.count{it.first.endsWith("(ford)")});assertTrue(loop.stops.any{it.first=="Wilkin Road (road)"})
        assertEquals(setOf("Young Hut","Siberia Hut","Kerin Forks Hut"),gillespie.huts.map{it.name}.toSet())
        assertEquals(setOf("Fly out of Siberia Valley","Jet boat from Kerin Forks"),gillespie.ways.filter{!it.main}.map{it.to}.toSet())
        // Ben Lomond: from the gondola top as well as from town, to the summit.
        val ben=r["7a9c364e-36aa-4b4b-af32-dcdfeda9ebd1"]!!
        assertTrue(ben.ways.any{it.from=="Skyline Gondola top complex"&&it.to=="Ben Lomond summit"})
        assertTrue(ben.ways.single{it.main}.let{it.from=="Skyline Gondola base"&&it.to=="Ben Lomond summit"})
    }
}
