package nz.ridgeline.nativeapp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The walk compiler on a small made-up map: track A from a road end north to Hut One
 * and east to a river; track B on the far bank (480 m across, no bridge) east to Hut
 * Two; Wilkin Road from Hut Two south to Hut Three. DOC's line is track A only. */
class WalkCompilerTest{
    private fun line(vararg p:Pair<Double,Double>):List<Coordinate>{
        val out=ArrayList<Coordinate>()
        for(i in 1 until p.size){val (x0,y0)=p[i-1];val (x1,y1)=p[i]
            for(k in 0 until 20)out.add(Coordinate(x0+(x1-x0)*k/20,y0+(y1-y0)*k/20))}
        out.add(Coordinate(p.last().first,p.last().second));return out
    }
    private val a=line(169.0 to -44.0,169.0 to -43.95,169.03 to -43.95)
    private val b=line(169.036 to -43.95,169.08 to -43.95)
    private val road=line(169.08 to -43.95,169.08 to -44.0)
    private val area=WalkOptions.Area(listOf("A Track" to a,"B Track" to b),roads=listOf("WILKIN ROAD" to road))
    private val packet=JSONObject("""{"guid":"g","name":"Test","time":"3-5 hr","distance":"","links":[],
        "features":[{"id":"F1","name":"where A Track meets a road","point":[169.0,-44.0]}],
        "names":[{"id":"T1","kind":"hut","name":"Hut One","point":[169.0005,-43.95]},
                 {"id":"T2","kind":"river","name":"Broad River","point":[169.033,-43.96]},
                 {"id":"T3","kind":"hut","name":"Hut Two","point":[169.0805,-43.95]},
                 {"id":"T4","kind":"hut","name":"Hut Three","point":[169.0805,-44.0]}]}""")
    private fun place(n:String,ref:String?,how:String?=null)=JSONObject().put("name",n).put("quote",n).put("ref",ref?:JSONObject.NULL).apply{if(how!=null)put("how",how)}
    private fun reading(via:List<JSONObject>,end:JSONObject,exits:List<JSONObject> = emptyList())=JSONObject().put("guid","g").put("kind","one_way")
        .put("ways",JSONArray().put(JSONObject().put("id","W1").put("main",true).put("label","Road end to the far hut").put("shape","one_way")
            .put("start",place("road end","F1")).put("end",end).put("via",JSONArray(via)).put("direction",JSONObject.NULL)
            .put("time",JSONObject.NULL).put("distance",JSONObject.NULL).put("evidence",JSONArray().put("x"))))
        .put("access_points",JSONArray()).put("exits",JSONArray(exits)).put("side_trips",JSONArray()).put("huts",JSONArray()).put("not_on_route",JSONArray()).put("doubts","")
    private fun compile(r:JSONObject)=WalkCompiler.compile(WalkCompiler.Input(packet,r,listOf(a),area,PlaceSearch(emptySequence()),{emptyList()}))

    @Test fun aFordIsCrossedOnlyWhereThePageSaysSo(){
        val without=compile(reading(listOf(place("Hut One","T1"),place("Broad River","T2")),place("Hut Two","T3")))
        assertFalse(without.verified);assertTrue(without.flags.any{"cannot be reached" in it})
        val with=compile(reading(listOf(place("Hut One","T1"),place("Broad River","T2","ford")),place("Hut Two","T3")))
        assertTrue(with.flags.toString(),with.verified)
        val w=with.ways.single{it.main}
        assertEquals(listOf("road end","Hut One","Broad River (ford)","Hut Two"),w.stops.map{it.first})
        // The crossing is the 480 m gap, not a longer way: 5.6 + 2.4 + 0.5 + 3.5 km.
        assertEquals(12.0,DocTracks.length(w.line)/1000,.1)
    }
    @Test fun aRoadIsWalkedOnlyWhereThePageSaysSo(){
        val via=listOf(place("Hut One","T1"),place("Broad River","T2","ford"),place("Hut Two","T3"))
        val without=compile(reading(via+place("Wilkin Road",null),place("Hut Three","T4")))
        assertFalse(without.verified)
        val with=compile(reading(via+place("Wilkin Road",null,"road"),place("Hut Three","T4")))
        assertTrue(with.flags.toString(),with.verified)
        assertTrue(with.ways.single{it.main}.stops.any{it.first=="Wilkin Road (road)"})
        assertTrue(DocTracks.meters(with.ways.single{it.main}.line.last(),Coordinate(169.08,-44.0))<50)
    }
    @Test fun anExitIsAWayToWhereThePageLeavesTheWalk(){
        val r=reading(listOf(place("Hut One","T1"),place("Broad River","T2","ford")),place("Hut Two","T3"),
            listOf(JSONObject().put("name","Hut One").put("quote","Hut One").put("ref","T1").put("from","Hut One").put("how","fly")))
        val res=compile(r);assertTrue(res.flags.toString(),res.verified)
        val exit=res.ways.single{!it.main}
        assertEquals("Hut One (fly out)",exit.to);assertEquals("road end",exit.from)
        assertTrue(DocTracks.meters(exit.line.last(),Coordinate(169.0,-43.95))<50)
        assertEquals(5.6,DocTracks.length(exit.line)/1000,.2)
    }
}
