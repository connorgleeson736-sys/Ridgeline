package nz.ridgeline.nativeapp

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.*

/** Narrow adapter to the pinned OkHttp 5.3.2 cache. Store the real response's
 * metadata/body in the existing full-size cache, after delivery. No cache-format
 * fork or second smaller cache tier. Internal calls have cache/migration tests. */
internal class HttpCachePublisher(private val cache:Cache?,private val byteLimit:Long=8L*1024*1024,threadFactory:ThreadFactory=Executors.defaultThreadFactory()):AutoCloseable {
    private data class Pending(val key:String,val response:Response,val body:TileBody)
    private val lock=Any();private val pending=HashMap<String,Pending>()
    private var bytes=0L;private var writes=0L;private var dropped=0L;private var errors=0L
    private val workers=ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(512),threadFactory)
    fun lookup(key:ResourceKey):TileBody?=synchronized(lock){
        val row=pending[key.url]?:return@synchronized null
        if(row.body.status !in 200..299&&row.body.status!=404)return@synchronized null
        if(!OkHttpCacheAccess.usable(row.response,key.offline))return@synchronized null
        if(key.offline)row.body.copy(until=Long.MAX_VALUE)else row.body
    }
    /** Runs only on the bounded local-I/O workers. Retain standard OkHttp
     * conditional validation for existing stale/no-cache entries. */
    fun hasStored(request:Request):Boolean {
        val disk=cache?:return false
        // BridgeInterceptor adds these before CacheInterceptor. Preserve Vary
        // matching when probing its cache directly, without reading body bytes.
        val probe=request.newBuilder().removeHeader("Cache-Control").removeHeader("Pragma").header("Accept-Encoding","gzip")
            .header("Connection","Keep-Alive").header("Host",request.url.let{url->
                val host=if(':' in url.host)"[${url.host}]"else url.host
                host+if(url.port==HttpUrl.defaultPort(url.scheme))""else":${url.port}"
            }).build()
        return OkHttpCacheAccess.contains(disk,probe)
    }
    fun publish(response:Response,body:TileBody,key:String=response.request.url.toString(),encoded:Boolean=false){
        val disk=cache?:return
        if(!OkHttpCacheAccess.cacheable(response)||response.headers.values("Vary").any{row->row.split(',').any{it.trim()=="*"}})return
        // BridgeInterceptor may already have decompressed the body. Keep headers
        // consistent with the exact bytes stored, with original timing/ETag/Vary.
        val empty=ByteArray(0).toResponseBody(response.body.contentType())
        val builder=response.newBuilder().header("Content-Length",body.bytes.size.toString()).body(empty)
        if(!encoded)builder.removeHeader("Content-Encoding")
        if(response.networkResponse==null)builder.networkResponse(response.newBuilder().body(empty).build())
        val row=Pending(key,builder.build(),body)
        synchronized(lock){
            if(workers.isShutdown||bytes+body.bytes.size>byteLimit){dropped++;return}
            bytes+=body.bytes.size;pending[key]=row
        }
        try{workers.execute{
            try{
                if(OkHttpCacheAccess.write(disk,row.response,body.bytes))synchronized(lock){writes++}
            }catch(_:Exception){synchronized(lock){errors++}}
            finally{retire(row)}
        }}catch(_:RejectedExecutionException){synchronized(lock){dropped++};retire(row)}
    }
    /** The SDK follows redirects. Capture cacheable redirect bodies as it closes
     * them, with the same 100ms drain bound as OkHttp's cache-writing source. */
    fun redirect(response:Response):Response {
        if(cache==null||response.code !in setOf(301,302,303,307,308)||!OkHttpCacheAccess.cacheable(response))return response
        val original=response.body;val captured=Buffer();var complete=false;var oversized=false
        val tee=object:ForwardingSource(original.source()){
            override fun read(sink:Buffer,byteCount:Long):Long {
                val n=super.read(sink,byteCount)
                if(n>0&&!oversized){if(captured.size+n<=byteLimit)sink.copyTo(captured,sink.size-n,n)else{oversized=true;captured.clear()}}
                if(n==-1L&&!complete){complete=true;if(!oversized)publish(response,TileBody(captured.readByteArray(),response.code,HttpFreshness.deadline(response)),encoded=true)}
                return n
            }
            override fun close(){
                if(!complete){
                    val clock=timeout();val hadDeadline=clock.hasDeadline();val previous=if(hadDeadline)clock.deadlineNanoTime()else Long.MAX_VALUE
                    clock.deadlineNanoTime(minOf(previous,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100)))
                    try{val discarded=Buffer();while(read(discarded,8192)!=-1L)discarded.clear()}
                    catch(_:IOException){} // Incomplete redirect bodies are not cached.
                    finally{if(hadDeadline)clock.deadlineNanoTime(previous)else clock.clearDeadline()}
                }
                super.close()
            }
        }.buffer()
        return response.newBuilder().body(object:ResponseBody(){override fun contentType()=original.contentType();override fun contentLength()=original.contentLength();override fun source()=tee}).build()
    }
    private fun retire(row:Pending)=synchronized(lock){pending.remove(row.key,row);bytes-=row.body.bytes.size}
    fun stats():Map<String,Long> = synchronized(lock){mapOf("pendingCacheBytes" to bytes,"cacheWrites" to writes,"cacheWriteDrops" to dropped,"cacheWriteErrors" to errors,"cacheWritesQueued" to workers.queue.size.toLong())}
    fun awaitIdle(){if(!workers.isShutdown)workers.submit{}.get(5,TimeUnit.SECONDS)}
    override fun close(){workers.shutdown()}
    fun awaitClosed(){workers.awaitTermination(5,TimeUnit.SECONDS)}
}
