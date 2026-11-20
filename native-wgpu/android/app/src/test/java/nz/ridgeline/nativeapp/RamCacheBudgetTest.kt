package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test

class RamCacheBudgetTest {
    @Test fun retainedPartitionsFitOneBudgetAtEverySliderPosition(){
        for(mib in 0..1000){
            val b=RamCacheBudget.split(mib)
            assertEquals(mib*1_048_576L,b.total)
            assertTrue(b.encoded>=0&&b.decoded>=0&&b.prepared>=0)
            val decodedEntries=b.decoded/(256*256*4)
            assertTrue(b.encoded+decodedEntries*(256*256*4)+b.prepared<=b.total)
        }
        assertEquals(RamCacheBudget(0,0,0),RamCacheBudget.split(0))
        assertEquals(RamCacheBudget.split(0),RamCacheBudget.split(-1))
        assertEquals(RamCacheBudget.split(1000),RamCacheBudget.split(1001))
    }
    @Test fun defaultIsConservativeOnLowRamPhones(){
        assertEquals(32,RamCacheBudget.recommended(256,true))
        assertEquals(64,RamCacheBudget.recommended(256,false))
        assertEquals(96,RamCacheBudget.recommended(1024,false))
    }
    private val mib=1_048_576L
    @Test fun neverMoreThanThePhoneCanHold(){
        // A typical 256 MB heap and 8 GB phone, slider at 1000 MiB: the Java
        // caches (encoded + decoded) keep to 30% of the heap (they were 500 MiB);
        // prepared (native) stays at its 500 MiB, within 512 MiB and RAM/16.
        val b=RamCacheBudget.split(1000,256*mib,8192*mib)
        assertTrue(b.encoded+b.decoded<=(256*mib*.3).toLong()+1)
        assertEquals(b.encoded,b.decoded)
        assertEquals(500*mib,b.prepared)
        // Within the limits nothing changes.
        assertEquals(RamCacheBudget.split(64),RamCacheBudget.split(64,256*mib,8192*mib))
        // A 4 GB phone: prepared at most a sixteenth of RAM.
        assertEquals(256*mib,RamCacheBudget.split(1000,512*mib,4096*mib).prepared)
    }
    @Test fun theSliderEndsWhereTheCacheIsHonoured(){
        val max=RamCacheBudget.maximumMiB(256*mib,8192*mib)
        assertEquals(153,max)
        val b=RamCacheBudget.split(max,256*mib,8192*mib)
        assertEquals(RamCacheBudget.split(max),b)
        assertEquals(1000,RamCacheBudget.maximumMiB(8192*mib,64*1024*mib).coerceAtMost(1000))
        assertTrue(RamCacheBudget.maximumMiB(64*mib,1024*mib)>=32)
    }
}
