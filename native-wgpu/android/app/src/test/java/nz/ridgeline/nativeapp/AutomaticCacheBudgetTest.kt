package nz.ridgeline.nativeapp
import org.junit.Assert.*
import org.junit.Test

class AutomaticCacheBudgetTest {
    @Test fun zeroDisablesAndMiBIsBinary(){
        assertEquals(0L,AutomaticCacheBudget.bytes(0,64L*1024*1024*1024))
        assertEquals(1048576000L,AutomaticCacheBudget.bytes(1000,64L*1024*1024*1024))
        assertEquals(1048576L,AutomaticCacheBudget.bytes(1,64L*1024*1024*1024))
        assertEquals(0L,AutomaticCacheBudget.bytes(-1,Long.MAX_VALUE))
        assertEquals(1048576000L,AutomaticCacheBudget.bytes(2000,Long.MAX_VALUE))
    }
    @Test fun freeStorageStillCapsTotalAllowance(){
        assertEquals(100L*1024*1024,AutomaticCacheBudget.bytes(1000,800L*1024*1024))
        assertEquals(4L*1024*1024,AutomaticCacheBudget.bytes(1000,0))
        assertEquals(0L,AutomaticCacheBudget.bytes(0,0))
    }
}
