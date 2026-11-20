package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Te Araroa's bundled data (assets/te-araroa) and the walker's views of it. */
class TeAraroaTest{
    private val assets=File(System.getProperty("ridgeline.assets")?:"src/main/assets","te-araroa")
    private val d=TeAraroa.parse(File(assets,"te-araroa.json").readText())

    @Test fun decodesTheTrail(){
        assertTrue(d.total>3000&&d.total<3200)
        assertTrue(d.pieces.size>400)
        assertEquals(93,d.sections.size);assertTrue(d.mains.size>=75);assertTrue(d.options.size>=10)
        assertEquals("cape-reinga-to-ahipara",d.mains.minByOrNull{it.from}!!.slug)
        // The first piece starts at Cape Reinga, the far north.
        val cape=d.pieces.first().line.first();assertTrue(cape.latitude>-34.6&&cape.longitude<173.0)
        assertTrue(d.stops.size>=40);assertTrue(d.shops.size>=500);assertTrue(d.places.size>=200)
    }
    @Test fun sectionLinesAreContinuousAndSouthbound(){
        val s=d.bySlug.getValue("cape-reinga-to-ahipara");val line=d.line(s)
        assertTrue(line.size>100)
        assertTrue(line.first().latitude>line.last().latitude) // southbound
        val opt=d.options.first();assertTrue(d.lines(opt).isNotEmpty())
    }
    @Test fun directionTurnsKilometresAndOrder(){
        assertEquals(100.0,d.kmIn(100.0,false),1e-9)
        assertEquals(d.total-100.0,d.kmIn(100.0,true),1e-9)
        val second=d.mains.sortedBy{it.from}[1]
        val (prevS,nextS)=d.neighbours(second,false);val (prevN,nextN)=d.neighbours(second,true)
        assertEquals(prevS,nextN);assertEquals(nextS,prevN)
        assertNull(d.neighbours(d.mains.minByOrNull{it.from}!!,false).first)
    }
    @Test fun foodCarriesCoverTheTrailInWalkingOrder(){
        val sobo=d.carries(false);val nobo=d.carries(true)
        assertEquals(sobo.size,nobo.size);assertTrue(sobo.size>=30)
        assertEquals(sobo.first().from,nobo.last().to)
        // Waiau Pass: the longest carry is in the South Island, over 100 km.
        assertTrue(sobo.maxOf{it.km}>100)
        val hamilton=d.bySlug.values.first{it.name=="Hamilton City"}
        assertEquals("Hamilton",d.nextFood(hamilton,false).first?.name)
    }
    @Test fun findsSectionsAlongALineAndNamePlaces(){
        val s=d.bySlug.values.first{it.name=="Kerikeri to Waitangi"};val line=d.line(s)
        assertEquals(listOf(s.slug),d.sectionsAlong(line.subList(line.size/4,line.size*3/4)).map{it.slug})
        val names=d.namePoints();assertEquals(listOf("north","south"),names.map{it.first})
        assertTrue(names[0].second.latitude<-38.0&&names[0].second.latitude>-40.0)
    }
    @Test fun readsNotesAndAlerts(){
        val notes=TeAraroa.Notes.parseAll(GZIPInputStream(File(assets,"notes.json.gz").inputStream()).bufferedReader().readText())
        assertTrue(notes.size>=80);assertTrue(notes.getValue("cape-reinga-to-ahipara").intro.isNotBlank())
        val trust=TeAraroa.Alert.parseTrust("""{"data":{"alerts":[{"title":"Info","status":"Information","fromKm":"5","toKm":null,"description":"<p>Note</p>"},{"title":"Shut","status":"Closure","description":"<p>Bridge <b>out</b>&nbsp;now<img src=x></p>"}]}}""")
        assertEquals(listOf("Shut","Info"),trust.map{it.title});assertEquals("Bridge out now",trust[0].text);assertEquals(5.0,trust[1].from!!,1e-9)
        val doc=TeAraroa.Alert.parseDoc("""[{"summary":"Track closed","description":"<p>Slip.</p><ul><li>Detour</li></ul>","displayDate":"1 Nov","subText":""}]""")
        assertEquals("Track closed",doc[0].title);assertEquals("Slip.\n\n• Detour",doc[0].text)
        assertTrue(TeAraroa.Alert.parseDoc("<html>").isEmpty())
    }
    @Test fun daysAndOptionKinds(){
        assertEquals(2.0 to 3.0,TeAraroa.daysOf("2-3 days"))
        assertEquals("≈ 2–3 days",TeAraroa.daysText(2.0 to 3.0));assertEquals("under a day",TeAraroa.daysText(.2 to .5))
        assertEquals("Flood bypass",TeAraroa.optionKind(d.options.first{it.name.contains("Hazard Zone",true)}).first)
    }
    @Test fun nameCurvesAreSmoothAndLongEnough(){
        val curves=d.nameCurves(24)
        assertEquals(listOf("north","south"),curves.map{it.first})
        for((_,bands) in curves){assertEquals(3,bands.size)
            for((band,line) in bands.withIndex()){assertEquals(24,line.size)
                val km=DocTracks.length(line)/1000;assertTrue("band $band: $km km",km>=listOf(480.0,135.0,36.0)[band]*.95)
                // No sharp turns between neighbouring points.
                for(i in 1 until line.size-1){val a=Math.atan2(line[i].latitude-line[i-1].latitude,line[i].longitude-line[i-1].longitude);val b=Math.atan2(line[i+1].latitude-line[i].latitude,line[i+1].longitude-line[i].longitude)
                    var t=Math.abs(a-b);if(t>Math.PI)t=2*Math.PI-t;assertTrue(t<.5)}}}
        // The North Island's name about the island's middle (Tongariro to Taumarunui).
        val mid=curves[0].second[2][12];assertTrue(mid.latitude<-38.5&&mid.latitude>-39.6)
    }
}
