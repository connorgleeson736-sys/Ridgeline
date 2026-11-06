package nz.ridgeline.nativeapp

import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RetiredHttpTest {
 @Test fun actualCanceledOffscreenCallsCannotOccupyTheCurrentViewsEscapeQueue(){
  val socket=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));val server=Executors.newCachedThreadPool();val requests=AtomicInteger()
  val payload="current sharp image bytes".toByteArray()
  server.execute{try{while(!socket.isClosed){val connection=socket.accept();server.execute{connection.use{c->try{
   val reader=c.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
   requests.incrementAndGet();c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray());write(payload);flush()}
  }catch(_:Exception){}}}}}catch(_:SocketException){}}
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  fun run(isolated:Boolean):Double {
   val dir=Files.createTempDirectory("retired-http").toFile()
   val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(Dispatcher().apply{maxRequests=2;maxRequestsPerHost=2}).cache(Cache(dir,1048576)).build()
   val sharedEscape=client.newBuilder().cache(null).build() // previous implementation
   val current=HttpTileBackend(client){null}
   val lock=client.cache!!.javaClass.getDeclaredField("cache").apply{isAccessible=true}.get(client.cache)!!
   val held=CountDownLatch(1);val release=CountDownLatch(1)
   val holder=Thread{synchronized(lock){held.countDown();release.await(3,TimeUnit.SECONDS)}}.apply{start()}
   val timer=Executors.newSingleThreadScheduledExecutor()
   val old=(0..1).map{client.newCall(Request.Builder().url("http://127.0.0.1:${socket.localPort}/offscreen/$it").build())}
   try{
    assertTrue(held.await(1,TimeUnit.SECONDS))
    val ignore=object:Callback{override fun onFailure(call:Call,e:IOException){};override fun onResponse(call:Call,response:Response){response.close()}}
    old.forEach{it.enqueue(ignore)}
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
    fun blockedReaders()=Thread.getAllStackTraces().count{(thread,stack)->thread.state==Thread.State.BLOCKED&&stack.any{it.className=="okhttp3.internal.cache.DiskLruCache"}}
    while(blockedReaders()<2&&System.nanoTime()<until)Thread.yield()
    assertTrue("Both real HTTP workers must enter the held cache monitor",blockedReaders()>=2)
    old.forEach{it.cancel()}
    assertEquals(2,client.dispatcher.runningCallsCount());assertTrue(old.all{it.isCanceled()})
    val result=CompletableFuture<TileBody>();val start=System.nanoTime()
    timer.schedule({release.countDown()},250,TimeUnit.MILLISECONDS)
    val key=ResourceKey("http://127.0.0.1:${socket.localPort}/current",false)
    if(isolated){
     // Ordinary network admission must detect retired underlying work, even
     // when its separate local lookup completed normally (no local hedge).
     current.network(key){it.fold(result::complete,result::completeExceptionally)}
    }else{
     sharedEscape.newCall(Request.Builder().url(key.url).build()).enqueue(object:Callback{
      override fun onFailure(call:Call,e:IOException){result.completeExceptionally(e)}
      override fun onResponse(call:Call,response:Response){response.use{result.complete(TileBody(it.body!!.bytes(),it.code,Long.MAX_VALUE))}}
     })
    }
    assertArrayEquals(payload,result.get(2,TimeUnit.SECONDS).bytes)
    val elapsed=(System.nanoTime()-start)/1e6
    if(isolated){assertEquals(1L,release.count);assertEquals(2L,current.stats()["retiredHttpRunning"])}
    return elapsed
   }finally{
    release.countDown();holder.join(1000);timer.shutdownNow();old.forEach{it.cancel()};current.close()
    client.dispatcher.cancelAll();client.dispatcher.executorService.shutdown();assertTrue(client.dispatcher.executorService.awaitTermination(2,TimeUnit.SECONDS))
    client.connectionPool.evictAll();client.cache!!.close();dir.deleteRecursively()
   }
  }
  try{
   repeat(4){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
   fun median(values:List<Double>)=values.sorted().let{(it[1]+it[2])/2}
   assertEquals("Canceled requests never reached the HTTP server",8,requests.get())
   val report="""{"scope":"Host JVM actual OkHttp running calls and cache monitor; two canceled off-screen calls still occupy a two-slot dispatcher, cache held 250ms after current-view enqueue. Current bytes delivered over loopback HTTP. Reduced dispatcher capacity is a replay control; no phone/Wi-Fi timing claim.","trials":4,"beforeMedianMs":${median(before)},"afterMedianMs":${median(after)},"beforeMs":$before,"afterMs":$after,"canceledCallsStillRunningWhenCurrentCompletes":2,"httpRequests":${requests.get()},"sameCurrentImageBytes":true}"""
   System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"retired-http.json").writeText(report)};println(report)
  }finally{socket.close();server.shutdownNow()}
 }
}
