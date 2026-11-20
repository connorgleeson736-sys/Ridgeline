package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StoredTileIndexTest {
    private fun url(z:Int,x:Int,y:Int)="https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/$z/$x/$y.webp?api=k"
    @Test fun recordsSavesLoadsAndFeedsCoverage(){
        val file=File.createTempFile("stored",".bin");file.delete()
        try{
            val index=StoredTileIndex(file)
            index.add(url(15,32270,20650));index.add(url(15,32270,20650));index.add("https://example.com/not-a-tile")
            index.add("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/14/10325/16135")
            assertEquals(2,index.size);index.save();assertTrue(file.exists())
            val loaded=StoredTileIndex(file);loaded.load();assertEquals(2,loaded.size)
            val coverage=CacheCoverage();loaded.copyInto(coverage)
            assertEquals(15,coverage.level((32270+.5)/32768,(20650+.5)/32768))
            // A full scan replaces entries the cache no longer holds.
            loaded.replace(listOf(url(16,1,1)).iterator());assertEquals(1,loaded.size)
        }finally{file.delete()}
    }
    @Test fun aCorruptFileIsIgnored(){
        val file=File.createTempFile("stored",".bin");file.writeBytes(byteArrayOf(1,2,3))
        try{val index=StoredTileIndex(file);index.load();assertEquals(0,index.size)}finally{file.delete()}
    }
}
