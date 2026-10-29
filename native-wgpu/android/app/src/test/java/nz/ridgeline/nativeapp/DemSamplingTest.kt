package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Test
import org.junit.Assert.*
private typealias LegacyId=GpuUploadQueue.Id
private typealias LegacyDem=DemSampling.Raster
/** Frozen pre-optimization sampling oracle from checkpoint7a3e659. */
private class LegacyDemMesh(val source:(LegacyId)->LegacyDem) {
 private fun raw(id:LegacyId)=source(id)
    private fun sample(record: LegacyDem, gx: Double, gy: Double, z: Int): Float {
        val scale = 2.0.pow(z - record.id.z)
        val px = ((gx + .5) / scale - .5 - record.id.x * 256).coerceIn(0.0, 255.0)
        val py = ((gy + .5) / scale - .5 - record.id.y * 256).coerceIn(0.0, 255.0)
        val x = floor(px).toInt(); val y = floor(py).toInt(); val fx = px - x; val fy = py - y
        fun h(a: Int, b: Int) = record.samples[b * 256 + a]
        return (h(x,y)*(1-fx)*(1-fy) + h(min(255,x+1),y)*fx*(1-fy) +
            h(x,min(255,y+1))*(1-fx)*fy + h(min(255,x+1),min(255,y+1))*fx*fy).toFloat()
    }
    fun mesh(id: LegacyId): ByteBuffer {
        val dz = min(id.z, 18); val factor = 1 shl (id.z - dz); val n = 1 shl dz
        val tx = id.x / factor; val ty = id.y / factor
        val halo = HashMap<LegacyId, LegacyDem>()
        for (dy in -1..1) for (dx in -1..1) {
            val neighbor = LegacyId(dz, Math.floorMod(tx + dx, n), (ty + dy).coerceIn(0, n - 1))
            if (neighbor !in halo) halo[neighbor] = raw(neighbor)
        }
        fun height(gx: Int, gy: Int): Float {
            val x = Math.floorMod(gx, n * 256); val y = gy.coerceIn(0, n * 256 - 1)
            val record = halo.getValue(LegacyId(dz, x / 256, y / 256))
            return sample(record, x.toDouble(), y.toDouble(), dz)
        }
        val heights = ByteBuffer.allocateDirect(129 * 129 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0..128) for (x in 0..128) {
            val px = (id.x * 256.0 + x * 2.0) / factor - .5
            val py = (id.y * 256.0 + y * 2.0) / factor - .5
            val ix = floor(px).toInt(); val iy = floor(py).toInt(); val fx = px - ix; val fy = py - iy
            val h = (height(ix,iy)*(1-fx)*(1-fy) + height(ix+1,iy)*fx*(1-fy) +
                height(ix,iy+1)*(1-fx)*fy + height(ix+1,iy+1)*fx*fy).toFloat()
            require(h.isFinite()); heights.putFloat(h)
        }
        heights.rewind();return heights
    }

}
class DemSamplingTest {
 private fun bytes(b:ByteBuffer)=ByteArray(b.capacity()).also{b.rewind();b.get(it)}
 private fun provider(fallback:Boolean):(LegacyId)->LegacyDem {
  val rows=HashMap<LegacyId,LegacyDem>()
  return {id->
   val actual=if(fallback&&id.z>0&&(id.x+id.y)%2==0)LegacyId(id.z-1,id.x/2,id.y/2)else id
   rows.getOrPut(actual){LegacyDem(actual,FloatArray(65536){i->
    val gx=actual.x*256+i%256;val gy=actual.y*256+i/256
    if(i%997==0)-0.0f else ((gx*31+gy*17)%65521-10000)/10f
   })}
  }
 }
 @Test fun completeHeightBuffersRemainBitIdenticalIncludingParentsAndWorldEdges(){
  val cases=listOf(LegacyId(11,1991,1300),LegacyId(18,254900,166400),
   LegacyId(19,509800,332800),LegacyId(19,509801,332801),LegacyId(20,1019601,665601),
   LegacyId(22,4078407,2662407),LegacyId(11,0,0),LegacyId(18,262143,262143))
  for(fallback in listOf(false,true))for(id in cases){
   val source=provider(fallback);val old=LegacyDemMesh(source).mesh(id)
   val actual=ByteBuffer.allocate(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
   DemSampling.mesh(id.z,id.x,id.y,actual,source)
   assertArrayEquals("$id parent=$fallback",bytes(old),bytes(actual))
  }
 }
 @Test fun interiorOverscaledMeshNeedsOneOriginalDemInsteadOfNine(){
  val id=LegacyId(20,1019601,665601);val source=provider(false)
  var oldCalls=0;val before=LegacyDemMesh{oldCalls++;source(it)}.mesh(id)
  var newCalls=0;val after=ByteBuffer.allocate(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
  DemSampling.mesh(id.z,id.x,id.y,after){newCalls++;source(it)}
  assertEquals(9,oldCalls);assertEquals(1,newCalls);assertArrayEquals(bytes(before),bytes(after))
  assertEquals(9,DemSampling.footprint(18,254900,166400).size)
  assertEquals(4,DemSampling.footprint(19,509800,332800).size)
 }
}
