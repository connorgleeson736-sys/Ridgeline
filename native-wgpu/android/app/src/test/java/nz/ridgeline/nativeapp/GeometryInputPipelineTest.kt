package nz.ridgeline.nativeapp

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class GeometryInputPipelineTest {
 @Test fun readyInputsAreDecodedBeforeTheSlowestBodyAndParentWaitsStartImmediately(){
  val pipe=GeometryInputPipeline<Int,String>();val late=CompletableFuture<Unit>();val parent=CompletableFuture<Unit>()
  val order=ArrayList<String>()
  fun prepare()=pipe.prepare(listOf(1,2,3),{key->order+="request$key";if(key==3)late else CompletableFuture.completedFuture(Unit)},{key->
   order+="decode$key";if(key==1&&!parent.isDone)throw ResourcePending(parent);"value$key"
  })
  try{prepare();fail("Must wait for remaining inputs")}catch(waiting:ResourcePending){assertFalse(waiting.ready.isDone);assertTrue(order.indexOf("request3")<order.indexOf("decode1"));assertEquals("value2",pipe.get(2))}
  assertTrue(order.contains("decode1"));assertFalse(order.contains("decode3"));assertNull(pipe.get(1))
  parent.complete(Unit)
  try{prepare();fail()}catch(_:ResourcePending){assertEquals("value1",pipe.get(1));assertEquals(1,order.count{it=="decode2"})}
  late.complete(Unit);prepare();assertEquals("value3",pipe.get(3));assertEquals(3,pipe.size())
 }
 @Test fun operationPinsAreBoundedAndClearableAndDecodeErrorsPropagate(){
  val pipe=GeometryInputPipeline<Int,String>(2)
  pipe.prepare((0..4).toList(),{CompletableFuture.completedFuture(Unit)},{"value$it"})
  assertEquals(2,pipe.size());pipe.clear();assertEquals(0,pipe.size())
  try{pipe.prepare(listOf(9),{CompletableFuture.completedFuture(Unit)},{error("Bad source")});fail()}catch(e:IllegalStateException){assertEquals("Bad source",e.message)}
 }
 @Test fun canceledOrPressuredOperationsDoNotRepinAfterTheirCacheIsCleared(){
  var live=true;val pipe=GeometryInputPipeline<Int,String>(retain={live})
  pipe.prepare(listOf(1,2),{CompletableFuture.completedFuture(Unit)},{key->
   if(key==1){live=false;pipe.clear()};"value$key"
  })
  assertEquals(0,pipe.size());live=true;pipe.remember(3,"resumed");assertEquals("resumed",pipe.get(3))
 }
 @Test fun benchmarkGenuineDemBodiesAndExactMeshWithStaggeredArrivals(){
  val directory=System.getenv("RIDGELINE_PIPELINE_FIXTURE")?.let(::File)?:return
  val resultDir=System.getenv("RIDGELINE_BENCH_OUTPUT")?.let(::File)?:return
  val geometry=GpuUploadQueue.Id(12,3983,2600)
  val ids=DemSampling.footprint(geometry.z,geometry.x,geometry.y,11).toList()
  assertEquals(4,ids.size)
  val failed=ids.first();val parent=GpuUploadQueue.Id(failed.z-1,failed.x/2,failed.y/2)
  val encoded=(ids+parent).associateWith{File(directory,"elevation-${it.z}-${it.x}-${it.y}.png").readBytes()}
  val imageIO=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java)
  val getRGB=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
  fun decode(id:GpuUploadQueue.Id,bytes:ByteArray):DemSampling.Raster {
   val image=imageIO.invoke(null,bytes.inputStream());val colors=getRGB.invoke(image,0,0,256,256,null,0,256)as IntArray
   check(colors.all{it ushr 24==255});return DemSampling.Raster(id,FloatArray(colors.size){(-10000+(colors[it]and 0xffffff)*.1).toFloat()})
  }
  val samples=ArrayList<String>()
  for(fallback in listOf(false,true)){
   val expectedRasters=(ids+parent).associateWith{decode(it,encoded.getValue(it))}
   val expected=ByteBuffer.allocate(66564).order(ByteOrder.LITTLE_ENDIAN)
   DemSampling.terrainMesh(geometry.z,geometry.x,geometry.y,expected){if(fallback&&it==failed)expectedRasters.getValue(parent)else expectedRasters.getValue(it)}
   val a=ArrayList<Double>();val b=ArrayList<Double>();val counts=ArrayList<Int>()
   fun run(current:Boolean):Double{
    val clock=Executors.newScheduledThreadPool(2);val worker=Executors.newSingleThreadExecutor();val requests=AtomicInteger()
    val urls=(ids+parent).associateBy{"https://fixture.invalid/${it.z}/${it.x}/${it.y}"}
    val transport=TileTransport(object:TileBackend{
     override fun local(key:ResourceKey):TileBody?=null
     override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
      requests.incrementAndGet();val id=urls.getValue(key.url)
      val delay=if(id==parent||id==ids.last())180L else 10L
      val event=clock.schedule({done(Result.success(if(fallback&&id==failed)TileBody(ByteArray(0),404,Long.MAX_VALUE)else TileBody(encoded.getValue(id),200,Long.MAX_VALUE)))},delay,TimeUnit.MILLISECONDS)
      return{event.cancel(false)}
     }
    },0)
    val owner=transport.consumer();val pipe=GeometryInputPipeline<GpuUploadQueue.Id,DemSampling.Raster>()
    val result=CompletableFuture<ByteBuffer>();val begun=System.nanoTime();var firstDecode=Long.MAX_VALUE
    fun request(id:GpuUploadQueue.Id)=owner.acquire(ResourceKey("https://fixture.invalid/${id.z}/${id.x}/${id.y}",false))
    fun raw(id:GpuUploadQueue.Id):DemSampling.Raster {
     if(current)pipe.get(id)?.let{return it}
     val ready=request(id);if(!ready.isDone)throw ResourcePending(ready)
     val body=ready.get()
     if(firstDecode==Long.MAX_VALUE)firstDecode=System.nanoTime()-begun
     val row=if(body.status==404)raw(GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2))else decode(id,body.bytes)
     return if(current)pipe.remember(id,row)else row
    }
    lateinit var work:Runnable
    work=Runnable{try{
     if(current)pipe.prepare(ids,::request,::raw)else{
      val bodies=ids.map(::request);if(bodies.any{!it.isDone})throw ResourcePending(CompletableFuture.allOf(*bodies.toTypedArray()))
     }
     val output=ByteBuffer.allocate(66564).order(ByteOrder.LITTLE_ENDIAN)
     DemSampling.terrainMesh(geometry.z,geometry.x,geometry.y,output,load=::raw)
     result.complete(output)
    }catch(waiting:ResourcePending){waiting.ready.whenComplete{_,_->worker.execute(work)}}catch(error:Throwable){result.completeExceptionally(error)}}
    try{
     worker.execute(work);val actual=result.get(3,TimeUnit.SECONDS);val duration=(System.nanoTime()-begun)/1e6
     assertArrayEquals(expected.array(),actual.array());assertEquals(if(fallback)5 else 4,requests.get());counts+=requests.get()
     if(current)assertTrue("Early decode must run during body arrival",firstDecode<TimeUnit.MILLISECONDS.toNanos(150))
     else assertTrue(firstDecode>=TimeUnit.MILLISECONDS.toNanos(150))
     return duration
    }finally{owner.release(true);transport.close();clock.shutdownNow();worker.shutdownNow()}
   }
   repeat(12){i->if(i%2==0){a+=run(false);b+=run(true)}else{b+=run(true);a+=run(false)}}
   samples+="""{"parentFallback":$fallback,"beforeMedianMs":${a.sorted()[6]},"afterMedianMs":${b.sorted()[6]},"beforeMs":$a,"afterMs":$b,"sourceRequestsPerRun":${counts.first()},"allMeshBytesIdentical":true}"""
  }
  val report="""{"scope":"Host JVM actual production transport, input-pipeline and mesh kernels; genuine LINZ PNG/ImageIO, controlled 10/180ms arrivals and injected first-source 404. Network timing, 404 and single worker are replay controls, not a phone/live-provider result. JNI profile/GPU excluded.","cases":[${samples.joinToString(",")}] }"""
  println(report);File(resultDir,"incremental-dem.json").writeText(report)
 }
}
