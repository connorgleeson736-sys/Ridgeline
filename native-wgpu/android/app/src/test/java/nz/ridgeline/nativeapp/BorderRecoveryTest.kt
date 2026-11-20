package nz.ridgeline.nativeapp

import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class BorderRecoveryTest {
    @Test fun transientSurveyFailureRecoversAfterTheOldRetryCutoff() {
        val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val server=Executors.newSingleThreadExecutor()
        val transient=AtomicInteger();val permanent=AtomicInteger()
        val exact="original survey body".toByteArray()
        server.execute {
            try{while(!socket.isClosed)socket.accept().use { connection ->
                val reader=connection.getInputStream().bufferedReader()
                val request=reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
                val code=if(request.contains("/permanent")){permanent.incrementAndGet();404}
                    else if(transient.incrementAndGet()<=4)503 else 200
                val body=if(code==200)exact else "source unavailable".toByteArray()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $code Result\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body);flush()
                }
            }}catch(_:SocketException){}
        }
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val transport=TileTransport(HttpTileBackend(client){null},0)
        var modeledDelay=0L
        // Exercise the production HTTP/transport exceptions and retry policy.
        // Advance retry delays virtually; this is not a phone-timing benchmark.
        fun load(path:String):ByteArray? {
            for(attempt in 1..8){
                val consumer=transport.consumer(imagery=true)
                try {
                    val body=try{consumer.acquire(ResourceKey("http://127.0.0.1:${socket.localPort}/$path",false)).get(3,TimeUnit.SECONDS)}
                        catch(e:ExecutionException){throw(e.cause as Exception)}
                    if(body.status !in 200..299)throw TileHttpException(body.status)
                    return body.bytes
                }catch(e:Exception){
                    if(!TileRetryPolicy.shouldWake(attempt,e))return null
                    modeledDelay+=TileRetryPolicy.delayMillis(attempt)
                }finally{consumer.release(true)}
            }
            fail("Retries exceeded fixture limit");return null
        }
        try {
            assertArrayEquals(exact,load("survey"))
            assertEquals(5,transient.get());assertEquals(35000L,modeledDelay)
            assertNull(load("permanent"));assertEquals(4,permanent.get())
            println("Real HTTP: four transient survey failures recover on request five; permanent 404 stops after established retries. Delays advanced virtually.")
        }finally{
            transport.close();socket.close();server.shutdownNow()
            client.dispatcher.cancelAll();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()
        }
    }
}
