package nz.ridgeline.nativeapp

import java.io.File
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PlanCadenceTest {
 @Test fun firstIdleAndReturnDemandAreImmediateWhileMovementRetainsRateLimit() {
  val cadence=PlanCadence();assertEquals(0L,cadence.delay(1000))
  cadence.started(1000)
  assertEquals(50L,cadence.delay(1000));assertEquals(30L,cadence.delay(1020))
  assertEquals(0L,cadence.delay(1050));assertEquals(0L,cadence.delay(8000))
  cadence.started(8000);assertEquals(49L,cadence.delay(8001))
  val moving=PlanCadence();var plans=0
  for(now in 0L..1000L)if(moving.delay(now)==0L){moving.started(now);plans++}
  assertEquals(21,plans) // Inclusive endpoints: exactly one start per 50 ms.
 }

 @Test fun benchmarkFirstDemandThroughRealLocalMeshReadiness() {
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE");assumeTrue(fixture!=null)
  val id=GpuUploadQueue.Id(12,3983,2600)
  val read=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java)
  val rgb=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
  val output=ByteBuffer.allocateDirect(66564).order(ByteOrder.LITTLE_ENDIAN)
  val scheduler=Executors.newSingleThreadScheduledExecutor()
  fun mesh(){output.clear();DemSampling.terrainMesh(id.z,id.x,id.y,output){source->
   val bytes=File(fixture!!,"elevation-${source.z}-${source.x}-${source.y}.png").readBytes()
   val image=read.invoke(null,ByteArrayInputStream(bytes));val colors=rgb.invoke(image,0,0,256,256,null,0,256)as IntArray
   DemSampling.Raster(source,FloatArray(colors.size){(-10000+(colors[it]and 0xffffff)*.1).toFloat()})}}
  fun request(delay:Long):Long{val start=System.nanoTime();scheduler.schedule({mesh()},delay,TimeUnit.MILLISECONDS).get(5,TimeUnit.SECONDS);return System.nanoTime()-start}
  try{
   repeat(10){request(0)}
   val a=ArrayList<Long>();val b=ArrayList<Long>()
   repeat(40){i->if(i%2==0){a+=request(50);b+=request(PlanCadence().delay(1000))}else{b+=request(PlanCadence().delay(1000));a+=request(50)}}
   val report="{\"scope\":\"Host scheduler first/idle demand through genuine local LINZ reads, ImageIO decode and production mesh. Same optimized sampler in both arms; excludes HTTP, planning, profile, Android scheduling and GPU\",\"beforeMedianNs\":${a.sorted()[20]},\"afterMedianNs\":${b.sorted()[20]},\"beforeNs\":$a,\"afterNs\":$b}"
   println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"first-demand.json").writeText(report)}
  }finally{scheduler.shutdownNow()}
 }
}
