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
}
