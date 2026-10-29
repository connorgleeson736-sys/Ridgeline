package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class DemandPlanCacheTest {
 @Test fun dataArrivalsReusePlanButAllCameraAndViewportChangesInvalidate(){
  val cache=DemandPlanCache<DemandPlanKey,Int>();val pose=CameraPose();var calls=0
  val base=DemandPlanKey(411,731,pose)
  repeat(100){assertEquals(1,cache.get(base){++calls})};assertEquals(1,calls)
  val keys=listOf(base.copy(width=412),base.copy(height=732),
   base.copy(pose=pose.copy(longitude=171.0)),base.copy(pose=pose.copy(latitude=-44.0)),
   base.copy(pose=pose.copy(zoom=12.0)),base.copy(pose=pose.copy(bearing=10f)),
   base.copy(pose=pose.copy(pitch=60f)),base.copy(pose=pose.copy(elevation=3000f)))
  for(key in keys){val before=calls;assertEquals(before+1,cache.get(key){++calls});assertEquals(calls,cache.get(key){error("same key rebuilt")})}
  assertEquals(9,calls)
 }
 @Test fun failedReplanDoesNotPublishAnOldPlanUnderANewCamera(){
  val cache=DemandPlanCache<String,Int>();assertEquals(1,cache.get("a"){1})
  try{cache.get("b"){throw IllegalStateException("fixture")};fail()}catch(_:IllegalStateException){}
  assertEquals(1,cache.get("a"){error("old valid plan lost")})
  assertEquals(2,cache.get("b"){2})
 }
}
