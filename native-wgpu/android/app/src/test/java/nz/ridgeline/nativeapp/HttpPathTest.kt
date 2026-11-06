package nz.ridgeline.nativeapp

import okhttp3.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import org.junit.Assert.*
import org.junit.Test

class HttpPathTest {
 private class Server(private val reply:(String,Map<String,String>)->Pair<List<String>,ByteArray>):AutoCloseable {
  private val socket=ServerSocket(0,100,InetAddress.getByName("127.0.0.1"));private val workers=Executors.newCachedThreadPool()
  val port get()=socket.localPort
  val requests=AtomicInteger()
  init{workers.execute{try{while(!socket.isClosed){val c=socket.accept();workers.execute{c.use{try{
   val reader=c.getInputStream().bufferedReader();val request=reader.readLine()?:return@use
   val headers=HashMap<String,String>();while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;val colon=line.indexOf(':');if(colon>0)headers[line.substring(0,colon).lowercase()]=line.substring(colon+1).trim()}
   requests.incrementAndGet();val (rows,body)=reply(request.split(' ')[1],headers)
   val length=if(rows.any{it.startsWith("Transfer-Encoding:",true)})emptyList()else listOf("Content-Length: ${body.size}")
   c.getOutputStream().apply{write((rows+length+listOf("Connection: close","","")).joinToString("\r\n").toByteArray());write(body);flush()}
  }catch(_:IOException){}}}}}catch(_:SocketException){}}}
  fun url(path:String)="http://127.0.0.1:$port$path"
  override fun close(){socket.close();workers.shutdownNow()}
 }
 private fun close(client:OkHttpClient,backend:HttpTileBackend?=null){
  backend?.close();backend?.awaitClosed();client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll();client.cache?.close()
 }
 @Test fun benchmarkColdWireDeliveryDoesNotWaitForHttpCachePublication(){
  val payload="identical original image response".toByteArray()
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  Server{_,_->listOf("HTTP/1.1 200 OK","Cache-Control: public, max-age=3600") to payload}.use{server->
   fun run(deferred:Boolean):Double {
    val dir=Files.createTempDirectory("deferred-http").toFile();val cache=Cache(dir,4_194_304)
    val disk=cache.javaClass.getDeclaredField("cache").apply{isAccessible=true}.get(cache)!!
    val held=CountDownLatch(1);val release=CountDownLatch(1);val timer=Executors.newSingleThreadScheduledExecutor()
    var holder:Thread?=null
    val metrics=HttpMetrics()
    val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(cache).eventListenerFactory{metrics.listener(it)}.addNetworkInterceptor{metrics.intercept(it)}.addNetworkInterceptor{chain->
     val response=chain.proceed(chain.request())
     holder=Thread{synchronized(disk){held.countDown();release.await(3,TimeUnit.SECONDS)}}.also{it.start()}
     assertTrue(held.await(1,TimeUnit.SECONDS));timer.schedule({release.countDown()},500,TimeUnit.MILLISECONDS)
     response
    }.build()
    val backend=HttpTileBackend(client,metrics=metrics){null};val key=ResourceKey(server.url("/body/${System.nanoTime()}?api=secret-fixture-key"),false)
    try{
     val result=CompletableFuture<ByteArray>();val start=System.nanoTime()
     if(deferred)backend.network(key){it.fold({body->result.complete(body.bytes)},result::completeExceptionally)}
     else client.newCall(Request.Builder().url(key.url).build()).enqueue(object:Callback{
      override fun onFailure(call:Call,e:IOException){metrics.delivered(call,e);result.completeExceptionally(e)}
      override fun onResponse(call:Call,response:Response){response.use{val bytes=it.body.bytes();metrics.delivered(call);result.complete(bytes)}}
     })
     assertArrayEquals(payload,result.get(2,TimeUnit.SECONDS));val elapsed=(System.nanoTime()-start)/1e6
     assertEquals(payload.size.toLong(),metrics.last()!!.bodyBytes)
     assertFalse("Never expose query credentials in HTTP diagnostics",metrics.snapshot().contains("secret-fixture-key"))
     assertTrue(if(deferred)metrics.last()!!.processingMs<200 else metrics.last()!!.processingMs>=400)
     if(deferred){
      assertEquals("Disk publication remains blocked when bytes are delivered",1L,release.count)
      assertArrayEquals("A zero-RAM transport can reuse pending publication",payload,backend.local(key)!!.bytes)
      release.countDown();backend.awaitCacheWrites();assertEquals(1L,backend.stats()["cacheWrites"])
     }
     return elapsed
    }finally{release.countDown();holder?.join(1000);timer.shutdownNow();close(client,backend);dir.deleteRecursively()}
   }
   repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
   assertEquals(6,server.requests.get())
  }
  val saved=before.sorted()[1]-after.sorted()[1];assertTrue(saved>200)
  val report="""{"scope":"Actual OkHttp HTTP response with disk-cache monitor held for 500ms only after provider headers arrive. Full-size cache publication deferred, same bytes and one wire request per trial; not phone/Wi-Fi latency.","beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"savedMedianMs":$saved,"trials":3,"sameBytes":true,"pendingPublicationReusable":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"http-cache-publication.json").writeText(report)}
 }
 @Test fun benchmarkFastFallbackEscapesAnUnresponsiveFirstAddress(){
  val before=ArrayList<Double>();val after=ArrayList<Double>();val payload=byteArrayOf(11,17,31)
  Server{_,_->listOf("HTTP/1.1 200 OK","Cache-Control: no-store") to payload}.use{server->
   fun run(fast:Boolean):Double {
    val attempts=AtomicInteger();val firstStarted=CountDownLatch(1);val firstClosed=CountDownLatch(1)
    val factory=object:SocketFactory(){
     override fun createSocket():Socket=object:Socket(){
      @Volatile private var first=false
      override fun connect(endpoint:SocketAddress,timeout:Int){
       attempts.incrementAndGet()
       if((endpoint as InetSocketAddress).address is Inet6Address){
        first=true
        firstStarted.countDown();firstClosed.await(650,TimeUnit.MILLISECONDS)
        if(isClosed)throw SocketException("Canceled losing address")
        throw SocketTimeoutException("Injected unresponsive first route")
       }
       super.connect(endpoint,timeout)
      }
      override fun close(){super.close();if(first)firstClosed.countDown()}
     }
     override fun createSocket(host:String,port:Int)=createSocket().apply{connect(InetSocketAddress(host,port))}
     override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
     override fun createSocket(host:InetAddress,port:Int)=createSocket().apply{connect(InetSocketAddress(host,port))}
     override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=createSocket().apply{bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
    }
    val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(factory)
     .dns{listOf(InetAddress.getByName("2001:db8::1"),InetAddress.getByName("127.0.0.1"))}.fastFallback(fast).build()
    try{
     val start=System.nanoTime();client.newCall(Request.Builder().url("http://routes.fixture.invalid:${server.port}/tile").build()).execute().use{assertArrayEquals(payload,it.body.bytes())}
     val elapsed=(System.nanoTime()-start)/1e6
     assertEquals(0L,firstStarted.count);assertEquals(2,attempts.get());assertTrue(firstClosed.await(1,TimeUnit.SECONDS))
     return elapsed
    }finally{close(client)}
   }
   repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
   assertEquals("Address racing must not duplicate HTTP requests",6,server.requests.get())
  }
  val saved=before.sorted()[1]-after.sorted()[1];assertTrue(saved>200)
  val report="""{"scope":"Actual OkHttp route planner, injected 650ms unresponsive IPv6 socket and working loopback IPv4 server, one real HTTP GET per trial. Fast fallback disabled reproduces 4.x sequential routing; not a phone IPv6 diagnosis.","beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"savedMedianMs":$saved,"trials":3,"httpRequests":6,"sameBytes":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"fast-fallback.json").writeText(report)}
 }
 @Test fun fullCachePreservesFreshnessGzipVaryConditionalValidationAndRedirects(){
  val payload="original bytes after transparent gzip".toByteArray()
  val gzip=ByteArrayOutputStream().also{out->java.util.zip.GZIPOutputStream(out).use{it.write(payload)}}.toByteArray()
  val chunked=(gzip.size.toString(16)+"\r\n").toByteArray()+gzip+"\r\n0\r\n\r\n".toByteArray()
  val validated=AtomicInteger()
  Server{path,headers->when(path){
   "/redirect"->listOf("HTTP/1.1 301 Moved Permanently","Location: /fresh","Cache-Control: public, max-age=3600") to "redirect body".toByteArray()
   "/fresh"->listOf("HTTP/1.1 200 OK","Cache-Control: public, max-age=3600","Vary: Accept-Encoding, User-Agent","Content-Encoding: gzip","ETag: \"original\"") to gzip
   "/chunked"->listOf("HTTP/1.1 200 OK","Cache-Control: public, max-age=3600","Content-Encoding: gzip","Transfer-Encoding: chunked") to chunked
   "/validate"->if(headers["if-none-match"]=="\"original\""){validated.incrementAndGet();listOf("HTTP/1.1 304 Not Modified","Cache-Control: public, max-age=3600","ETag: \"original\"") to ByteArray(0)}else listOf("HTTP/1.1 200 OK","Cache-Control: no-cache","ETag: \"original\"") to payload
   "/vary-star"->listOf("HTTP/1.1 200 OK","Cache-Control: public, max-age=3600","Vary: *") to payload
   else->listOf("HTTP/1.1 200 OK","Cache-Control: no-store") to payload
  }}.use{server->
   val dir=Files.createTempDirectory("http-publish-roundtrip").toFile()
   val metrics=HttpMetrics()
   var client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(dir,4_194_304)).eventListenerFactory{metrics.listener(it)}.addNetworkInterceptor{metrics.intercept(it)}.build();var backend=HttpTileBackend(client,metrics=metrics){null}
   fun network(key:ResourceKey):TileBody{val f=CompletableFuture<TileBody>();backend.network(key){it.fold(f::complete,f::completeExceptionally)};return f.get(2,TimeUnit.SECONDS)}
   val fresh=ResourceKey(server.url("/fresh"),false);val conditional=ResourceKey(server.url("/validate"),false);val redirect=ResourceKey(server.url("/redirect"),false);val noStore=ResourceKey(server.url("/no-store"),false)
   try{
    assertNull(backend.local(fresh));assertArrayEquals(payload,network(fresh).bytes);backend.awaitCacheWrites()
    val cacheCall=client.newCall(Request.Builder().url(fresh.url).header("User-Agent","Ridgeline-Native/0.1.0").build())
    cacheCall.execute().use{assertArrayEquals(payload,it.body.bytes());metrics.delivered(cacheCall)}
    assertEquals("cache",metrics.last()!!.protocol);assertEquals(0L,metrics.last()!!.bodyBytes);assertEquals("HTTP 200",metrics.last()!!.outcome)
    assertArrayEquals(payload,network(conditional).bytes);backend.awaitCacheWrites()
    assertNull(backend.local(conditional));assertArrayEquals(payload,network(conditional).bytes);assertEquals(1,validated.get())
    assertArrayEquals(payload,network(redirect).bytes);backend.awaitCacheWrites()
    assertArrayEquals(payload,network(noStore).bytes);backend.awaitCacheWrites();assertNull(backend.local(noStore))
    val varyStar=ResourceKey(server.url("/vary-star"),false);assertArrayEquals(payload,network(varyStar).bytes);assertNull(backend.local(varyStar))
    val chunk=ResourceKey(server.url("/chunked"),false);assertArrayEquals(payload,network(chunk).bytes);backend.awaitCacheWrites()
    val before=server.requests.get();close(client,backend)
    client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(dir,4_194_304)).build();backend=HttpTileBackend(client){null}
    assertArrayEquals(payload,backend.local(fresh)!!.bytes)
    assertArrayEquals(payload,backend.local(conditional)!!.bytes)
    assertArrayEquals(payload,backend.local(redirect)!!.bytes)
    assertArrayEquals(payload,backend.local(chunk)!!.bytes)
    assertEquals("Persistent cache hits/redirects must not perform HTTP",before,server.requests.get())
    assertTrue("Original automatic disk budget is retained",client.cache!!.maxSize()==4_194_304L)
   }finally{close(client,backend);dir.deleteRecursively()}
  }
 }
 @Test fun existingOkHttp4CacheEntriesRemainReadableWithoutNetwork(){
  val fixture=File(System.getProperty("ridgeline.fixtures"),"http-cache-v4")
  val metadata=org.json.JSONObject(File(fixture,"fixture.json").readText())
  val dir=Files.createTempDirectory("v4-to-v5-cache").toFile();File(fixture,"cache").copyRecursively(File(dir,"cache"))
  val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(File(dir,"cache"),1_048_576)).build();val backend=HttpTileBackend(client){null}
  try{
   val key=ResourceKey(metadata.getString("url"),false)
   assertArrayEquals(metadata.getString("body").toByteArray(),backend.local(key)!!.bytes)
   assertEquals(0L,backend.stats()["escapeRunning"])
  }finally{close(client,backend);dir.deleteRecursively()}
 }
 @Test fun fiftyDownloadBurstRemainsPersistedWithZeroTransportRamAndAHeldCacheWriter(){
  val payload=ByteArray(8192){(it*17).toByte()}
  Server{_,_->listOf("HTTP/1.1 200 OK","Cache-Control: public, max-age=3600") to payload}.use{server->
   val dir=Files.createTempDirectory("cache-burst").toFile();val cache=Cache(dir,8_388_608)
   val disk=cache.javaClass.getDeclaredField("cache").apply{isAccessible=true}.get(cache)!!
   val held=CountDownLatch(1);val release=CountDownLatch(1)
   val holder=Thread{synchronized(disk){held.countDown();release.await(5,TimeUnit.SECONDS)}}.apply{start()}
   val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(cache).build();val backend=HttpTileBackend(client){null}
   val keys=List(50){ResourceKey(server.url("/tile/$it"),false)}
   try{
    assertTrue(held.await(1,TimeUnit.SECONDS))
    val results=keys.map{key->CompletableFuture<TileBody>().also{f->backend.network(key){it.fold(f::complete,f::completeExceptionally)}}}
    results.forEach{assertArrayEquals(payload,it.get(3,TimeUnit.SECONDS).bytes)}
    assertEquals(1L,release.count);assertEquals(0L,backend.stats()["cacheWriteDrops"])
    assertArrayEquals(payload,backend.local(keys.first())!!.bytes)
    val reused=CompletableFuture<TileBody>();backend.network(keys.first()){it.fold(reused::complete,reused::completeExceptionally)}
    assertArrayEquals(payload,reused.get(1,TimeUnit.SECONDS).bytes);assertEquals(50,server.requests.get())
    release.countDown();backend.awaitCacheWrites();assertEquals(50L,backend.stats()["cacheWrites"])
    close(client,backend)
    val restarted=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(Cache(dir,8_388_608)).build();val stored=HttpTileBackend(restarted){null}
    try{keys.forEach{assertArrayEquals(payload,stored.local(it)!!.bytes)};assertEquals(50,server.requests.get())}finally{close(restarted,stored)}
   }finally{release.countDown();holder.join(1000);close(client,backend);dir.deleteRecursively()}
  }
 }
 @Test fun pendingPublicationUsesSdkFreshnessAndMustRevalidateRules(){
  val dir=Files.createTempDirectory("pending-policy").toFile();val cache=Cache(dir,1_048_576);val publisher=HttpCachePublisher(cache)
  val disk=cache.javaClass.getDeclaredField("cache").apply{isAccessible=true}.get(cache)!!
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  val holder=Thread{synchronized(disk){entered.countDown();release.await(3,TimeUnit.SECONDS)}}.apply{start()}
  try{
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   for((i,policy)in listOf("public, max-age=0","public, max-age=0, must-revalidate","no-cache","no-store").withIndex()){
    val request=Request.Builder().url("http://policy.fixture.invalid/$i").build()
    val now=System.currentTimeMillis()
    val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
     .header("Cache-Control",policy).sentRequestAtMillis(now-2000).receivedResponseAtMillis(now-1000).build()
    publisher.publish(response,TileBody(byteArrayOf(1),200,0))
    assertNull(publisher.lookup(ResourceKey(request.url.toString(),false)))
    if(i==0)assertNotNull(publisher.lookup(ResourceKey(request.url.toString(),true)))
    else assertNull("Offline max-stale cannot override $policy",publisher.lookup(ResourceKey(request.url.toString(),true)))
   }
  }finally{release.countDown();holder.join(1000);publisher.close();publisher.awaitClosed();cache.close();dir.deleteRecursively()}
 }
}
