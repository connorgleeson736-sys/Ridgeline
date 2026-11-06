package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class UploadAdmissionTest {
 @Test fun actualCostLimitsWorkButCheapGeometryCanBatch(){
    val policy=UploadAdmission();val g=GpuUploadQueue.Kind.GEOMETRY;val i=GpuUploadQueue.Kind.IMAGE
    repeat(10){policy.uploaded(g,100_000);policy.uploaded(i,5_000_000)}
    assertTrue(policy.allows(g,300_000,3,200000,66580,policy.allowance(false),0))
    assertFalse(policy.allows(i,300_000,1,66580,262144,policy.allowance(false),0))
    assertFalse(policy.allows(i,0,0,0,262144,0,0))
    assertFalse(policy.allows(i,0,0,0,262144,1,0))
    assertTrue(policy.allows(i,0,0,0,262144,0,0,urgent=true))
    assertTrue(policy.allows(i,0,0,0,262144,0,0,starvedIdle=true))
    assertFalse(policy.allows(g,0,0,0,66580,4000000,2))
    assertFalse(policy.allows(g,0,4,0,66580,4000000,0))
    assertFalse(policy.allows(i,0,1,600000,262144,4000000,0))
 }
 @Test fun activeDisplayRateAndLateCallbacksReduceAllowance(){val policy=UploadAdmission();repeat(20){policy.rendered(4_000_000)};assertEquals(0L,policy.allowance(false,8_333_333,5_000_000));assertTrue(policy.allowance(false,8_333_333)<policy.allowance(false,16_666_667))}
 @Test fun renderPressureReducesNextAllowance(){val policy=UploadAdmission();repeat(40){policy.rendered(16_000_000)};assertEquals(0L,policy.allowance(false))}
 @Test fun stationaryFramesKeepIdleAllowanceRegardlessOfRefreshOrRenderCost(){
    val policy=UploadAdmission();repeat(40){policy.rendered(7_000_000)}
    // 120 Hz, heavy frame and a late callback: zero slack while moving...
    assertEquals(0L,policy.allowance(false,8_333_333,2_000_000))
    // ...but a still camera keeps the full idle allowance.
    assertEquals(UploadAdmission.IDLE_ALLOWANCE,policy.allowance(false,8_333_333,2_000_000,stationary=true))
    // Gestures never take the stationary path.
    assertEquals(policy.allowance(true,8_333_333,2_000_000),policy.allowance(true,8_333_333,2_000_000,stationary=true))
 }
 /** Frame replay at 120 Hz with a 7 ms render, 1 ms callback lateness and 1.2 ms
  * actual upload cost: 240 queued refinement uploads while the camera is still. */
 @Test fun stationaryRefinementNoLongerWaitsOnStarvationTimer(){
    fun replay(stationaryAdmission:Boolean):Double{
        val policy=UploadAdmission();val frame=8_333_333L;var now=0L;var remaining=240;var headSince=-1L
        repeat(10){policy.rendered(7_000_000)}
        while(remaining>0){
            val allowance=policy.allowance(false,frame,1_000_000,stationary=stationaryAdmission)
            var elapsed=0L;var count=0;var bytes=0
            while(remaining>0){
                if(headSince<0)headSince=now
                // Previous rule: an over-budget head waited 250 ms of stillness.
                val starved=if(stationaryAdmission)true else now-headSince>=250_000_000L
                if(!policy.allows(GpuUploadQueue.Kind.IMAGE,elapsed,count,bytes,262144,allowance,0,false,starved))break
                elapsed+=1_200_000;count++;bytes+=262144;remaining--;headSince=-1;policy.uploaded(GpuUploadQueue.Kind.IMAGE,1_200_000)
            }
            policy.rendered(7_000_000);now+=frame
        }
        return 240/(now/1e9)
    }
    val before=replay(false);val after=replay(true)
    assertTrue("previous rule ~4 tiles/s, was $before",before<5)
    assertTrue("stationary admission should exceed 200 tiles/s, was $after",after>200)
 }
}
