package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class UploadAdmissionTest {
 @Test fun actualCostLimitsWorkButCheapGeometryCanBatch(){
    val policy=UploadAdmission();val g=GpuUploadQueue.Kind.GEOMETRY;val i=GpuUploadQueue.Kind.IMAGE
    repeat(10){policy.uploaded(g,100_000);policy.uploaded(i,5_000_000)}
    assertTrue(policy.allows(g,300_000,3,200000,66580,policy.allowance(false),0))
    assertFalse(policy.allows(i,300_000,1,66580,262144,policy.allowance(false),0))
    assertTrue(policy.allows(i,0,0,0,262144,0,0)) // Expensive tile cannot starve.
    assertFalse(policy.allows(g,0,0,0,66580,4000000,2))
    assertFalse(policy.allows(g,0,4,0,66580,4000000,0))
    assertFalse(policy.allows(i,0,1,600000,262144,4000000,0))
 }
 @Test fun renderPressureReducesNextAllowance(){val policy=UploadAdmission();repeat(40){policy.rendered(16_000_000)};assertEquals(0L,policy.allowance(false))}
}
