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
 @Test fun changedViewportKeepsUsefulAncestorsAndDropsUnrelatedSources(){
  val q=GpuUploadQueue(8)
  val a=GpuUploadQueue.Id(11,1991,1300);val b=GpuUploadQueue.Id(12,3982,2600)
  val data=ByteBuffer.allocate(16)
  fun tile(id:GpuUploadQueue.Id,kind:GpuUploadQueue.Kind)=GpuUploadQueue.Tile(id,data,GpuUploadQueue.EMPTY,kind)
  q.offer(tile(a,GpuUploadQueue.Kind.GEOMETRY))
  q.offer(tile(a,GpuUploadQueue.Kind.IMAGE))
  q.updateDemand(setOf(a),setOf(b))
  // A cached ancestor remains useful to fill the image target until it arrives.
  q.offer(tile(a,GpuUploadQueue.Kind.IMAGE))
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE))
  assertEquals(listOf(a to GpuUploadQueue.Kind.IMAGE,a to GpuUploadQueue.Kind.GEOMETRY,b to GpuUploadQueue.Kind.IMAGE),q.take(8).map{it.id to it.kind})
  q.updateDemand(emptySet(),emptySet())
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE));assertFalse(q.isNotEmpty())
  q.updateDemand(setOf(a),setOf(b))
  q.offer(tile(b,GpuUploadQueue.Kind.IMAGE));assertEquals(b,q.take(1).single().id)
 }
 @Test fun zoomJumpAdmitsAndPrioritizesGrandparentBeforeFineUploads(){
  val q=GpuUploadQueue(8);val parent=GpuUploadQueue.Id(12,3983,2600)
  val fine=GpuUploadQueue.Id(16,parent.x*16+3,parent.y*16+5)
  val other=fine.copy(x=fine.x+64);val data=ByteBuffer.allocate(16)
  fun tile(id:GpuUploadQueue.Id)=GpuUploadQueue.Tile(id,GpuUploadQueue.EMPTY,data,GpuUploadQueue.Kind.IMAGE)
  q.updateDemand(emptySet(),setOf(fine))
  assertTrue(q.offer(tile(fine)));assertTrue(q.offer(tile(parent)))
  assertFalse(q.offer(tile(other)))
  assertEquals(parent,q.take(1).single().id)
  q.updateDemand(emptySet(),setOf(other));assertFalse(q.isNotEmpty())
 }
 @Test fun repeatedReadyPayloadDoesNotScheduleExtraFramesButNewOwnershipDoes(){
  val q=GpuUploadQueue(8);val id=GpuUploadQueue.Id(11,1991,1300)
  val h=ByteBuffer.allocate(16);val image=ByteBuffer.allocate(16)
  val first=GpuUploadQueue.Tile(id,h,image)
  assertTrue(q.offer(first));repeat(100){assertFalse(q.offer(first.copy()))}
  assertSame(h,q.take(1).single().heights)
  assertTrue(q.offer(first)) // Re-admit after draining/surface recreation.
  assertTrue(q.offer(first.copy(rgba=ByteBuffer.allocate(16)))) // Replacement cannot be dropped.
  assertEquals(1,q.take(8).size)
 }
 @Test fun speculationNeverDisplacesVisibleUploadsAndIsDroppedWhenCancelled(){
  val q=GpuUploadQueue(2);val a=GpuUploadQueue.Id(12,1,1);val b=a.copy(x=2);val warm=a.copy(x=3)
  val data=ByteBuffer.allocate(16)
  fun tile(id:GpuUploadQueue.Id)=GpuUploadQueue.Tile(id,GpuUploadQueue.EMPTY,data,GpuUploadQueue.Kind.IMAGE)
  q.updateDemand(emptySet(),setOf(a,b));q.updateWarm(emptySet(),setOf(warm));q.offer(tile(a));q.offer(tile(b))
  assertFalse(q.offer(tile(warm)));assertEquals(a,q.take(1).single().id);assertTrue(q.offer(tile(warm)))
  assertEquals(b,q.take(1).single().id);q.updateWarm(emptySet(),emptySet());assertFalse(q.isNotEmpty())
 }
}
