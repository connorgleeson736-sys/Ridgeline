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
 @Test fun separateSourcesCoalesceAndRespectBytes(){
  val q=GpuUploadQueue(8);val id=GpuUploadQueue.Id(12,3984,2600)
  val h=ByteBuffer.allocate(100);val image=ByteBuffer.allocate(256)
  q.offer(GpuUploadQueue.Tile(id,h,GpuUploadQueue.EMPTY,GpuUploadQueue.Kind.GEOMETRY))
  q.offer(GpuUploadQueue.Tile(id,GpuUploadQueue.EMPTY,image,GpuUploadQueue.Kind.IMAGE))
  assertEquals(GpuUploadQueue.Kind.GEOMETRY,q.take(4,300).single().kind)
  assertTrue(q.isNotEmpty());assertEquals(GpuUploadQueue.Kind.IMAGE,q.take(4,300).single().kind)
  assertFalse(q.isNotEmpty())
 }
}
