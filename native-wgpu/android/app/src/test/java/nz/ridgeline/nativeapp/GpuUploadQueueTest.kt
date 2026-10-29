package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.Assert.*
class GpuUploadQueueTest {
 @Test fun burstsCoalesceAndRemainBounded(){
  val q=GpuUploadQueue(4)
  val bytes=ByteBuffer.allocate(4)
  for(x in 0..100)q.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(12,x,0),bytes,bytes))
  val last=ByteBuffer.allocate(8)
  q.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(12,100,0),last,last))
  val a=q.take(2);val b=q.take(2)
  assertEquals(listOf(97,98),a.map{it.id.x});assertEquals(listOf(99,100),b.map{it.id.x})
  assertSame(last,b.last().rgba);assertFalse(q.isNotEmpty())
 }
}
