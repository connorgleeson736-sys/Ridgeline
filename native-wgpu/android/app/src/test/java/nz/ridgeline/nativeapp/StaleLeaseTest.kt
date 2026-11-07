package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class StaleLeaseTest {
    @Test fun onlyDownloadsOutstandingTwentySecondsAreReleased(){
        val a=GpuUploadQueue.Id(14,1,1);val b=GpuUploadQueue.Id(14,1,2)
        val started=mapOf(a to 1_000L,b to 15_000L)
        assertEquals(setOf(a),MapLayers.staleLeases(started,21_000L))
        assertEquals(emptySet<GpuUploadQueue.Id>(),MapLayers.staleLeases(started,20_999L))
        assertEquals(setOf(a,b),MapLayers.staleLeases(started,35_000L))
    }
}
