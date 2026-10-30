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
}
