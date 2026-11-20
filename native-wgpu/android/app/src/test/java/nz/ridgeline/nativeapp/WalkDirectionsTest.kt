package nz.ridgeline.nativeapp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WalkDirectionsTest{
    private fun pts(a:JSONArray)=(0 until a.length()).map{val p=a.getJSONArray(it);Coordinate(p.getDouble(0),p.getDouble(1))}
    private class Walk(val sections:List<DocPages.Section>,val overview:DocPages.Overview,val line:List<List<Coordinate>>,val area:WalkOptions.Area,val places:List<Triple<String,String,Coordinate>>)
    private fun walk(name:String):Walk{
        val dir="src/test/fixtures/directions/$name";val j=JSONObject(File("$dir/area.json").readText());val html=File("$dir/page.html").readText()
        fun named(k:String)=j.getJSONArray(k).let{a->(0 until a.length()).map{a.getJSONObject(it).let{o->o.getString("name") to pts(o.getJSONArray("points"))}}}
        val peaks=j.getJSONArray("peaks").let{a->(0 until a.length()).map{a.getJSONObject(it).let{o->o.getString("name") to pts(JSONArray().put(o.getJSONArray("point")))[0]}}}
        val places=j.getJSONArray("places").let{a->(0 until a.length()).map{a.getJSONObject(it).let{o->Triple(o.getString("kind"),o.getString("name"),pts(JSONArray().put(o.getJSONArray("point")))[0])}}}
        val box=j.getJSONArray("box").let{a->DoubleArray(4){a.getDouble(it)}}
        return Walk(DocPages.parse(html),DocPages.overview(html),DocTracks.parseLines(File("$dir/line.json").readText()),WalkOptions.Area(named("tracks"),emptyList(),peaks,box,named("roads")),places)
    }
    private fun route(w:Walk)=WalkDirections.route(w.sections,w.overview.title,w.overview.distance,w.overview.time,w.line,w.area,w.places,WalkRoutes.walkLine(null,w.line,w.overview.distance))

    /** "Taking the loop clockwise, cross the bridge … to Elfin Bay … Lake Rere …
     * join the Greenstone Track. To finish this loop continue on Greenstone
     * Track back to the start of the Lake Rere Walk." DOC's line has only the
     * loop's own track; the way back is on the Greenstone Track. */
    @Test fun lakeRereLoopFollowsTheDescription(){
        val w=walk("lake-rere");val r=route(w)!!
        assertTrue(r.loop);assertEquals(listOf("Elfin Bay","Lake Rere","Greenstone Track"),r.waypoints)
        assertTrue(DocTracks.meters(r.line.first(),r.line.last())<50)
        assertEquals(14_000.0,DocTracks.length(r.line),14_000*WalkOptions.MATCH)
        assertTrue(r.pieces.any{it.first=="Greenstone Track"})
        // Clockwise: negative signed area (east right, north up).
        val k=Math.cos(Math.toRadians(r.line[0].latitude));var a=0.0
        for(i in r.line.indices){val p=r.line[i];val q=r.line[(i+1)%r.line.size];a+=p.longitude*k*q.latitude-q.longitude*k*p.latitude}
        assertTrue(a<0)
        // From the car park: its start is near a road.
        assertTrue(w.area.roads.any{DocTracks.distanceTo(it.second,r.line.first())<300})
    }
    /** An out-and-back route goes on to its far end (the saddle, named only in the walk's own name), past Black Lake. */
    @Test fun outAndBackGoesToTheFarEnd(){
        val w=walk("gertrude-saddle");val r=route(w)!!
        assertFalse(r.loop)
        val far=w.line.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.maxByOrNull{DocTracks.meters(it,r.line.first())}!!
        assertTrue(DocTracks.meters(r.line.last(),far)<200)
    }
    @Test fun mentionsInOrderWithoutViewsOrPassingTracks(){
        val s=listOf("Taking the loop clockwise, cross the bridge to Elfin Bay.","Climb to Lake Rere, with views of Mount Earnslaw.","The Greenstone Track is nearby.","Join the Greenstone Track and continue back to the start of the Lake Rere Walk.")
        val m=WalkDirections.mentions(s,listOf("Elfin Bay","Lake Rere","Mount Earnslaw","Greenstone Track"),setOf("Greenstone Track"),listOf("Lake Rere Walk"))
        assertEquals(listOf("Elfin Bay","Lake Rere","Greenstone Track"),m)
    }
    @Test fun sideTripsAndOptionsAreNotDirections(){
        val sec=listOf(DocPages.Section("Description",listOf(DocPages.Paragraph("Follow the track to the hut. Alternatively, return via the ridge."),DocPages.Heading("Side trip"),DocPages.Paragraph("Climb to the summit."))))
        assertEquals(listOf("Follow the track to the hut."),WalkDirections.sentences(sec))
    }
    @Test fun clockwiseFromText(){
        fun way(t:String)=DocPages.clockwise(listOf(DocPages.Section("Description",listOf(DocPages.Paragraph(t)))))
        assertEquals(true,way("Taking the loop clockwise, cross the bridge."))
        assertEquals(false,way("Walk the loop anti-clockwise."))
        assertEquals(false,way("It can be walked clockwise or anticlockwise but is described here anticlockwise."))
        assertNull(way("A loop through beech forest."))
    }
}
