package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test
class CachedFallbackTest {
    @Test fun zoomJumpFindsClosestPreparedAncestorWithoutPreparingAnything(){
        val p=GpuUploadQueue.Id(12,3983,2600)
        val c=GpuUploadQueue.Id(16,p.x*16+3,p.y*16+5)
        val near=GpuUploadQueue.Id(14,c.x/4,c.y/4)
        val cache=mapOf(p to "coarse",near to "near")
        assertEquals(near to "near",CachedFallback.nearest(c){cache[it]})
        assertEquals(p to "coarse",CachedFallback.nearest(c){if(it==p)"coarse"else null})
        assertNull(CachedFallback.nearest(c){if(it==c)"exact, not an ancestor"else null})
        assertNull(CachedFallback.nearest(GpuUploadQueue.Id(0,0,0)){"not reached"})
    }
}
