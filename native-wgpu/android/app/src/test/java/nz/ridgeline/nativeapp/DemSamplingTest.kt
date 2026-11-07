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
 @Test fun adjacentTerrainMeshesShareEveryEdgeSampleIncludingSourceBoundaries(){
  fun mesh(z:Int,x:Int,y:Int):ByteBuffer{
   val output=ByteBuffer.allocate(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
   DemSampling.terrainMesh(z,x,y,output){id->LegacyDem(id,FloatArray(65536){i->
    val gx=id.x*256+i%256;val gy=id.y*256+i/256
    // Different slopes and high-frequency detail across original DEM borders.
    ((gx*13L+gy*37L)%19001-5000).toFloat()*.125f
   })}
   return output
  }
  for(id in listOf(LegacyId(12,3983,2600),LegacyId(12,3984,2600),
       LegacyId(19,509801,332801),LegacyId(22,4078407,2662407))){
   val a=mesh(id.z,id.x,id.y);val east=mesh(id.z,id.x+1,id.y);val south=mesh(id.z,id.x,id.y+1)
   for(i in 0..128){
    assertEquals("east $id sample $i",a.getFloat((i*129+128)*4).toRawBits(),east.getFloat(i*129*4).toRawBits())
    assertEquals("south $id sample $i",a.getFloat((128*129+i)*4).toRawBits(),south.getFloat(i*4).toRawBits())
   }
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
 @Test fun cameraGroundUsesPixelCentersAndActualNeighborsWithoutFabricatedFallback(){
  val z=2;val records=HashMap<LegacyId,LegacyDem>()
  fun value(x:Int,y:Int)=1000f+Math.floorMod(x,17)*2+Math.floorMod(y,23)*3
  fun source(id:LegacyId)=records.getOrPut(id){LegacyDem(id,FloatArray(65536){i->value(id.x*256+i%256,id.y*256+i/256)})}
  // Independent analytic expectations for the ramp, including a four-tile
  // seam, a fractional point, dateline wrap and both polar clamps.
  for((mx,my,expected)in listOf(Triple(.25,.5,1017.5f),Triple(.2503,.5007,1020.2648f),
      Triple(0.0,0.0,1003f),Triple(1.0,1.0,1036f),Triple(-.75,.5,1017.5f))){
   assertEquals(expected,DemSampling.point(z,mx,my,::source)!!,0f)
  }
  assertNull(DemSampling.point(z,.25,.5){if(it.x==0)null else source(it)})
  assertNull(DemSampling.point(z,Double.NaN,.5,::source))
  assertNull(DemSampling.point(z,.25,-.01,::source))
 }
 @Test fun parentGroundSamplingRetainsTheRealParentRatherThanAZeroOrChildInvention(){
  val parent=LegacyDem(LegacyId(1,0,0),FloatArray(65536){i->100f+i%256*.25f+i/256*.5f})
  val fine=DemSampling.point(2,.16,.21){parent}
  val coarse=DemSampling.point(1,.16,.21){parent}
  assertEquals(173.865f,coarse!!,0f);assertEquals(173.865f,fine!!,0f)
 }
 @Test fun genuineReferenceDemCenterMatchesCaptured0_18GroundHeight(){
  // Four original LINZ11/1991/1300pixels sampled by the exact0.18APK
  // capture. The source hash and reference Float64 result are recorded in
  // validation/reference-host; this permits only Float32 input quantization.
  val mx=(170.14219572+180)/360
  val my=(1-asinh(tan(Math.toRadians(-43.5955671)))/PI)/2
  val x=floor((mx*2048-1991)*256-.5).toInt()
  val y=floor((my*2048-1300)*256-.5).toInt()
  val pixels=FloatArray(65536)
  pixels[y*256+x]=3651.2f;pixels[y*256+x+1]=3566.4f
  pixels[(y+1)*256+x]=3668f;pixels[(y+1)*256+x+1]=3589.6f
  val raster=LegacyDem(LegacyId(11,1991,1300),pixels)
  assertEquals(3648.731912109524,DemSampling.point(DemSampling.terrainSourceZoom(12),mx,my){raster}!!.toDouble(),.001)
 }
 @Test fun referenceTerrainOwnershipRetainsAllVerticesAndTheOriginalSourceCeiling(){
  for(id in listOf(LegacyId(12,3983,2600),LegacyId(12,3984,2600),LegacyId(19,509800,332800),LegacyId(22,4078407,2662407))){
   val dz=(id.z-1).coerceIn(0,18);assertEquals(dz,DemSampling.terrainSourceZoom(id.z))
   val factor=1 shl(id.z-dz);val ax=id.x/factor;val ay=id.y/factor
   val records=HashMap<LegacyId,LegacyDem>();val output=ByteBuffer.allocate(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
   DemSampling.terrainMesh(id.z,id.x,id.y,output){source->
    assertEquals(dz,source.z)
    records.getOrPut(source){LegacyDem(source,FloatArray(65536){i->1000f+(source.x*256+i%256-ax*256)*.125f+(source.y*256+i/256-ay*256)*.25f})}
   }
   for(y in 0..128)for(x in 0..128){
    val px=(id.x*256.0+x*2)/factor-.5-ax*256
    val py=(id.y*256.0+y*2)/factor-.5-ay*256
    assertEquals((1000+px*.125+py*.25).toFloat(),output.float,0f)
   }
   assertFalse(output.hasRemaining())
  }
  assertEquals(0,DemSampling.terrainSourceZoom(0));assertEquals(0,DemSampling.terrainSourceZoom(1))
 }
}
