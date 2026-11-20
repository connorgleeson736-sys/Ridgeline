package nz.ridgeline.nativeapp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WalkOptionsTest{
    private fun pts(a:JSONArray)=(0 until a.length()).map{val p=a.getJSONArray(it);Coordinate(p.getDouble(0),p.getDouble(1))}
    private fun area(dir:String):WalkOptions.Area{val j=JSONObject(File("$dir/area.json").readText())
        val t=j.getJSONArray("tracks");val l=j.getJSONArray("lifts");val p=j.getJSONArray("peaks")
        return WalkOptions.Area((0 until t.length()).map{t.getJSONObject(it).let{o->o.getString("name") to pts(o.getJSONArray("points"))}},
            (0 until l.length()).map{pts(l.getJSONArray(it))},(0 until p.length()).map{p.getJSONObject(it).let{o->o.getString("name") to pts(JSONArray().put(o.getJSONArray("point")))[0]}},null,
            j.optJSONArray("roads")?.let{r->(0 until r.length()).map{r.getJSONObject(it).let{o->o.getString("name") to pts(o.getJSONArray("points"))}}}.orEmpty())}
    private val gazetteer by lazy{File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}}
    private fun locate(n:String,c:Coordinate)=gazetteer.locate(n,c,15.0)?.let{Coordinate(it.longitude,it.latitude)}

    /** DOC's Ben Lomond Track: three legs from two starts to two ends; DOC's
     * line runs only from the gondola top to the saddle, and the map names
     * the way Tiki Track, Ben Lomond Walkway and Ben Lomond Track. */
    @Test fun benLomondStartsAndEnds(){
        val dir="src/test/fixtures/benlomond"
        val legs=DocPages.legs(DocPages.parse(File("$dir/page.html").readText()))
        assertTrue(WalkOptions.alternatives(legs))
        val c=WalkOptions.choose(legs,DocTracks.parseLines(File("$dir/line.json").readText()),area(dir),::locate)!!
        assertEquals(listOf("Tiki Trail","Skyline Gondola top complex"),c.starts)
        assertEquals(listOf("Ben Lomond Summit","Ben Lomond Saddle"),c.ends)
        assertEquals(4,c.options.size)
        val summit=Coordinate(168.61544,-45.00714)
        for((s,e,km) in listOf(Triple(0,0,7.0),Triple(0,1,5.0),Triple(1,1,3.5))){val o=c.option(s,e)!!
            assertNotNull(o.leg);assertEquals(km*1000,DocTracks.length(o.line),km*1000*WalkOptions.MATCH)}
        // To the summit, not the saddle; the first leg DOC lists first.
        assertTrue(DocTracks.meters(c.option(0,0)!!.line.last(),summit)<100)
        assertEquals(0,c.options[0].start);assertEquals(0,c.options[0].end)
        // The gondola top to the summit (not a DOC leg) goes over the saddle.
        val combined=c.option(1,0)!!;assertNull(combined.leg)
        assertTrue(DocTracks.distanceTo(combined.line,c.option(1,1)!!.line.last())<30)
    }
    /** Legs that follow one another (a multi-day walk) are one route, not options. */
    @Test fun chainedLegsAreNotOptions(){
        val legs=DocPages.legs(DocPages.parse(File("src/test/fixtures/docpages/gillespie-pass-circuit.html").readText()))
        assertFalse(WalkOptions.alternatives(legs))
        assertNull(WalkOptions.choose(legs,emptyList(),WalkOptions.Area(listOf("A" to listOf(Coordinate(169.0,-44.0),Coordinate(169.01,-44.0)))),::locate))
    }
    private fun east(lon0:Double,m:Double)=Coordinate(lon0+m/(111_320.0*Math.cos(Math.toRadians(-44.0))),-44.0)
    /** A walk's line stopping where the map's track changes name goes on to
     * the next junction, only when that brings it to DOC's length. */
    @Test fun extendsAcrossNameChangesToJunction(){
        val a=listOf(east(169.0,0.0),east(169.0,500.0),east(169.0,1000.0))
        val b=listOf(east(169.0,1000.0),east(169.0,1500.0),east(169.0,2000.0))
        // A junction at 2 km: two ways on.
        val north=listOf(east(169.0,2000.0),Coordinate(east(169.0,2000.0).longitude,-43.99))
        val south=listOf(east(169.0,2000.0),Coordinate(east(169.0,2000.0).longitude,-44.01))
        val area=WalkOptions.Area(listOf("Upper Track" to a,"Lower Walkway" to b,"N" to north,"S" to south))
        val out=WalkOptions.extend(a,area,2000.0)!!
        assertEquals(2000.0,DocTracks.length(out),5.0)
        // Not when the junction is still far short of DOC's length.
        assertNull(WalkOptions.extend(a,area,4000.0))
        // Not when the line is already about right.
        assertNull(WalkOptions.extend(a,area,1100.0))
    }
    /** DOC's Sealy Tarns page names its starts only in its text: "Start on the
     * Kea Point Track either outside the Aoraki/ Mount Cook National Park
     * Visitor Centre or at White Horse Hill car park". */
    @Test fun sealyTarnsStartsFromText(){
        val dir="src/test/fixtures/sealy"
        val sections=DocPages.parse(File("$dir/page.html").readText())
        val starts=DocPages.starts(sections)
        assertEquals(listOf("Aoraki/ Mount Cook National Park Visitor Centre","White Horse Hill car park"),starts.map{it.place})
        assertTrue(starts.all{it.track=="Kea Point Track"})
        val c=WalkOptions.fromStarts(starts,"Sealy Tarns Track","3 - 4 hr return",DocTracks.parseLines(File("$dir/line.json").readText()),area(dir),::locate)!!
        assertEquals(2,c.options.size);assertEquals(listOf("Sealy Tarns"),c.ends)
        val tarns=Coordinate(170.07433,-43.71353)
        for(o in c.options)assertTrue(DocTracks.meters(o.line.last(),tarns)<300)
        // From the village it is the longer walk, along the Kea Point Track.
        assertTrue(DocTracks.length(c.option(0,0)!!.line)>DocTracks.length(c.option(1,0)!!.line)+1000)
        assertTrue(c.option(0,0)!!.pieces.any{it.first=="Kea Point Track"})
    }
    /** "You can start the trip to Mt McKerrow from Wainuiomata or the Catchpool
     * Valley"; sentences about either end or direction name no places. */
    @Test fun startsInText(){
        fun starts(text:String)=DocPages.starts(listOf(DocPages.Section("Description",listOf(DocPages.Paragraph(text))))).map{it.place}
        assertEquals(listOf("Wainuiomata","Catchpool Valley"),starts("You can start the trip to Mt McKerrow from Wainuiomata or the Catchpool Valley in the Remutaka Forest Park."))
        assertEquals(listOf("Stirling Point","Gunpit road"),starts("Starting at either Stirling Point or Gunpit road, this track winds through diverse native bush."))
        assertEquals(listOf("Punchbowl","Bealey Chasm","Temple Basin car parks"),starts("Start at the Punchbowl, Bealey Chasm (Jacks Hut) or Temple Basin car parks to walk all or part of the track and return the same way."))
        assertEquals(emptyList<String>(),starts("The Three Passes trip can be started from either end, but the east to west route described here is recommended."))
        assertEquals(emptyList<String>(),starts("Return to your starting point via the footpath or retrace your steps along the track."))
    }
}
