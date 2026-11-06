package nz.ridgeline.nativeapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PipelineWaitTest {
 @Test fun benchmarkSharperImageryDoesNotWaitForTerrainToReleaseEveryNetworkSlot(){
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  fun run(reserve:Boolean):Double {
   val timer=Executors.newScheduledThreadPool(2);val terrainStarts=AtomicInteger();val active=AtomicInteger();val maximum=AtomicInteger()
   val backend=object:TileBackend {
    override fun local(key:ResourceKey):TileBody?=null
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit {
     val terrain=key.url.contains("terrain")
     if(terrain)terrainStarts.incrementAndGet()
     val count=active.incrementAndGet();maximum.updateAndGet{maxOf(it,count)}
     val event=timer.schedule({active.decrementAndGet();done(Result.success(TileBody(byteArrayOf(1,2,3,4),200,Long.MAX_VALUE)))},if(terrain)250L else 5L,TimeUnit.MILLISECONDS)
     return{if(event.cancel(false))active.decrementAndGet()}
    }
   }
   val transport=TileTransport(backend,0);transport.movement(true);transport.imageryDemand(reserve)
   val terrain=(0..7).map{transport.consumer(it).also{owner->owner.acquire(ResourceKey("https://fixture.invalid/terrain/$it",false))}}
   try{
    val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
    while(terrainStarts.get()<(if(reserve)6 else 8)&&System.nanoTime()<limit)Thread.yield()
    assertEquals(if(reserve)6 else 8,terrainStarts.get())
    val image=transport.consumer(1,imagery=true);val start=System.nanoTime()
    assertArrayEquals(byteArrayOf(1,2,3,4),image.acquire(ResourceKey("https://fixture.invalid/sharp-image",false)).get(1,TimeUnit.SECONDS).bytes)
    val elapsed=(System.nanoTime()-start)/1e6;image.close()
    assertTrue(maximum.get()<=8)
    if(reserve)assertEquals("Sharper image finishes before slow terrain",6,terrainStarts.get())
    return elapsed
   }finally{terrain.forEach{it.release(true)};transport.close();timer.shutdownNow()}
  }
  repeat(6){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
  fun median(values:List<Double>)=values.sorted().let{(it[2]+it[3])/2}
  val report="""{"scope":"Host JVM production TileTransport, eight foreground slots, eight terrain transfers with injected 250ms latency, newly requested sharper image with 5ms service. Replay controls, not Wi-Fi or phone timings.","trials":6,"beforeMedianMs":${median(before)},"afterMedianMs":${median(after)},"beforeMs":$before,"afterMs":$after,"maximumForegroundTransfers":8,"sameImageBytes":true}"""
  System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"imagery-reserve.json").writeText(report)};println(report)
 }

 @Test fun coldBootstrapUsesAnOriginalAssetAndKeepsAllFineTargetsInsideTheBudget(){
  val catalog=org.json.JSONObject(File(System.getProperty("ridgeline.assets"),"overview-index.json").readText()).getJSONObject("coarse")
  val focus=GpuUploadQueue.Id(12,3983,2600)
  val coarse=SourceDemand.coarseAsset(focus,catalog::has)!!
  assertEquals(GpuUploadQueue.Id(8,248,162),coarse)
  val asset=catalog.getJSONObject("${coarse.z}/${coarse.x}/${coarse.y}").getString("natural")
  assertTrue(File(System.getProperty("ridgeline.assets"),asset).length()>0)
  val leaves=(0..11).map{GpuUploadQueue.Id(12,3983+it%6,2600+it/6)}
  val original=SourceDemand.bounded(leaves,16);val actual=SourceDemand.bootstrap(original,coarse,16)
  assertEquals(0,actual[coarse]);assertTrue(actual.size<=16);assertTrue(actual.keys.containsAll(leaves))
  val focusParent=original.entries.first{it.value==0}.key
  assertTrue(actual.containsKey(focusParent))
  assertEquals(original,SourceDemand.bootstrap(original,GpuUploadQueue.Id(8,1,1),16))
 }

 @Test fun realCacheFreeHttpWinnerSurvivesRamEvictionAndRestartWithoutAnotherRequest(){
  val socket=java.net.ServerSocket(0,50,java.net.InetAddress.getByName("127.0.0.1"))
  val server=Executors.newSingleThreadExecutor();val writer=Executors.newSingleThreadExecutor()
  val dir=Files.createTempDirectory("escaped-http").toFile();val body="exact original encoded body".toByteArray()
  server.execute{socket.accept().use{c->val input=c.getInputStream().bufferedReader();input.readLine();while(!input.readLine().isNullOrEmpty()){}
   c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}}}
  val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).cache(okhttp3.Cache(File(dir,"http"),1048576)).build()
  val store=HedgedBodyCache(File(dir,"escaped"),1048576);val saved=CompletableFuture<Unit>();val delivered=CompletableFuture<TileBody>()
  val backend=HttpTileBackend(client,escapeRead={store.get(it)},escapeWrite={key,value->writer.execute{store.put(key,value);saved.complete(Unit)}},offlineRead={null})
  val key=ResourceKey("http://127.0.0.1:${socket.localPort}/tile",false)
  try{
   backend.uncachedNetwork(key){it.fold(delivered::complete,delivered::completeExceptionally)}
   val response=delivered.get(2,TimeUnit.SECONDS);assertArrayEquals(body,response.bytes);saved.get(2,TimeUnit.SECONDS)
   // The old path has no HTTP-cache entry. There is no retained transport RAM.
   assertNull(HttpTileBackend(client){null}.local(key))
   val restarted=HedgedBodyCache(File(dir,"escaped"),1048576)
   assertArrayEquals(body,restarted.get(key)!!.bytes)
   assertNull(restarted.get(key,response.until+1))
   assertArrayEquals(body,restarted.get(key.copy(offline=true),response.until+1)!!.bytes)
   val forbidden=ResourceKey(key.url+"/no-store",false)
   restarted.put(forbidden,TileBody(body,200,0));assertNull(restarted.get(forbidden))
   assertArrayEquals(body,backend.local(key)!!.bytes)
  }finally{socket.close();server.shutdownNow();writer.shutdown();writer.awaitTermination(1,TimeUnit.SECONDS);client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();client.cache!!.close();dir.deleteRecursively()}
 }

 @Test fun timedOutReadsKeepTheirPermitsAndLateValuesCannotReplaceMisses(){
  val gate=DerivedReadGate(20,2);val entered=CountDownLatch(2);val release=CountDownLatch(1)
  try{
   val work=List(2){gate.read(0){entered.countDown();release.await();"late"}}
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   work.forEach{assertNull(it.get(1,TimeUnit.SECONDS))}
   var extraRan=false;assertNull(gate.read(0){extraRan=true;"extra"}.get())
   assertFalse("Timed-out running I/O still owns both permits",extraRan)
   release.countDown();work.forEach{assertNull(it.get())}
  }finally{release.countDown();gate.close()}
  assertNull(gate.read(0){"closed"}.get())
 }

 @Test fun sharedDecodeSuspendsAndReusesTheCompletedResultWithoutBlockingOtherTiles(){
  val shared=SharedDecode<String,FloatArray>();val waiter=SharedDecode.Waiter<String,FloatArray>()
  val pool=Executors.newFixedThreadPool(2);val producerEntered=CountDownLatch(1);val release=CountDownLatch(1)
  val producerCalls=AtomicInteger();val sample=floatArrayOf(123f)
  try{
   val producer=pool.submit<FloatArray>{shared.get("png"){producerCalls.incrementAndGet();producerEntered.countDown();release.await();sample}}
   assertTrue(producerEntered.await(1,TimeUnit.SECONDS))
   val yielded=pool.submit<Boolean>{try{shared.get("png",waiter){error("Must share")};false}catch(_:ResourcePending){true}}
   assertTrue(yielded.get(1,TimeUnit.SECONDS))
   assertEquals("Unrelated foreground work can use the second worker",7,pool.submit<Int>{7}.get(1,TimeUnit.SECONDS).toInt())
   release.countDown();assertSame(sample,producer.get(1,TimeUnit.SECONDS))
   assertSame(sample,shared.get("png",waiter){error("Completed shared decode must survive producer removal")})
   assertEquals(1,producerCalls.get());waiter.clear()
  }finally{release.countDown();pool.shutdownNow()}
 }

 @Test fun benchmarkActualPreparedCacheWithBlockedReadersAndGenuineDemMesh(){
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE")?.let(::File)
  val output=System.getenv("RIDGELINE_BENCH_OUTPUT")?.let(::File)
  assumeTrue(fixture!=null&&output!=null)
  val imageRead=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java)
  val getRGB=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
  val rasters=HashMap<GpuUploadQueue.Id,DemSampling.Raster>()
  val ids=(3982..3984).map{GpuUploadQueue.Id(12,it,2600)}
  for(id in ids)for(input in DemSampling.footprint(id.z,id.x,id.y,11))if(input !in rasters){
   val image=imageRead.invoke(null,File(fixture,"elevation-${input.z}-${input.x}-${input.y}.png").inputStream().use{it.readBytes()}.inputStream())
   val colors=getRGB.invoke(image,0,0,256,256,null,0,256)as IntArray
   assertTrue(colors.all{it ushr 24==255})
   rasters[input]=DemSampling.Raster(input,FloatArray(colors.size){(-10000+(colors[it]and 0xffffff)*.1).toFloat()})
  }
  fun mesh(id:GpuUploadQueue.Id)=ByteBuffer.allocateDirect(66580).order(ByteOrder.LITTLE_ENDIAN).apply{
   DemSampling.terrainMesh(id.z,id.x,id.y,this){rasters.getValue(it)}
   putDouble(66564,Double.NaN);putDouble(66572,Double.NaN)
  }
  fun bytes(buffer:ByteBuffer)=ByteArray(buffer.capacity()).also{buffer.duplicate().apply{clear();get(it)}}
  val directory=Files.createTempDirectory("derived-stall").toFile()
  val cache=PreparedTerrainCache(directory,4*1024*1024,"fixture")
  for(id in ids)cache.put(id,mesh(id),emptyList())
  val expected=bytes(mesh(ids[1]));val before=ArrayList<Double>();val after=ArrayList<Double>()
  val healthyBefore=ArrayList<Double>();val healthyAfter=ArrayList<Double>()
  fun run(current:Boolean,stalled:Boolean):Double {
   val cpu=Executors.newFixedThreadPool(2);val disk=DerivedReadGate();val timer=Executors.newSingleThreadScheduledExecutor()
   val held=CountDownLatch(1);val release=CountDownLatch(1)
   val holder=if(stalled)Thread{synchronized(cache){held.countDown();release.await()}}.apply{start()}else null
   if(stalled){assertTrue(held.await(1,TimeUnit.SECONDS));timer.schedule({release.countDown()},250,TimeUnit.MILLISECONDS)}
   fun load(id:GpuUploadQueue.Id,started:CountDownLatch?=null):CompletableFuture<ByteBuffer>{
    val done=CompletableFuture<ByteBuffer>();var probe:CompletableFuture<ByteBuffer?>?=null
    lateinit var work:Runnable
    work=Runnable{try{
     val value=if(current){
      val ready=probe?:disk.read(0){cache.get(id){true}}.also{probe=it;started?.countDown()}
      if(!ready.isDone)throw ResourcePending(ready)
      ready.getNow(null)
     }else{started?.countDown();cache.get(id){true}}
     done.complete(value?:mesh(id))
    }catch(pending:ResourcePending){pending.ready.whenComplete{_,_->cpu.execute(work)}}catch(error:Throwable){done.completeExceptionally(error)}}
    cpu.execute(work);return done
   }
   try{
    if(stalled){val started=CountDownLatch(2);load(ids[0],started);load(ids[2],started);assertTrue(started.await(1,TimeUnit.SECONDS))}
    val start=System.nanoTime();val actual=load(ids[1]).get(2,TimeUnit.SECONDS)
    val elapsed=(System.nanoTime()-start)/1e6
    assertArrayEquals(expected,bytes(actual))
    if(current&&stalled)assertEquals("Foreground mesh finishes while the cache remains locked",1L,release.count)
    return elapsed
   }finally{release.countDown();holder?.join(1000);disk.close();cpu.shutdown();assertTrue(cpu.awaitTermination(1,TimeUnit.SECONDS));timer.shutdownNow()}
  }
  try{
   repeat(3){run(false,false);run(true,false)}
   repeat(6){i->if(i%2==0){before+=run(false,true);after+=run(true,true)}else{after+=run(true,true);before+=run(false,true)}}
   repeat(10){healthyBefore+=run(false,false);healthyAfter+=run(true,false)}
   fun median(values:List<Double>)=values.sorted().let{(it[(it.size-1)/2]+it[it.size/2])/2}
   val report="""{"scope":"Host JVM production PreparedTerrainCache, DerivedReadGate and DemSampling; 250 ms injected cache-monitor hold, two occupied CPU workers, genuine LINZ rasters already decoded in RAM, 129x129 mesh. Excludes network, BitmapFactory, JNI profile, GPU and display.","trials":6,"beforeMedianMs":${median(before)},"afterMedianMs":${median(after)},"beforeMs":$before,"afterMs":$after,"healthyBeforeMedianMs":${median(healthyBefore)},"healthyAfterMedianMs":${median(healthyAfter)},"healthyBeforeMs":$healthyBefore,"healthyAfterMs":$healthyAfter,"allMeshBytesIdentical":true,"currentCompletedWhileCacheLocked":true}"""
   output!!.mkdirs();File(output,"derived-stall.json").writeText(report);println(report)
  }finally{directory.deleteRecursively()}
 }
}
