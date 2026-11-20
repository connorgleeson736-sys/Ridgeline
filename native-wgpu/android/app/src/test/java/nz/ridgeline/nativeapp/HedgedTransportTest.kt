package nz.ridgeline.nativeapp

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real sockets: a keep-alive HTTP/1.1 server behind a TCP proxy that can
 * silently black-hole existing connections (no FIN/RST), as a Wi-Fi/mobile
 * handover or NAT timeout does, while new connections keep working. */
class HedgedTransportTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    private val closers=CopyOnWriteArrayList<AutoCloseable>()
    private val requests=AtomicInteger()

    private fun daemon(block:()->Unit)=Thread{runCatching(block)}.apply{isDaemon=true;start()}
    private fun server():Int{
        val socket=ServerSocket(0,50,loopback);closers.add(socket)
        daemon{while(true){val c=socket.accept();closers.add(c);daemon{serve(c)}}}
        return socket.localPort
    }
    private fun serve(c:Socket){
        val input=c.getInputStream().bufferedReader();val output=c.getOutputStream()
        while(true){
            val line=input.readLine()?:return
            while(!input.readLine().isNullOrEmpty()){}
            requests.incrementAndGet()
            val path=line.split(' ')[1]
            if(path.startsWith("/fail")){output.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray());output.flush();continue}
            val body="tile $path".toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nCache-Control: max-age=60\r\n\r\n".toByteArray());output.write(body);output.flush()
        }
    }
    private inner class Proxy(upstream:Int){
        private val socket=ServerSocket(0,50,loopback).also{closers.add(it)}
        private val pipes=CopyOnWriteArrayList<AtomicBoolean>()
        val port=socket.localPort
        init{daemon{while(true){
            val client=socket.accept();val server=Socket(loopback,upstream);closers.add(client);closers.add(server)
            val alive=AtomicBoolean(true);pipes.add(alive)
            daemon{pump(client.getInputStream(),server.getOutputStream(),alive)}
            daemon{pump(server.getInputStream(),client.getOutputStream(),alive)}
        }}}
        private fun pump(from:InputStream,to:OutputStream,alive:AtomicBoolean){
            val buffer=ByteArray(8192)
            while(true){val n=from.read(buffer);if(n<0)return;if(alive.get()){to.write(buffer,0,n);to.flush()}}
        }
        /** Existing connections stop delivering bytes but stay open. */
        fun blackholeExisting(){for(p in pipes)p.set(false)}
    }
    private fun backend(hedge:Long,readTimeoutSeconds:Long=10):HttpTileBackend{
        val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).readTimeout(readTimeoutSeconds,TimeUnit.SECONDS).build()
        return HttpTileBackend(client,hedgeDelayMillis=hedge,offlineRead={null}).also{closers.add(it)}
    }
    private fun get(backend:HttpTileBackend,url:String):CompletableFuture<Result<TileBody>>{
        val f=CompletableFuture<Result<TileBody>>();backend.network(ResourceKey(url,false)){f.complete(it)};return f
    }
    @After fun close(){for(c in closers.reversed())runCatching{c.close()}}

    @Test fun aStalledPooledConnectionIsBypassedByAHedgeOnAFreshConnection(){
        val proxy=Proxy(server());val backend=backend(hedge=300)
        val base="http://127.0.0.1:${proxy.port}"
        assertEquals("tile /a",String(get(backend,"$base/a").get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        proxy.blackholeExisting()
        val started=System.nanoTime()
        val body=get(backend,"$base/b").get(5,TimeUnit.SECONDS).getOrThrow()
        val ms=(System.nanoTime()-started)/1_000_000
        assertEquals("tile /b",String(body.bytes))
        assertTrue("recovered in $ms ms",ms<2000)
        val stats=backend.stats();assertEquals(1L,stats["hedgesStarted"]);assertEquals(1L,stats["hedgesWon"])
        // The suspect pool was evicted: the next request uses a working connection.
        val next=System.nanoTime()
        assertEquals("tile /c",String(get(backend,"$base/c").get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        assertTrue((System.nanoTime()-next)/1_000_000<1000)
    }
    @Test fun withoutHedgingTheSameStallLastsTheWholeReadTimeout(){
        val proxy=Proxy(server());val backend=backend(hedge=Long.MAX_VALUE/4,readTimeoutSeconds=2)
        val base="http://127.0.0.1:${proxy.port}"
        get(backend,"$base/a").get(5,TimeUnit.SECONDS).getOrThrow()
        proxy.blackholeExisting()
        val started=System.nanoTime()
        val result=get(backend,"$base/b").get(10,TimeUnit.SECONDS)
        val ms=(System.nanoTime()-started)/1_000_000
        assertTrue(result.isFailure);assertTrue("stalled $ms ms",ms>=1900)
    }
    @Test fun healthyRequestsAreNeverDuplicated(){
        val port=server();val backend=backend(hedge=300)
        repeat(20){assertTrue(get(backend,"http://127.0.0.1:$port/t$it").get(5,TimeUnit.SECONDS).isSuccess)}
        assertEquals(0L,backend.stats()["hedgesStarted"]);assertEquals(20,requests.get())
    }
    @Test fun aProviderErrorIsReportedPromptlyWithoutAHedge(){
        val port=server();val backend=backend(hedge=300)
        val result=get(backend,"http://127.0.0.1:$port/fail").get(5,TimeUnit.SECONDS)
        assertEquals(503,(result.exceptionOrNull() as TileHttpException).code)
        Thread.sleep(500)
        assertEquals(0L,backend.stats()["hedgesStarted"]);assertEquals(1,requests.get())
    }
    @Test fun aNetworkChangeRepeatsStalledRequestsImmediately(){
        val proxy=Proxy(server());val backend=backend(hedge=60_000)
        val base="http://127.0.0.1:${proxy.port}"
        get(backend,"$base/a").get(5,TimeUnit.SECONDS).getOrThrow()
        proxy.blackholeExisting()
        val started=System.nanoTime()
        val pending=get(backend,"$base/b")
        Thread.sleep(150);assertFalse(pending.isDone)
        backend.networkChanged()
        assertEquals("tile /b",String(pending.get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        assertTrue((System.nanoTime()-started)/1_000_000<2000)
        assertEquals(1L,backend.stats()["networkChanges"])
    }
    @Test fun cancellingARequestNeverStartsAHedge(){
        val proxy=Proxy(server());val backend=backend(hedge=200)
        val base="http://127.0.0.1:${proxy.port}"
        get(backend,"$base/a").get(5,TimeUnit.SECONDS).getOrThrow()
        proxy.blackholeExisting()
        val f=CompletableFuture<Result<TileBody>>()
        val cancel=backend.network(ResourceKey("$base/b",false)){f.complete(it)}
        cancel()
        assertTrue(f.get(5,TimeUnit.SECONDS).isFailure)
        Thread.sleep(400)
        assertEquals(0L,backend.stats()["hedgesStarted"])
    }
    /** HTTP/2: every request shares one connection. While another stalled
     * stream keeps the dead connection busy, a new request must go straight to
     * the proven pool instead of waiting for its own hedge. */
    @Test fun afterAHedgeWinsNewHttp2RequestsAvoidTheDeadConnection(){
        val server=mockwebserver3.MockWebServer();closers.add(server)
        server.protocols=listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)
        server.dispatcher=object:mockwebserver3.Dispatcher(){
            override fun dispatch(request:mockwebserver3.RecordedRequest)=mockwebserver3.MockResponse.Builder().code(200).body("tile ${request.url.encodedPath}").build()
        }
        server.start(loopback,0)
        val proxy=Proxy(server.port)
        val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).protocols(listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)).readTimeout(10,TimeUnit.SECONDS).build()
        val backend=HttpTileBackend(client,hedgeDelayMillis=300,offlineRead={null}).also{closers.add(it)}
        val base="http://127.0.0.1:${proxy.port}"
        get(backend,"$base/a").get(5,TimeUnit.SECONDS).getOrThrow()
        proxy.blackholeExisting()
        val first=get(backend,"$base/b1")
        Thread.sleep(150)
        val second=get(backend,"$base/b2") // also stalls on the dead connection
        assertEquals("tile /b1",String(first.get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        assertFalse("b2 still stalled, keeping the dead connection busy",second.isDone)
        val started=System.nanoTime()
        assertEquals("tile /c",String(get(backend,"$base/c").get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        val ms=(System.nanoTime()-started)/1_000_000
        assertEquals("tile /b2",String(second.get(5,TimeUnit.SECONDS).getOrThrow().bytes))
        assertTrue("new request served without waiting for a hedge ($ms ms)",ms<250)
        assertEquals("only the two stalled requests hedged",2L,backend.stats()["hedgesStarted"])
    }
}
