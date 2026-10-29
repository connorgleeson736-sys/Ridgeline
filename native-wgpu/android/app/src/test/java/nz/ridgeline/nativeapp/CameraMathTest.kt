package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class CameraMathTest {
 @Test fun levelCameraAndRotatedCameraUseCorrectScreenAxes(){
  val p=CameraMath.offset(320,640,0f,0f,180.0,300.0)!!;assertEquals(20.0,p.first,1e-9);assertEquals(20.0,p.second,1e-9)
  val q=CameraMath.offset(320,640,90f,0f,180.0,300.0)!!;assertEquals(20.0,q.first,1e-9);assertEquals(-20.0,q.second,1e-9)
 }
 @Test fun pitchedCameraCenterStaysAtOriginAndHorizonIsNotAnInfinitePan(){
  for(pitch in listOf(0f,45f,58f,80f)){val p=CameraMath.offset(320,640,-24f,pitch,160.0,320.0)!!;assertEquals(0.0,p.first,1e-8);assertEquals(0.0,p.second,1e-8)}
  assertNull(CameraMath.offset(320,640,0f,85f,160.0,0.0))
 }
 @Test fun dragMovesTheCameraOppositeTheFingerAndKeepsTheGrabbedTerrain(){
  for(bearing in listOf(0f,-24f,90f,175f))for(pitch in listOf(0f,45f,58f,75f))for(scale in listOf(1.0,3.0)){
   val pose=CameraPose(bearing=bearing,pitch=pitch)
   val width=(320*scale).toInt();val height=(640*scale).toInt()
   val anchor=CameraMath.anchor(pose,width,height,scale,150*scale,350*scale,pose.elevation+200.0)!!
   val moved=CameraMath.anchored(pose,anchor,width,height,scale,195*scale,400*scale)!!
   val after=CameraMath.anchor(moved,width,height,scale,195*scale,400*scale,anchor.elevation)!!
   assertEquals(anchor.mx,after.mx,1e-11);assertEquals(anchor.my,after.my,1e-11)
   if(bearing==0f){assertTrue(moved.longitude<pose.longitude);assertTrue(moved.latitude>pose.latitude)}
   val pinch=moved.copy(zoom=moved.zoom+.7,bearing=bearing+12,pitch=pitch.coerceAtMost(65f))
   val zoomed=CameraMath.anchored(pinch,anchor,width,height,scale,180*scale,370*scale)!!
   val projected=CameraMath.anchor(zoomed,width,height,scale,180*scale,370*scale,anchor.elevation)!!
   assertEquals(anchor.mx,projected.mx,1e-11);assertEquals(anchor.my,projected.my,1e-11)
  }
 }
}
