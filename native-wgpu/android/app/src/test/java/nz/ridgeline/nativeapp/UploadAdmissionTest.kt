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
 @Test fun movementMinimumAdmitsTerrainAndOneImageWithinBounds(){
    val p=UploadAdmission();val g=GpuUploadQueue.Kind.GEOMETRY;val i=GpuUploadQueue.Kind.IMAGE;val c=GpuUploadQueue.Kind.COMBINED
    repeat(10){p.uploaded(g,400_000);p.uploaded(i,1_500_000);p.uploaded(c,2_000_000)}
    // Zero spare time while moving: the ordinary rule admits nothing non-urgent.
    assertFalse(p.allows(g,0,0,0,66580,0,0))
    // Terrain: two per frame within 2 ms of upload time.
    assertTrue(p.movementMinimum(g,0,0,0,66580,0,0,0,0))
    assertTrue(p.movementMinimum(g,400_000,1,66580,66580,0,1,0,0))
    assertFalse(p.movementMinimum(g,800_000,2,133160,66580,0,2,0,0))
    assertFalse(p.movementMinimum(g,1_800_000,1,66580,66580,0,1,0,0))
    // Imagery: one per frame within 4 ms.
    assertTrue(p.movementMinimum(i,0,0,0,262144,0,0,0,0))
    assertTrue(p.movementMinimum(c,800_000,2,133160,328724,0,2,0,0))
    assertFalse(p.movementMinimum(i,0,1,262144,262144,0,0,1,0))
    // Backlog, count and byte limits always win.
    assertFalse(p.movementMinimum(g,0,0,0,66580,2,0,0,0))
    assertFalse(p.movementMinimum(i,0,4,0,262144,0,0,0,0))
    assertFalse(p.movementMinimum(i,0,1,600_000,262144,0,1,0,0))
 }
 @Test fun expensiveImageryIsRateLimitedDuringMovement(){
    val p=UploadAdmission();val i=GpuUploadQueue.Kind.IMAGE
    repeat(10){p.uploaded(i,6_000_000)}
    assertTrue(p.movementMinimum(i,0,0,0,262144,0,0,0,1_000_000_000))
    p.movementUploaded(i,1_000_000_000)
    assertFalse(p.movementMinimum(i,0,0,0,262144,0,0,0,1_050_000_000))
    assertTrue(p.movementMinimum(i,0,0,0,262144,0,0,0,1_100_000_000))
 }
 /** 120 Hz gesture, 7 ms render (zero spare time), 0.4 ms terrain and 1.5 ms
  * imagery uploads, alternating queue: refinement now continues while moving. */
 @Test fun refinementContinuesDuringAGesture(){
    val p=UploadAdmission();val g=GpuUploadQueue.Kind.GEOMETRY;val i=GpuUploadQueue.Kind.IMAGE
    repeat(10){p.rendered(7_000_000);p.uploaded(g,400_000);p.uploaded(i,1_500_000)}
    val queue=ArrayDeque((0 until 300).map{if(it%2==0)g else i});var frames=0;var now=0L
    while(queue.isNotEmpty()&&frames<120){
        val allowance=p.allowance(true,8_333_333,1_000_000)
        var elapsed=0L;var count=0;var bytes=0;var geometry=0;var images=0
        while(queue.isNotEmpty()){
            val kind=queue.first();val size=if(kind==g)66580 else 262144
            val minimum=p.movementMinimum(kind,elapsed,count,bytes,size,0,geometry,images,now)
            if(!minimum&&!p.allows(kind,elapsed,count,bytes,size,allowance,0))break
            queue.removeFirst();val cost=if(kind==g)400_000L else 1_500_000L
            elapsed+=cost;count++;bytes+=size;if(kind==g)geometry++ else images++;p.uploaded(kind,cost);if(minimum)p.movementUploaded(kind,now)
        }
        frames++;now+=8_333_333
    }
    assertEquals(0L,p.allowance(true,8_333_333,1_000_000))
    // One second of gesture: one terrain + one imagery upload per frame
    // (a second terrain upload would exceed the 2 ms terrain bound).
    assertEquals(240,300-queue.size)
    // The previous rule admitted none of these non-urgent uploads.
    assertFalse(p.allows(g,0,0,0,66580,p.allowance(true,8_333_333,1_000_000),0))
 }
}
