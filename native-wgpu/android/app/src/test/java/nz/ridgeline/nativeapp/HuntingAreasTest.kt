package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real DOC responses (CC BY 4.0, Department of Conservation) for two tiles near Wanaka. */
class HuntingAreasTest {
    private val fixtures get()=File(requireNotNull(System.getProperty("ridgeline.fixtures")),"hunting")
    @Test fun decodesDocTileAttributesAndOutlines(){
        val areas=HuntingAreas.decode(File(fixtures,"doc-hunting-13-7939-5225.pbf").readBytes())
        assertEquals(4,areas.size)
        val albert=areas.single{it.name=="Upper Albert Burn"}
        assertEquals("Hawea Conservation Park/Lake Wanaka/Lower Matukituki Valley",albert.permitArea)
        assertEquals("Roar",albert.type);assertTrue(albert.webPage.startsWith("https://www.doc.govt.nz/"))
        assertEquals(1,albert.rings.size);assertEquals(133,albert.rings[0].size)
        // DOC's first vertex for this area (EPSG:3857 18799925.5, -5513624.4), to within a few metres.
        val p=albert.rings[0][0];val half=20037508.342789244
        assertEquals(18799925.5,(p.x*2-1)*half,6.0);assertEquals(-5513624.4,(1-p.y*2)*half,6.0)
        // Every outline lies in the Wanaka area.
        for(a in areas)for(r in a.rings)for(q in r){assertTrue(q.x*360-180 in 168.0..170.0);assertTrue(CameraMath.latitude(q.y) in -45.0..-43.5)}
    }
    @Test fun pointInAreaAndAnchorsInside(){
        val areas=HuntingAreas.decode(File(fixtures,"doc-hunting-11-1984-1306.pbf").readBytes())
        assertTrue(areas.isNotEmpty())
        for(a in areas){val p=a.anchor?:continue;assertTrue("${a.name} anchor inside",a.contains(p.x,p.y))}
        assertFalse(areas.any{it.contains(0.0,0.0)})
    }
    @Test fun requestsPermittedAreasOnlyInTileMode(){
        val url=HuntingAreas.url(13,7939,5225)
        assertTrue(HuntingAreas.isUrl(url));assertTrue(url.contains("HuntStatus%3D%27Permitted%27"))
        assertTrue(url.contains("resultType=tile"));assertTrue(url.endsWith("&f=pbf"))
        assertEquals(8,HuntingAreas.dataZoom(9.0));assertEquals(11,HuntingAreas.dataZoom(12.0));assertEquals(13,HuntingAreas.dataZoom(15.0))
    }
    @Test fun rejectsGarbage(){
        try{HuntingAreas.decode(byteArrayOf(0x12,0x7f,1));fail("truncated data must fail")}catch(_:IllegalArgumentException){}
    }
}
