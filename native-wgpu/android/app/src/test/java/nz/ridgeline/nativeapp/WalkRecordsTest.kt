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
}
