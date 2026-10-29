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
 @Test fun changedViewportDropsQueuedAndLateSourcesIndependently(){
  val q=GpuUploadQueue(8)
  val a=GpuUploadQueue.Id(11,1991,1300);val b=GpuUploadQueue.Id(12,3982,2600)
  val data=ByteBuffer.allocate(16)
  fun tile(id:GpuUploadQueue.Id,kind:GpuUploadQueue.Kind)=GpuUploadQueue.Tile(id,data,GpuUploadQueue.EMPTY,kind)
  q.offer(tile(a,GpuUploadQueue.Kind.GEOMETRY))
  q.offer(tile(a,GpuUploadQueue.Kind.IMAGE))
  q.updateDemand(setOf(a),setOf(b))
  // An obsolete in-flight completion cannot displace demanded sources.
  q.offer(tile(a,GpuUploadQueue.Kind.IMAGE))
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE))
  assertEquals(listOf(a to GpuUploadQueue.Kind.GEOMETRY,b to GpuUploadQueue.Kind.IMAGE),q.take(8).map{it.id to it.kind})
  q.updateDemand(emptySet(),emptySet())
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE));assertFalse(q.isNotEmpty())
  q.updateDemand(setOf(a),setOf(b))
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE));assertEquals(b,q.take(1).single().id)
 }
}
