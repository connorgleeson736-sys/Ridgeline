package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

class PreparedTerrainCacheTest {
    private val id=GpuUploadQueue.Id(12,3982,2600)
    private val dep=PreparedTerrainCache.Dependency(GpuUploadQueue.Id(11,1991,1300),"a".repeat(64))
    private fun payload()=ByteBuffer.allocateDirect(66580).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(16641){putFloat(it*.03125f)};putDouble(.6);putDouble(.9);rewind()}
    @Test fun losslessRestartFreshnessNamespaceAndCorruption(){
        val dir=Files.createTempDirectory("terrain-cache").toFile()
        try{
            val cache=PreparedTerrainCache(dir,1000000,"policy-v1")
            val input=payload();cache.put(id,input,listOf(dep));assertEquals(0,input.position())
            val restarted=PreparedTerrainCache(dir,1000000,"policy-v1")
            val restored=restarted.get(id){it==dep}!!
            for(i in 0 until input.capacity())assertEquals(input.get(i),restored.get(i))
            assertNull(PreparedTerrainCache(dir,1000000,"policy-v2").get(id){true})
            assertNull(restarted.get(id){false}) // Changed source cannot return stale data.
            cache.put(id,input,listOf(dep));dir.listFiles()!!.single().writeBytes(byteArrayOf(1,2,3))
            assertNull(PreparedTerrainCache(dir,1000000,"policy-v1").get(id){true})
        }finally{dir.deleteRecursively()}
    }
    @Test fun cacheIsBoundedAndReadsRefreshRecency(){
        val dir=Files.createTempDirectory("terrain-bound").toFile()
        try{
            val probe=PreparedTerrainCache(dir,1000000,"v1");probe.put(id,payload(),listOf(dep));val one=probe.sizeBytes()
            val cache=PreparedTerrainCache(dir,one*2+100,"v1")
            val b=id.copy(x=id.x+1);val c=id.copy(x=id.x+2)
            cache.put(b,payload(),listOf(dep));assertNotNull(cache.get(id){true});cache.put(c,payload(),listOf(dep))
            assertTrue(cache.sizeBytes()<=one*2+100);assertNull(cache.get(b){true});assertNotNull(cache.get(id){true})
        }finally{dir.deleteRecursively()}
    }
    @Test fun completedRgbaCompositionRestoresEveryChannelWithoutRecomposition(){
        val dir=Files.createTempDirectory("composition-cache").toFile()
        try{
            val store=PreparedTerrainCache(dir,2L*1024*1024,"gap-plan-v1",256*256*4)
            val rgba=ByteBuffer.allocateDirect(256*256*4);repeat(rgba.capacity()){rgba.put((it*37+it/257).toByte())};rgba.rewind()
            val stamp=PreparedTerrainCache.Dependency(id,"exact-input",2000,"provider-key")
            store.put(id,rgba,listOf(stamp));var now=1000L
            val hit=store.get(id){it.until>now}!!
            for(index in 0 until rgba.capacity())assertEquals(rgba.get(index),hit.get(index))
            now=2001;assertNull(store.get(id){it.until>now})
            assertNull(PreparedTerrainCache(dir,2L*1024*1024,"gap-plan-v2",256*256*4).get(id){true})
        }finally{dir.deleteRecursively()}
    }
}
