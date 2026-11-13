package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Following DOC's legs over DOC's line, on the real Gillespie Pass Circuit:
 * DOC's line also runs up the Wilkin to Top Forks Hut (an extension, not
 * part of the circuit), and the circuit starts and ends near Makarora. */
class WalkRoutesTest {
    private val page=DocPages.parse(File("src/test/fixtures/docpages/gillespie-pass-circuit.html").readText())
    private val legs=DocPages.legs(page)
    private val line=DocTracks.parseLines(File("src/test/fixtures/gillespie/doc-walk-line-gillespie-pass-circuit.json").readText())
    private val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
    private fun locate(name:String,near:Coordinate)=places.locate(name,near,40.0)?.let{Coordinate(it.longitude,it.latitude)}

    @Test fun readsDocsLegsWithTimesAndDistances(){
        assertEquals(listOf("Makarora to Young Hut","Young Hut to Siberia Hut","Siberia Hut to Makarora","Siberia Hut to Kerin Forks Hut","Kerin Forks to Makarora – SH6"),legs.map{it.title})
        assertEquals("6 to 7 hr",legs[0].time);assertEquals(20.0,legs[0].km!!,0.0)
        assertEquals(12.0,legs[1].km!!,0.0)
        assertTrue(legs[1].blocks.first().text.startsWith("Climb 1 hour to the bushline"))
        // The summary leg is not walked twice.
        assertEquals(listOf("Makarora to Young Hut","Young Hut to Siberia Hut","Siberia Hut to Kerin Forks Hut","Kerin Forks to Makarora – SH6"),WalkRoutes.walked(legs).map{it.title})
        assertEquals("Makarora",WalkRoutes.plain("Makarora – SH6"))
        assertEquals("Kerin Forks to Makarora",WalkRoutes.title("Kerin Forks to Makarora – SH6"))
        assertEquals("Lewis Pass to Ada Pass Hut",WalkRoutes.title("Lewis Pass - Cannibal Gorge Hut - Ada Pass Hut"))
    }
    @Test fun followsTheCircuitNotTheTopForksExtension(){
        val route=WalkRoutes.route(line,legs,::locate)!!
        assertTrue("circuit ${route.meters} m",route.meters in 45_000.0..60_000.0)
        // Top Forks Hut is at about 168.89 E; the circuit never goes west of Kerin Forks (169.03 E).
        assertTrue(route.points.all{it.longitude>169.0})
        // DOC's direction: from the Young/Makarora confluence (north-east) up the Young first.
        val start=route.points.first();val end=route.points.last()
        assertTrue("starts at ${start}",start.latitude>-44.2&&start.longitude>169.25)
        assertTrue("ends at ${end}",end.latitude<-44.25)
        val young=route.sections.single{it.title=="Makarora to Young Hut"}
        assertEquals(20_000.0,young.to-young.from,5_000.0)
        val siberia=route.sections.single{it.title=="Young Hut to Siberia Hut"}
        assertEquals(12_000.0,siberia.to-siberia.from,4_000.0)
        assertEquals(young.to,siberia.from,1.0)
        val summary=route.sections.single{it.title=="Siberia Hut to Makarora"}
        assertEquals(route.meters,summary.to,1.0)
        assertEquals(5,route.sections.size)
    }
    private fun leg(title:String,km:Double?,time:String=""):DocPages.Leg{val stops=title.split(" to ");return DocPages.Leg(stops.first(),stops.last(),title,time,km,emptyList(),stops)}
    @Test fun walksAlternativesTheWayDocDescribesThem(){
        // Abel Tasman style: a summary leg and its parts; two endings from one bay.
        val legs=listOf(leg("Marahau to Anchorage",12.4),leg("Anchorage to Bark Bay",11.5),leg("Bark Bay to Awaroa",13.5),leg("Awaroa to Whariwharangi Bay",16.0),
            leg("Awaroa to Tōtaranui",7.1),leg("Tōtaranui to Whariwharangi Bay",7.4),leg("Whariwharangi Bay to Wainui",5.7),leg("Whariwharangi Bay to Tōtaranui",7.4))
        assertEquals(listOf("Marahau to Anchorage","Anchorage to Bark Bay","Bark Bay to Awaroa","Awaroa to Tōtaranui","Tōtaranui to Whariwharangi Bay","Whariwharangi Bay to Wainui"),WalkRoutes.walked(legs).map{it.title})
        // Without distances, the shorter time is the finer leg.
        val passes=listOf(leg("SH73 to Carrington Hut",null,"4–5 hours one way"),leg("Carrington Hut to Park Morpeth Memorial Hut",null,"9–10 hours one way"),leg("Carrington Hut to Harman Pass",null,"3 hours one way"),leg("Harman Pass to Park Morpeth Memorial Hut",null,"6 hours"))
        assertEquals(listOf("SH73 to Carrington Hut","Carrington Hut to Harman Pass","Harman Pass to Park Morpeth Memorial Hut"),WalkRoutes.walked(passes).map{it.title})
        assertEquals(3.5,WalkRoutes.hours("3 hr 30 min"),0.6);assertEquals(.5,WalkRoutes.hours("30 min"),.01)
    }
    @Test fun readsLegsWrittenWithDashesAndSeveralStops(){
        val html="""<doc-accordion-item header-text="Description"><div class="block textblock x"><h3>Places to stay</h3><p>Huts: five.</p>
            <h3>Lewis Pass - Cannibal Gorge Hut - Ada Pass Hut</h3><p>Time: 3 hr 30 min to Cannibal Gorge Hut, 5 hr to Ada Pass Hut<br>Distance: 7 km to Cannibal Gorge Hut, 10 km to Ada Pass Hut</p><p>The track leaves the picnic area.</p>
            <h3>Side trip: Tarn Nature Walk</h3><p>Time: 20 min</p>
            <h3>Ada Pass Hut - Christopher Hut</h3><p>Time: 4 hr<br>Distance: 11 km</p></div></doc-accordion-item>"""
        val legs=DocPages.legs(DocPages.parse(html))
        assertEquals(2,legs.size)
        assertEquals(listOf("Lewis Pass","Cannibal Gorge Hut","Ada Pass Hut"),legs[0].stops);assertEquals(10.0,legs[0].km!!,0.0)
        assertEquals("The track leaves the picnic area.",legs[0].blocks.single().text)
        assertEquals(listOf("Lewis Pass - Cannibal Gorge Hut - Ada Pass Hut","Ada Pass Hut - Christopher Hut"),WalkRoutes.walked(legs).map{it.title})
        // "Track description" (Stewart Island) is a description section too.
        assertEquals(1,DocPages.legs(DocPages.parse(html.replace("header-text=\"Description\"","header-text=\"Track description\""))).size.coerceAtMost(1))
    }
    @Test fun noLegsNoRoute(){
        assertNull(WalkRoutes.route(line,emptyList(),::locate))
    }
}
