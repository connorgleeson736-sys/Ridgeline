package nz.ridgeline.nativeapp

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.net.*
import java.nio.file.Files
import okhttp3.*

class TileTransportTest {
 private class Controlled:TileBackend{
    val pending=ConcurrentHashMap<ResourceKey,(Result<TileBody>)->Unit>();val starts=AtomicInteger();val stops=AtomicInteger();val cancelled=ConcurrentHashMap.newKeySet<ResourceKey>()
    override fun local(key:ResourceKey):TileBody?=null
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{pending[key]=done;starts.incrementAndGet();return{cancelled.add(key);stops.incrementAndGet()}}
    fun complete(key:ResourceKey,bytes:String="original",until:Long=System.currentTimeMillis()+60000){pending.remove(key)!!(Result.success(TileBody(bytes.toByteArray(),200,until)))}
 }
 private fun waitUntil(test:()->Boolean){val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!test()){if(System.nanoTime()>deadline)fail("Timed out waiting for asynchronous state");Thread.sleep(2)}}
 @Test fun lastConsumerCancellationAndQuickReturnDoNotPoisonSharedWork(){
    val backend=Controlled();val transport=TileTransport(backend,1024,graceMs=80)
    try{
     val key=ResourceKey("https://example.invalid/shared",false)
     val a=transport.consumer();val b=transport.consumer();val first=a.acquire(key);val second=b.acquire(key)
     waitUntil{backend.starts.get()==1};a.close();assertEquals(0,backend.stops.get())
     b.close();val returning=transport.consumer();val third=returning.acquire(key)
     Thread.sleep(100);assertEquals(0,backend.stops.get());assertTrue(first.isCompletedExceptionally);assertTrue(second.isCompletedExceptionally);assertFalse(third.isDone)
     backend.complete(key);assertEquals("original",String(third.get(1,TimeUnit.SECONDS).bytes));returning.close()
     val obsolete=transport.consumer();obsolete.acquire(ResourceKey("https://example.invalid/obsolete",false))
     waitUntil{backend.starts.get()==2};obsolete.close();waitUntil{backend.stops.get()==1}
    }finally{transport.close()}
 }
 @Test fun foregroundJoinPromotesQueuedBackgroundTransfer(){
    val backend=Controlled();val transport=TileTransport(backend,1024)
    try{
     val background=(0..2).map{transport.consumer(1000,true)};val keys=(0..2).map{ResourceKey("https://example.invalid/$it",false)}
     background.zip(keys).forEach{(consumer,key)->consumer.acquire(key)}
     waitUntil{backend.starts.get()==2}
     val queued=keys.first{!backend.pending.containsKey(it)};val foreground=transport.consumer(0,false);foreground.acquire(queued)
     waitUntil{backend.starts.get()==3};assertTrue(backend.pending.containsKey(queued))
     background.forEach{it.close()};assertFalse(queued in backend.cancelled);backend.complete(queued);foreground.close()
    }finally{transport.close()}
 }
 @Test fun freshRamReusePreservesDeadlineAndVersion(){
    var now=1000L;val backend=Controlled();val transport=TileTransport(backend,1024,{now})
    try{
     val key=ResourceKey("https://example.invalid/fresh",false);val a=transport.consumer();val first=a.acquire(key)
     waitUntil{backend.starts.get()==1};backend.complete(key,"v1",2000);val stamp=first.get().stamp!!;a.close()
     val b=transport.consumer();assertEquals(2000,b.acquire(key).get().until);b.close();assertEquals(1,backend.starts.get());assertTrue(transport.fresh(stamp))
     now=2001;assertFalse(transport.fresh(stamp));val c=transport.consumer();val next=c.acquire(key)
     waitUntil{backend.starts.get()==2};backend.complete(key,"v2",3000);next.get();assertFalse(transport.fresh(stamp));c.close()
    }finally{transport.close()}
 }
 @Test fun actualHttpDiskHitFinishesWhileAllForegroundNetworkSlotsAreBlocked(){
    val socket=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));val release=CountDownLatch(1);val entered=CountDownLatch(8)
    val server=Executors.newCachedThreadPool();val dir=Files.createTempDirectory("tile-http-admission").toFile()
    server.execute{try{while(!socket.isClosed){val connection=socket.accept();server.execute{connection.use{client->
     try{val reader=client.getInputStream().bufferedReader();val request=reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
      if(request.contains("/slow")){entered.countDown();release.await(5,TimeUnit.SECONDS)}
      val body="exact tile body".toByteArray();client.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
     }catch(_:Exception){}
    }}}}catch(_:SocketException){}}
    val dispatcher=Dispatcher().apply{maxRequests=32;maxRequestsPerHost=16}
    val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(dispatcher).cache(Cache(dir,4L*1024*1024)).build()
    val transport=TileTransport(HttpTileBackend(client){null},0)
    try{
     val base="http://127.0.0.1:${socket.localPort}";val key=ResourceKey("$base/cached",false)
     val first=transport.consumer();first.acquire(key).get(2,TimeUnit.SECONDS);first.close()
     transport.movement(true)
     val slow=(0..7).map{transport.consumer().also{c->c.acquire(ResourceKey("$base/slow/$it",false))}}
     assertTrue(entered.await(2,TimeUnit.SECONDS))
     val returning=transport.consumer();val start=System.nanoTime();val bytes=returning.acquire(key).get(1,TimeUnit.SECONDS).bytes
     val elapsed=(System.nanoTime()-start)/1e6
     assertEquals("exact tile body",String(bytes));assertEquals(1L,release.count);assertTrue(transport.stats().getValue("localHits")>=1)
     println("ACTUAL_HTTP_CACHE: cached return ${elapsed}ms while 8 network transfers remain gated; identical bytes")
     returning.close();slow.forEach{it.release(true)}
    }finally{release.countDown();transport.close();socket.close();server.shutdownNow();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();client.cache!!.close();dir.deleteRecursively()}
 }
 @Test fun absoluteHttpExpiryDoesNotRestartOnDiskRead(){
    val response=Response.Builder().request(Request.Builder().url("https://example.invalid/tile").build()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
      .header("Cache-Control","public,max-age=100").header("Age","20").sentRequestAtMillis(1000000).receivedResponseAtMillis(1001000).build()
    assertEquals(1080000L,HttpFreshness.deadline(response,1050000))
    assertEquals(HttpFreshness.deadline(response,1050000),HttpFreshness.deadline(response,1060000))
 }
}
