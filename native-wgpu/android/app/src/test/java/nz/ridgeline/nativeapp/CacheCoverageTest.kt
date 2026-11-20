package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class CacheCoverageTest {
    @Test fun parsesCachedImageryUrls(){
        assertEquals(Triple(15,32315,20825),CacheCoverage.parse("https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/15/32315/20825.webp?api=k"))
        assertEquals(Triple(14,1,2),CacheCoverage.parse("https://basemaps.linz.govt.nz/v1/tiles/topo-raster/WebMercatorQuad/14/1/2.webp?api=k"))
        assertEquals(Triple(16,700,300),CacheCoverage.parse("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/16/300/700"))
        assertNull(CacheCoverage.parse("https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/12/1/2.png?api=k"))
    }
    @Test fun finestStoredZoomWinsAndRegionsCount(){
        val c=CacheCoverage();val mx=0.97;val my=0.63
        assertEquals(-1,c.level(mx,my))
        c.addTile(12,(mx*4096).toInt(),(my*4096).toInt());assertEquals(12,c.level(mx,my))
        c.addTile(16,(mx*65536).toInt(),(my*65536).toInt());assertEquals(16,c.level(mx,my))
        val lon=mx*360-180;val lat=CameraMath.latitude(my)
        c.addRegion(TileLoader.Bounds(lon-.01,lat-.01,lon+.01,lat+.01),17);assertEquals(17,c.level(mx,my))
        assertEquals(-1,c.level(mx+.01,my))
    }
    @Test fun coloursRunFromRedToGreen(){
        fun rgb(z:Int)=CacheCoverage.color(z).let{Triple(it shr 16 and 255,it shr 8 and 255,it and 255)}
        val red=rgb(-1);val mid=rgb(13);val green=rgb(17)
        assertTrue(red.first>200&&red.second<60)
        assertTrue(green.second>200&&green.first<60)
        assertTrue(mid.first>150&&mid.second>100) // orange-yellow in between
        assertTrue((10..17).map{rgb(it).second}.zipWithNext().all{(a,b)->b>=a}) // continuous
        assertEquals(CacheCoverage.SIZE*CacheCoverage.SIZE*4,CacheCoverage().grid(.97,.63,.001).size)
    }
}
