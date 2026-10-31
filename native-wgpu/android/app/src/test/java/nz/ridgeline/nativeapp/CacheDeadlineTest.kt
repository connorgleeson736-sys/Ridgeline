package nz.ridgeline.nativeapp

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.IOException
import java.net.*
import java.nio.file.Files
import okhttp3.*

class CacheDeadlineTest {
 private fun waitUntil(test:()->Boolean){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!test()){if(System.nanoTime()>until)fail("Timed out");Thread.sleep(1)}}
 @Test fun aStalledCacheCannotBlockForegroundNetworkAndLaterLookupsBypassIt(){
  val entered=CountDownLatch(2);val release=CountDownLatch(1);val starts=AtomicInteger();val reads=AtomicInteger()
  val backend=object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{reads.incrementAndGet();entered.countDown();release.await(3,TimeUnit.SECONDS);return null}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=error("Hedge must escape cache")
   override fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();done(Result.success(TileBody(key.url.toByteArray(),200,Long.MAX_VALUE)));return{}}
  }
  val transport=TileTransport(backend,1024,localWaitMs=25)
  try{
   val a=transport.consumer(1000,true);val b=transport.consumer(1000,true);val x=a.acquire(ResourceKey("a",false));val y=b.acquire(ResourceKey("b",false))
   assertTrue(entered.await(2,TimeUnit.SECONDS));a.promote(0);b.promote(1);assertEquals("a",String(x.get(1,TimeUnit.SECONDS).bytes));y.get(1,TimeUnit.SECONDS)
   assertEquals(1L,release.count);assertEquals(2,reads.get())
   val next=transport.consumer();assertEquals("c",String(next.acquire(ResourceKey("c",false)).get(1,TimeUnit.SECONDS).bytes));assertEquals(2,reads.get());assertEquals(3,starts.get())
   assertEquals(0L,transport.stats()["networkActive"]);assertEquals(0L,transport.stats()["pending"])
  }finally{release.countDown();transport.close()}
 }
 @Test fun immediateCooldownBypassCannotRestartAJobAfterItsNetworkAlreadyFinished(){
  val release=CountDownLatch(1);val requests=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{release.await(4,TimeUnit.SECONDS);return null}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
    requests.incrementAndGet();done(Result.success(TileBody(byteArrayOf(1),200,Long.MAX_VALUE)));return{}
   }
  },0,localWaitMs=10)
  val callers=Executors.newFixedThreadPool(8)
  try{
   transport.consumer().acquire(ResourceKey("open-circuit",false)).get(1,TimeUnit.SECONDS)
   val futures=(0..199).map{i->callers.submit{val c=transport.consumer();try{c.acquire(ResourceKey("race$i",false)).get(2,TimeUnit.SECONDS)}finally{c.close()}}}
   futures.forEach{it.get(3,TimeUnit.SECONDS)}
   assertEquals(201,requests.get());assertEquals(0L,transport.stats()["networkActive"]);assertEquals(0L,transport.stats()["pending"])
  }finally{release.countDown();transport.close();callers.shutdownNow()}
 }
 @Test fun twoHundredStalledCacheReadsShareOneDeadlineWake(){
  val release=CountDownLatch(1);val entered=CountDownLatch(2);val starts=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{entered.countDown();release.await(3,TimeUnit.SECONDS);return null}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();return{}}
  },0,localWaitMs=250)
  try{
   transport.movement(true)
   val owners=(0..199).map{i->transport.consumer(1000,true).also{it.acquire(ResourceKey("batch$i",false))}}
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   owners.forEachIndexed{i,c->c.promote(i)};waitUntil{starts.get()==8}
   assertEquals(1L,transport.stats()["localWatchRuns"]);assertEquals(200L,transport.stats()["localTimeouts"])
   owners.forEach{it.release(true)};assertEquals(0L,transport.stats()["networkActive"])
  }finally{release.countDown();transport.close()}
 }
 @Test fun offlineAndUnpromotedSpeculationNeverStartHedges(){
  val release=CountDownLatch(1);val starts=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{release.await(2,TimeUnit.SECONDS);return TileBody(byteArrayOf(7),200,Long.MAX_VALUE)}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();error("Forbidden")}
  },1024,localWaitMs=10)
  try{
   val offline=transport.consumer();val background=transport.consumer(1000,true)
   val a=offline.acquire(ResourceKey("saved",true));val b=background.acquire(ResourceKey("prediction",false))
   Thread.sleep(60);assertEquals(0,starts.get());assertFalse(a.isDone);assertFalse(b.isDone)
   release.countDown();assertEquals(7,a.get().bytes[0].toInt());b.get()
  }finally{release.countDown();transport.close()}
 }
 @Test fun failedHedgeStillWaitsForAValidLocalHit(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val attempted=CountDownLatch(1)
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{entered.countDown();release.await(2,TimeUnit.SECONDS);return TileBody("saved".toByteArray(),200,Long.MAX_VALUE)}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{attempted.countDown();done(Result.failure(IOException("No connection")));return{}}
  },1024,localWaitMs=15)
  try{
   val owner=transport.consumer();val future=owner.acquire(ResourceKey("offline-fallback",false))
   assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(attempted.await(1,TimeUnit.SECONDS));assertFalse(future.isDone)
   release.countDown();assertEquals("saved",String(future.get(1,TimeUnit.SECONDS).bytes));assertEquals(0L,transport.stats()["networkActive"])
  }finally{release.countDown();transport.close()}
 }
 @Test fun localWinnerCancelsHedgeAndLateNetworkCannotReplaceItsVersion(){
  val release=CountDownLatch(1);val started=CountDownLatch(1);val stopped=AtomicInteger()
  var callback:((Result<TileBody>)->Unit)?=null
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{release.await(2,TimeUnit.SECONDS);return TileBody("local".toByteArray(),200,Long.MAX_VALUE)}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{callback=done;started.countDown();return{stopped.incrementAndGet()}}
  },1024,localWaitMs=15)
  try{
   val key=ResourceKey("winner",false);val a=transport.consumer();val body=a.acquire(key)
   assertTrue(started.await(1,TimeUnit.SECONDS));release.countDown();assertEquals("local",String(body.get(1,TimeUnit.SECONDS).bytes));waitUntil{stopped.get()==1}
   callback!!(Result.success(TileBody("late-network".toByteArray(),200,Long.MAX_VALUE)))
   assertEquals("local",String(transport.consumer().acquire(key).get().bytes));assertEquals(0L,transport.stats()["networkActive"])
  }finally{release.countDown();transport.close()}
 }
 @Test fun networkWinnerSurvivesLateDiskAndLocalProgressReopensHealthyCache(){
  val release=CountDownLatch(1);val reads=AtomicInteger();val requests=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{reads.incrementAndGet();if(key.url=="first")release.await(2,TimeUnit.SECONDS);return TileBody("old-local".toByteArray(),200,Long.MAX_VALUE)}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{requests.incrementAndGet();done(Result.success(TileBody("fresh-network".toByteArray(),200,Long.MAX_VALUE)));return{}}
  },1024,localWaitMs=15)
  try{
   val key=ResourceKey("first",false);val body=transport.consumer().acquire(key).get(1,TimeUnit.SECONDS)
   assertEquals("fresh-network",String(body.bytes));assertTrue(transport.stats().getValue("localCooldownMs")>0)
   release.countDown();waitUntil{transport.stats().getValue("localCooldownMs")==0L}
   assertEquals(body.stamp,transport.consumer().acquire(key).get().stamp)
   assertEquals("old-local",String(transport.consumer().acquire(ResourceKey("healthy",false)).get().bytes))
   assertEquals(2,reads.get());assertEquals(1,requests.get())
  }finally{release.countDown();transport.close()}
 }
 @Test fun queuedSharedHedgeRemainsCancelableAndWithinExistingLimits(){
  val release=CountDownLatch(1);val calls=AtomicInteger();val stops=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?{release.await(3,TimeUnit.SECONDS);return null}
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{calls.incrementAndGet();return{stops.incrementAndGet()}}
  },0,localWaitMs=10)
  try{
   transport.movement(true)
   val owners=(0..11).map{transport.consumer(it).also{c->c.acquire(ResourceKey("tile$it",false))}}
   waitUntil{calls.get()==8};assertEquals(8L,transport.stats()["networkActive"])
   val shared=transport.consumer();val second=shared.acquire(ResourceKey("tile0",false));owners[0].release(true)
   assertFalse(second.isDone);assertEquals(0,stops.get());shared.release(true);waitUntil{stops.get()==1}
   waitUntil{calls.get()==9};assertEquals(8L,transport.stats()["networkActive"])
   owners.drop(1).forEach{it.release(true)};waitUntil{transport.stats()["pending"]==0L};assertEquals(0L,transport.stats()["networkActive"])
  }finally{release.countDown();transport.close()}
 }
 @Test fun localStorageFailureDoesNotPreventOnlineDelivery(){
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?=throw IOException("Broken cache")
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=error("Must bypass broken cache")
   override fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{done(Result.success(TileBody(byteArrayOf(3),200,Long.MAX_VALUE)));return{}}
  },0)
  try{assertEquals(3,transport.consumer().acquire(ResourceKey("source",false)).get(1,TimeUnit.SECONDS).bytes[0].toInt())}finally{transport.close()}
 }
 @Test fun healthyDiskHitsRemainLocalAndMeasureAdmissionOverhead(){
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE")?:return
  val body=File(fixture).listFiles()!!.first{it.name.startsWith("elevation-")&&it.extension=="png"}.readBytes()
  val socket=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));val server=Executors.newCachedThreadPool();val requests=AtomicInteger()
  server.execute{try{while(!socket.isClosed){val c=socket.accept();server.execute{c.use{try{
   val input=it.getInputStream().bufferedReader();input.readLine();while(!input.readLine().isNullOrEmpty()){};requests.incrementAndGet()
   it.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
  }catch(_:Exception){}}}}}catch(_:SocketException){}}
  val dir=Files.createTempDirectory("healthy-disk").toFile();val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(dir,4L*1024*1024)).build()
  val key=ResourceKey("http://127.0.0.1:${socket.localPort}/cached",false);val backend=HttpTileBackend(client){null}
  val old=BeforeDeadlineTransport(backend,0);val current=TileTransport(backend,0)
  try{
   client.newCall(Request.Builder().url(key.url).build()).execute().use{assertArrayEquals(body,it.body!!.bytes())}
   val a=ArrayList<Double>();val b=ArrayList<Double>()
   fun run(fresh:Boolean):Double {
    val start=System.nanoTime()
    if(fresh){val c=current.consumer();try{assertArrayEquals(body,c.acquire(key).get(2,TimeUnit.SECONDS).bytes)}finally{c.close()}}
    else{val c=old.consumer();try{assertArrayEquals(body,c.acquire(key).get(2,TimeUnit.SECONDS).bytes)}finally{c.close()}}
    return (System.nanoTime()-start)/1e6
   }
   repeat(50){i->val x:Double;val y:Double;if(i%2==0){x=run(false);y=run(true)}else{y=run(true);x=run(false)};if(i>=10){a+=x;b+=y}}
   assertEquals(1,requests.get());assertEquals(0L,current.stats()["uncachedStarts"])
   val report="""{"scope":"Host JVM healthy actual OkHttp disk hits, 114kB-class genuine LINZ PNG, RAM retention disabled, same cache/client, acquire to validated bytes; no phone claim.","beforeMedianMs":${a.sorted()[20]},"afterMedianMs":${b.sorted()[20]},"beforeMs":$a,"afterMs":$b,"networkRequestsIncludingWarmup":${requests.get()}}"""
   println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"healthy-cache.json").writeText(report)}
  }finally{old.close();current.close();socket.close();server.shutdownNow();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();client.cache!!.close();dir.deleteRecursively()}
 }
 @Test fun actualOkHttpCacheMonitorCanBeBypassedWithIdenticalTileBytes(){
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE")?:return
  val bytes=File(fixture).listFiles()!!.first{it.name.startsWith("elevation-")&&it.extension=="png"}.readBytes()
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  val socket=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));val server=Executors.newCachedThreadPool();val requests=AtomicInteger()
  server.execute{try{while(!socket.isClosed){val connection=socket.accept();server.execute{connection.use{c->try{
   val reader=c.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()){};requests.incrementAndGet()
   c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
  }catch(_:Exception){}}}}}catch(_:SocketException){}}
  fun run(current:Boolean):Double{
   val dir=Files.createTempDirectory("deadline-http").toFile()
   val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(dir,4L*1024*1024)).build()
   val backend=HttpTileBackend(client){null}
   val transport=if(current)TileTransport(backend,0)else null
   val old=if(!current)BeforeDeadlineTransport(backend,0)else null
   val lock=client.cache!!.javaClass.getDeclaredField("cache").apply{isAccessible=true}.get(client.cache)
   val entered=CountDownLatch(1);val release=CountDownLatch(1);val unlocked=AtomicBoolean(false)
   val worker=Thread{ synchronized(lock){entered.countDown();release.await(3,TimeUnit.SECONDS)};unlocked.set(true)}.apply{start()}
   val timer=Executors.newSingleThreadScheduledExecutor()
   try{
    assertTrue(entered.await(1,TimeUnit.SECONDS));val scheduled=timer.schedule({release.countDown()},400,TimeUnit.MILLISECONDS)
    val key=ResourceKey("http://127.0.0.1:${socket.localPort}/terrain",false);val start=System.nanoTime()
    val future=transport?.consumer()?.acquire(key)?:old!!.consumer().acquire(key)
    val result=future.get(2,TimeUnit.SECONDS);val elapsed=(System.nanoTime()-start)/1e6
    assertArrayEquals(bytes,result.bytes)
    if(current){assertFalse("Network must finish while the actual disk monitor is locked",unlocked.get());assertTrue(transport!!.stats().getValue("uncachedStarts")>=1)}
    scheduled.cancel(false);return elapsed
   }finally{release.countDown();worker.join(1000);timer.shutdownNow();transport?.close();old?.close();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();client.cache!!.close();dir.deleteRecursively()}
  }
  try{
   repeat(12){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
   val report="""{"scope":"Host JVM actual OkHttp disk-cache monitor held 400ms; genuine LINZ PNG over loopback HTTP, acquire to validated body. No phone disk or remote-network/GPU claim.","bodyBytes":${bytes.size},"beforeMedianMs":${before.sorted()[6]},"afterMedianMs":${after.sorted()[6]},"beforeMs":$before,"afterMs":$after,"httpRequests":${requests.get()}}"""
   println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"cache-deadline.json").writeText(report)}
  }finally{socket.close();server.shutdownNow()}
 }
}
