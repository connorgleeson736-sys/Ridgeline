package nz.ridgeline.nativeapp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*
class LatestCameraTest {
 @Test fun inputDuringBlockedAcquisitionIsLatchedAfterItReturns(){
  var time=1L;val latest=LatestCamera("A"){time};val acquired=CountDownLatch(1);val finish=CountDownLatch(1)
  var drawn:LatestCamera.Packet<String>?=null
  val gpu=Thread{acquired.countDown();check(finish.await(2,TimeUnit.SECONDS));drawn=latest.read()};gpu.start()
  assertTrue(acquired.await(2,TimeUnit.SECONDS));time=8;latest.publish("B");finish.countDown();gpu.join(2000)
  assertFalse(gpu.isAlive);assertEquals("B",drawn!!.pose);assertEquals(1L,drawn!!.revision);assertEquals(8L,drawn!!.inputNanos)
 }
}
