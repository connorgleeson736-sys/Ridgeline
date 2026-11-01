package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class GpuUploadAckTest {
 @Test fun retriesKeepPayloadSkipBlockedHeadsAndRespectNewOwnership(){
  val q=GpuUploadQueue(3);val a=GpuUploadQueue.Id(16,60000,40000);val b=a.copy(x=a.x+1)
  fun tile(id:GpuUploadQueue.Id)=GpuUploadQueue.Tile(id,GpuUploadQueue.EMPTY,ByteBuffer.allocate(8),GpuUploadQueue.Kind.IMAGE)
  val first=tile(a);val second=tile(b);val start=System.nanoTime()
  q.updateRankedDemand(emptyMap(),mapOf(a to 0,b to 1));q.offer(second);q.offer(first)
  assertSame(first,q.peek(start));q.acknowledge(first,GpuUploadQueue.RETRY,start)
  assertEquals(2,q.size());assertSame(second,q.peek(start));q.acknowledge(second,GpuUploadQueue.ACCEPTED,start)
  assertNull(q.peek(start));assertEquals(16L,q.nextDelayMillis(start))
  assertSame(first,q.peek(start+16_000_000));q.acknowledge(first,GpuUploadQueue.RETRY,start+16_000_000)
  assertEquals(32L,q.nextDelayMillis(start+16_000_000))
  val newer=tile(a);assertTrue(q.offer(newer));q.acknowledge(first,GpuUploadQueue.ACCEPTED,start)
  assertSame(newer,q.peek(start));q.acknowledge(newer,GpuUploadQueue.INVALID,start)
  assertFalse(q.isNotEmpty());assertNull(q.nextDelayMillis(start))
  q.offer(first);q.acknowledge(first,GpuUploadQueue.DEVICE_UNAVAILABLE,start)
  assertEquals(250L,q.nextDelayMillis(start));q.wakeRetries();assertSame(first,q.peek(start))
  q.updateDemand(emptySet(),emptySet());assertFalse(q.isNotEmpty());assertNull(q.nextDelayMillis(start))
 }
}
