package nz.ridgeline.nativeapp

import okhttp3.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DownloadLimitsTest {
 private fun until(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!check()&&System.nanoTime()<end)Thread.sleep(1);assertTrue(check())}
 @Test fun limitsApplyLiveAcrossMovementGrowthShrinkAndOneSlotTerrain(){
  assertEquals(DownloadLimits(8,12,2),DownloadLimits.DEFAULT)
  assertEquals(DownloadLimits(1,50,1),DownloadLimits(-1,500,0).normalized())
  val callbacks=ConcurrentHashMap<ResourceKey,(Result<TileBody>)->Unit>();val cancelled=AtomicInteger()
  val backend=object:TileBackend {
   override fun local(key:ResourceKey):TileBody?=null
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit {callbacks[key]=done;return{callbacks.remove(key);cancelled.incrementAndGet()}}
  }
  val transport=TileTransport(backend,0,initialLimits=DownloadLimits(1,2,1));val owners=ArrayList<TileTransport.Consumer>()
  fun acquire(name:String,background:Boolean=false,imagery:Boolean=false):ResourceKey {
   val key=ResourceKey("https://fixture.invalid/$name",false);val owner=transport.consumer(speculative=background,imagery=imagery);owners+=owner;owner.acquire(key);return key
  }
  try{
   transport.movement(true);transport.imageryDemand(true)
   val terrain=acquire("terrain");until{callbacks.containsKey(terrain)} // limit 1 must not reserve away terrain's only slot
   val images=(0..49).map{acquire("image/$it",imagery=true)}
   transport.limits(DownloadLimits(1,50,1));assertEquals(1L,transport.limitStats()["foregroundLimit"])
   transport.movement(false);until{callbacks.size==50}
   assertEquals(50L,transport.stats()["networkActive"])
   val waiting=images.single{!callbacks.containsKey(it)}
   val active=callbacks.keys.toList()
   transport.limits(DownloadLimits(1,1,1));assertEquals(0,cancelled.get())
   for(key in active.dropLast(1)){callbacks.remove(key)!!(Result.success(TileBody(byteArrayOf(1),200,Long.MAX_VALUE)))}
   assertEquals(1,callbacks.size);assertFalse(callbacks.containsKey(waiting))
   callbacks.remove(active.last())!!(Result.success(TileBody(byteArrayOf(1),200,Long.MAX_VALUE)))
   until{callbacks.containsKey(waiting)};assertEquals(1,callbacks.size)
   val a=acquire("background/a",true);val b=acquire("background/b",true)
   until{callbacks.containsKey(a)||callbacks.containsKey(b)};assertEquals(1L,transport.stats()["backgroundActive"])
   transport.limits(DownloadLimits(1,1,50));until{callbacks.containsKey(a)&&callbacks.containsKey(b)}
   assertEquals(2L,transport.stats()["backgroundActive"])
  }finally{owners.forEach{it.release(true)};transport.close()}
 }

 @Test fun fiftyJobsReachRealHttpOnBothNormalAndIsolatedPaths(){
  for(escape in listOf(false,true)){
   val socket=ServerSocket(0,100,InetAddress.getByName("127.0.0.1"));val server=Executors.newCachedThreadPool()
   val entered=CountDownLatch(50);val release=CountDownLatch(1);val requests=AtomicInteger()
   server.execute{try{while(!socket.isClosed){val connection=socket.accept();server.execute{connection.use{c->try{
    val reader=c.getInputStream().bufferedReader();reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
    requests.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS)
    c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nCache-Control: public, max-age=60\r\nConnection: close\r\n\r\nx".toByteArray());flush()}
   }catch(_:Exception){}}}}}catch(_:SocketException){}}
   val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dispatcher(Dispatcher().apply{maxRequests=DownloadLimits.HTTP_CAPACITY;maxRequestsPerHost=DownloadLimits.HTTP_CAPACITY}).build()
   val http=HttpTileBackend(client){null}
   val backend=if(!escape)http else object:TileBackend {
    override fun local(key:ResourceKey):TileBody?=null
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit)=http.uncachedNetwork(key,done)
    override fun close()=http.close()
   }
   val transport=TileTransport(backend,0,initialLimits=DownloadLimits(50,50,1));val owners=ArrayList<TileTransport.Consumer>()
   try{
    val results=(0..50).map{i->val owner=transport.consumer(imagery=true);owners+=owner;owner.acquire(ResourceKey("http://127.0.0.1:${socket.localPort}/$i",false))}
    assertTrue("All 50 jobs must reach the actual HTTP server; escape=$escape",entered.await(5,TimeUnit.SECONDS))
    assertEquals(50,requests.get());assertEquals(50L,transport.stats()["networkActive"])
    release.countDown();results.forEach{assertArrayEquals(byteArrayOf('x'.code.toByte()),it.get(5,TimeUnit.SECONDS).bytes)}
    assertEquals(51,requests.get())
   }finally{release.countDown();owners.forEach{it.release(true)};transport.close();socket.close();server.shutdownNow();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
  }
 }
}
