package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import org.junit.Test
import org.junit.Assert.assertArrayEquals

class DemHaloPerformanceTest {
 @Test fun compareProductionTerrainPreparation() {
  val cases=listOf(GpuUploadQueue.Id(0,0,0),GpuUploadQueue.Id(12,3983,2600),
   GpuUploadQueue.Id(19,509801,332801),GpuUploadQueue.Id(22,4078407,2662407),
   GpuUploadQueue.Id(11,0,0),GpuUploadQueue.Id(18,262143,262143))
  val current=ByteBuffer.allocate(66564).order(ByteOrder.LITTLE_ENDIAN)
  val baseline=ByteBuffer.allocate(66564).order(ByteOrder.LITTLE_ENDIAN)
  val originals=HashMap<GpuUploadQueue.Id,FloatArray>()
  fun values(id:GpuUploadQueue.Id)=originals.getOrPut(id){FloatArray(65536){i->
   if(i%997==0)-0.0f else ((id.x*256L+i%256)*31+(id.y*256L+i/256)*17).rem(19001).toFloat()*.125f}}
  for(fallback in listOf(false,true))for(id in cases){
   fun actual(request:GpuUploadQueue.Id)=if(fallback&&request.z>0&&(request.x+request.y)%2==0)
    GpuUploadQueue.Id(request.z-1,request.x/2,request.y/2)else request
   baseline.clear();current.clear()
   BaselineDemSampling.terrainMesh(id.z,id.x,id.y,baseline){request->val a=actual(request);BaselineDemSampling.Raster(a,values(a))}
   DemSampling.terrainMesh(id.z,id.x,id.y,current){request->val a=actual(request);DemSampling.Raster(a,values(a))}
   assertArrayEquals("$id fallback=$fallback",baseline.array(),current.array())
  }
  val id=cases[1]
  val rasters=DemSampling.footprint(id.z,id.x,id.y,11).associateWith{DemSampling.Raster(it,values(it))}
  val old=rasters.mapValues{BaselineDemSampling.Raster(it.key,it.value.samples)}
  fun before(){baseline.clear();BaselineDemSampling.terrainMesh(id.z,id.x,id.y,baseline){old.getValue(it)}}
  fun after(){current.clear();DemSampling.terrainMesh(id.z,id.x,id.y,current){rasters.getValue(it)}}
  repeat(100){before();after()}
  val a=ArrayList<Long>();val b=ArrayList<Long>()
  fun timed(block:()->Unit):Long{val t=System.nanoTime();block();return System.nanoTime()-t}
  repeat(100){i->if(i%2==0){a+=timed(::before);b+=timed(::after)}else{b+=timed(::after);a+=timed(::before)}}
  val report="{\"scope\":\"Host JVM decoded DEM to complete mesh, no I/O or profile\",\"beforeMedianNs\":${a.sorted()[50]},\"afterMedianNs\":${b.sorted()[50]},\"beforeNs\":$a,\"afterNs\":$b,\"allPayloadsIdentical\":true}"
  println(report)
  System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"dem-halo.json").apply{parentFile?.mkdirs();writeText(report)}}
  System.getenv("RIDGELINE_PIPELINE_FIXTURE")?.let{directory->
   val imageIO=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java)
   val getRGB=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
   fun decode(source:GpuUploadQueue.Id):FloatArray {
    val image=imageIO.invoke(null,File(directory,"elevation-${source.z}-${source.x}-${source.y}.png"))
    val pixels=getRGB.invoke(image,0,0,256,256,null,0,256)as IntArray
    return FloatArray(pixels.size){(-10000+(pixels[it]and 0xffffff)*.1).toFloat()}
   }
   fun loadBefore(){baseline.clear();BaselineDemSampling.terrainMesh(id.z,id.x,id.y,baseline){BaselineDemSampling.Raster(it,decode(it))}}
   fun loadAfter(){current.clear();DemSampling.terrainMesh(id.z,id.x,id.y,current){DemSampling.Raster(it,decode(it))}}
   repeat(16){loadBefore();loadAfter()}
   val oldLoad=ArrayList<Long>();val newLoad=ArrayList<Long>()
   repeat(40){i->if(i%2==0){oldLoad+=timed(::loadBefore);newLoad+=timed(::loadAfter)}else{newLoad+=timed(::loadAfter);oldLoad+=timed(::loadBefore)}}
   assertArrayEquals(baseline.array(),current.array())
   val endToEnd="{\"scope\":\"Host JVM genuine LINZ files: disk read, ImageIO PNG decode, exact production mesh. Network, Android Bitmap decoder, adaptive profile and GPU upload excluded.\",\"beforeMedianNs\":${oldLoad.sorted()[20]},\"afterMedianNs\":${newLoad.sorted()[20]},\"beforeNs\":$oldLoad,\"afterNs\":$newLoad}"
   println(endToEnd)
   System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"dem-local-loading.json").writeText(endToEnd)}
  }

 }
}
