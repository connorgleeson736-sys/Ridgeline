package nz.ridgeline.nativeapp
import okhttp3.*
import java.io.IOException

/** The local path is explicitly cache-only. It never waits on a network permit. */
internal class HttpTileBackend(private val client:OkHttpClient,
    private val escapeRead:(ResourceKey)->TileBody?={null},
    private val escapeWrite:(ResourceKey,TileBody)->Unit={_,_->},
    private val offlineRead:(String)->ByteArray?):TileBackend {
    override fun local(key:ResourceKey):TileBody?{
        offlineRead(key.url)?.let{return TileBody(it,200,Long.MAX_VALUE)}
        escapeRead(key)?.let{return it}
        val policy=if(key.offline)CacheControl.FORCE_CACHE else CacheControl.Builder().onlyIfCached().build()
        client.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").cacheControl(policy).build()).execute().use{response->
            if(response.code==504)return null
            return read(response,key.offline)
        }
    }
    // FORCE_NETWORK still runs CacheInterceptor.get() in OkHttp 4.12. A
    // cache-free sibling is required to escape a blocked disk journal/lookup.
    // A canceled call is still a Dispatcher running/queued call until its
    // callback exits. In particular, cancellation cannot interrupt a cache
    // monitor acquisition. Sharing that dispatcher made the escape path wait
    // behind canceled off-screen calls even after transport permits were free.
    // Isolate this bounded queue; retain connection pool, proxy and TLS settings.
    private val directClient=lazy{client.newBuilder().cache(null).dispatcher(Dispatcher().apply{
        maxRequests=DownloadLimits.HTTP_CAPACITY;maxRequestsPerHost=DownloadLimits.HTTP_CAPACITY
    }).build()}
    private val direct by directClient
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit {
        val retired=client.dispatcher.runningCalls().any{it.isCanceled()}||client.dispatcher.queuedCalls().any{it.isCanceled()}
        return if(retired)uncachedNetwork(key,done)else network(client,key,done)
    }
    fun stats():Map<String,Long> {
        val running=client.dispatcher.runningCalls();val queued=client.dispatcher.queuedCalls()
        return mapOf("httpRunning" to running.size.toLong(),"httpQueued" to queued.size.toLong(),
            "retiredHttpRunning" to running.count{it.isCanceled()}.toLong(),"retiredHttpQueued" to queued.count{it.isCanceled()}.toLong(),
            "escapeRunning" to if(directClient.isInitialized())direct.dispatcher.runningCallsCount().toLong()else 0L,
            "escapeQueued" to if(directClient.isInitialized())direct.dispatcher.queuedCallsCount().toLong()else 0L)
    }
    override fun close(){if(directClient.isInitialized()){
        direct.dispatcher.cancelAll();direct.dispatcher.executorService.shutdown()
    }}
    override fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit)=network(direct,key){result->
        // Enqueue only: a cache-free winner must not re-enter a stuck journal
        // before delivery, but should survive encoded-RAM eviction/restart.
        result.getOrNull()?.let{body->runCatching{escapeWrite(key,body)}}
        done(result)
    }
    private fun network(http:OkHttpClient,key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
        check(!key.offline){"Offline network request rejected"}
        val call=http.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").build())
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){done(Result.failure(e))}
            override fun onResponse(call:Call,response:Response){response.use{done(runCatching{read(it,false)})}}
        });return{call.cancel()}
    }
    private fun read(response:Response,cacheOnly:Boolean):TileBody{
        if(!response.isSuccessful&&response.code!=404)throw TileHttpException(response.code)
        val body=response.body?:throw IOException("Empty tile");require(body.contentLength()<=8*1024*1024)
        val source=body.source();val sink=okio.Buffer()
        while(true){val remaining=8L*1024*1024+1-sink.size;check(remaining>0){"Oversized tile"};if(source.read(sink,minOf(8192L,remaining))==-1L)break}
        require(sink.size<=8*1024*1024)
        return TileBody(sink.readByteArray(),response.code,if(cacheOnly)Long.MAX_VALUE else HttpFreshness.deadline(response))
    }
}
