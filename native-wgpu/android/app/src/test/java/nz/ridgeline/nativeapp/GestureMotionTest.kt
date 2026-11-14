package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class GestureMotionTest {
 @Test fun momentumMatchesMapLibreDefaultsAndStopsAfterHolding(){
  val motion=GestureMotion()
  motion.record(GestureMotion.Sample(0,0.0,0.0,12.0,0.0,45.0))
  motion.record(GestureMotion.Sample(50,50.0,0.0,12.0,0.0,45.0))
  val coast=motion.finish(50)!!
  assertEquals(400,coast.duration);assertEquals(60.0,coast.x,1e-10);assertEquals(0.0,coast.y,0.0)
  motion.record(GestureMotion.Sample(100,0.0,0.0,12.0,0.0,45.0))
  motion.record(GestureMotion.Sample(150,50.0,0.0,12.0,0.0,45.0))
  assertNull(motion.finish(400))
 }
 @Test fun parallelUpwardFingersTiltUpWithoutZoomingOrRotating(){
  val touch=TwoFingerGesture();touch.start(100.0,100.0,200.0,100.0)
  val delta=touch.move(100.0,90.0,200.0,90.0,10)
  assertTrue(delta.tilting);assertEquals(5.0,delta.pitch,0.0);assertEquals(0.0,delta.zoom,0.0);assertEquals(0.0,delta.bearing,0.0)
 }
 @Test fun pinchAndClockwiseRotationUseMapLibreSigns(){
  val touch=TwoFingerGesture();touch.start(100.0,100.0,100.0,200.0)
  val small=touch.move(100.0,100.0,100.0,201.0,10)
  assertEquals(0.0,small.zoom,0.0)
  val pinch=touch.move(100.0,80.0,100.0,220.0,20)
  assertTrue(pinch.zoom>0);assertEquals(0.0,pinch.pitch,0.0)
  val rotate=touch.move(130.0,100.0,70.0,200.0,30)
  assertTrue(rotate.bearing<0)
 }
}
