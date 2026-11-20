package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Te Araroa offline downloads: the corridor along chosen sections, and saved alerts. */
class TeAraroaOfflineTest {
    private val assets=File(System.getProperty("ridgeline.assets")?:"src/main/assets","te-araroa")
    private val d=TeAraroa.parse(File(assets,"te-araroa.json").readText())
    @Test fun aSectionsCorridorIsDetailedNearTheTrailAndOverviewAroundIt(){
        val s=d.bySlug.getValue("cape-reinga-to-ahipara")
        val cells=TeAraroaOffline.cells(d,listOf(s),2)
        assertTrue(cells.values.any{it==2});assertTrue(cells.values.any{it==0})
        // Every point of the line is in a detailed cell.
        val line=d.line(s)
        for(p in line.filterIndexed{i,_->i%20==0}){
            val x=Math.floor((p.longitude+180)/360*PaintedArea.GRID).toInt();val y=Math.floor(CameraMath.mercatorY(p.latitude)*PaintedArea.GRID).toInt()
            assertEquals(2,cells[PaintedArea.key(x,y)])
        }
        // The overview band is wider than the detailed one (4 km against 1 km each side).
        assertTrue(cells.values.count{it==0}>cells.values.count{it==2})
    }
    @Test fun bypassesBesideTheChosenSectionsComeToo(){
        val opt=d.options.first();val main=d.mains.first{s->minOf(opt.to,s.to)-maxOf(opt.from,s.from)>.3}
        assertTrue(TeAraroaOffline.withBypasses(d,listOf(main)).any{it.slug==opt.slug})
        // Walking order: the next five from a section, southbound and northbound.
        val second=d.mains.sortedBy{it.from}[1]
        assertEquals(5,TeAraroaOffline.following(d,second,5,false).size)
        assertEquals(second.slug,TeAraroaOffline.following(d,second,5,true).first().slug)
        assertTrue(TeAraroaOffline.following(d,second,5,true).size<=2)
    }
    @Test fun anIslandFitsOneDownloadAtDetailedMapDetail(){
        for(island in listOf("NI","SI")){
            val sections=TeAraroaOffline.withBypasses(d,TeAraroaOffline.island(d,island))
            val tiles=PaintedArea.tilesOf(TeAraroaOffline.cells(d,sections,2)).size
            println("Te Araroa $island at zoom 15: $tiles tiles, ${sections.size} sections and bypasses")
            assertTrue("$island: $tiles tiles",tiles<=TileLoader.MAX_PAINTED_TILES)
        }
        val whole=PaintedArea.tilesOf(TeAraroaOffline.cells(d,TeAraroaOffline.withBypasses(d,d.mains),1)).size
        println("Te Araroa whole trail at zoom 14: $whole tiles")
    }
    @Test fun trustSectionDataIsKeptAsItsAlertsOnly(){
        val full="""{"data":{"track":{"points":[1,2,3]},"alerts":[{"title":"Shut","status":"Closure","fromKm":"5","toKm":null,"description":"<p>Bridge out</p>"}]}}"""
        val kept=TeAraroa.Alert.trustAlertsOnly(full)!!
        assertFalse(kept.contains("points"))
        assertEquals(TeAraroa.Alert.parseTrust(full).map{it.title},TeAraroa.Alert.parseTrust(kept).map{it.title})
        assertTrue(TeAraroa.Alert.parseTrust(TeAraroa.Alert.trustAlertsOnly("""{"data":{}}""")!!).isEmpty())
    }
    @Test fun liveDataIsSavedWithItsDate(){
        val dir=kotlin.io.path.createTempDirectory().toFile()
        val s=LiveSnapshots(dir)
        assertNull(s.load("https://example.org/a"))
        s.save("https://example.org/a","[1]");s.save("https://example.org/b","[2]")
        assertEquals("[1]",s.load("https://example.org/a")!!.first);assertEquals("[2]",s.load("https://example.org/b")!!.first)
        assertTrue(System.currentTimeMillis()-s.load("https://example.org/a")!!.second<60_000)
        dir.deleteRecursively()
    }
}
