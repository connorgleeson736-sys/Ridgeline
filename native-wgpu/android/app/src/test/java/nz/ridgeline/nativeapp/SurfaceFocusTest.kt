package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SurfaceFocusTest {
 private fun point(pose:CameraPose):DoubleArray {
  val hit=CameraMath.anchor(pose,320,640,1.0,160.0,320.0,3000.0)!!
  return doubleArrayOf(hit.mx*360-180,CameraMath.latitude(hit.my),hit.elevation)
 }
 @Test fun measuredSurfaceFocusInvalidatesThePlanAndNeverChangesTheCamera(){
  val pose=CameraPose(zoom=12.0,bearing=0f,pitch=70f,elevation=0f)
  val tracker=SurfaceFocusTracker();val hit=point(pose)
  assertTrue(tracker.update(pose,320,640,1.0,1000,hit))
  val focus=tracker.current(pose,320,640,1.0,1000)!!
  assertEquals(hit[0],focus.longitude,0.0);assertEquals(hit[1],focus.latitude,0.0);assertTrue(focus.range>0)
  assertNotEquals(pose.latitude,focus.latitude)
  val cache=DemandPlanCache<DemandPlanKey,Int>();var calls=0
  val key=DemandPlanKey(320,640,pose)
  assertEquals(1,cache.get(key){++calls});assertEquals(2,cache.get(key.copy(surfaceFocus=focus)){++calls})
  assertEquals(2,cache.get(key.copy(surfaceFocus=focus)){error("stable focus must reuse plan")})
  assertEquals(0f,pose.elevation);assertEquals(70f,pose.pitch)
  assertFalse(tracker.needsSample(pose,320,640,1.0,1100,true,true))
  assertTrue(tracker.needsSample(pose,320,640,1.0,1240,true,true))
  assertFalse(tracker.pending(pose,320,640,1.0,false))
 }
 @Test fun missingStaleAndOffscreenHitsFallBackWhileSmallMovementCanReuseRealHits(){
  val pose=CameraPose(zoom=12.0,bearing=0f,pitch=70f,elevation=0f);val tracker=SurfaceFocusTracker()
  tracker.update(pose,320,640,1.0,1000,point(pose))
  assertNotNull(tracker.current(pose.copy(longitude=pose.longitude+.00001),320,640,1.0,1100))
  assertNull(tracker.current(pose.copy(longitude=171.0),320,640,1.0,1100))
  assertNull(tracker.current(pose.copy(longitude=pose.longitude+.00001),320,640,1.0,1600))
  assertNull(tracker.current(pose,640,320,1.0,1100))
  assertNotNull(tracker.current(pose,320,640,1.0,9999)) // unchanged camera needs no permanent sampling
  assertTrue(tracker.update(pose,320,640,1.0,1100,null));assertNull(tracker.current(pose,320,640,1.0,1100))
  assertTrue(tracker.pending(pose,320,640,1.0,true)) // imagery can make existing terrain drawable
  assertFalse(tracker.needsSample(pose,320,640,1.0,1200,true,false))
  assertTrue(tracker.needsSample(pose,320,640,1.0,1340,true,false))
  tracker.update(pose,320,640,1.0,1200,doubleArrayOf(Double.NaN,0.0,0.0));assertNull(tracker.current(pose,320,640,1.0,1200))
  tracker.clear();assertTrue(tracker.needsSample(pose,320,640,1.0,1300,false,false))
 }
}
