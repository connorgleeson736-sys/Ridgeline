package nz.ridgeline.nativeapp
import okhttp3.*
import java.io.IOException

/** The local path is explicitly cache-only. It never waits on a network permit. */
internal class HttpTileBackend(private val client:OkHttpClient,
    private val escapeRead:(ResourceKey)->TileBody?={null},
    private val escapeWrite:(ResourceKey,TileBody)->Unit={_,_->},
    cacheThreadFactory:java.util.concurrent.ThreadFactory=java.util.concurrent.Executors.defaultThreadFactory(),
    private val metrics:HttpMetrics?=null,
    private val offlineRead:(String)->ByteArray?):TileBackend {
    private val publisher=HttpCachePublisher(client.cache,threadFactory=cacheThreadFactory)
    private val stored=java.util.concurrent.ConcurrentHashMap<ResourceKey,Boolean>()
    override fun local(key:ResourceKey):TileBody?{
        offlineRead(key.url)?.let{return TileBody(it,200,Long.MAX_VALUE)}
        publisher.lookup(key)?.let{return it}
        escapeRead(key)?.let{return it}
        val policy=if(key.offline)CacheControl.FORCE_CACHE else CacheControl.Builder().onlyIfCached().build()
        val request=Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").cacheControl(policy).build()
        client.newCall(request).execute().use{response->
            if(response.code==504){
                if(!key.offline&&publisher.hasStored(request)){
                    if(stored.size>=512)stored.clear()
                    stored[key]=true
                }
                return null
            }
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
    }).apply{networkInterceptors().add(0,Interceptor{chain->publisher.redirect(chain.proceed(chain.request()))})}.build()}
    private val direct by directClient
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit {
        check(!key.offline){"Offline network request rejected"}
        publisher.lookup(key)?.let{done(Result.success(it));return{}}
        val retired=client.dispatcher.runningCalls().any{it.isCanceled()}||client.dispatcher.queuedCalls().any{it.isCanceled()}
        val validate=stored.remove(key)==true
        // A genuine miss never re-enters the disk cache on its network thread.
        // Existing entries retain OkHttp's standard ETag/304/no-cache semantics.
        return if(retired||!validate)network(direct,key,done)else network(client,key,done)
    }
    fun stats():Map<String,Long> {
        val running=client.dispatcher.runningCalls();val queued=client.dispatcher.queuedCalls()
        return mapOf("httpRunning" to running.size.toLong(),"httpQueued" to queued.size.toLong(),
            "retiredHttpRunning" to running.count{it.isCanceled()}.toLong(),"retiredHttpQueued" to queued.count{it.isCanceled()}.toLong(),
            "escapeRunning" to if(directClient.isInitialized())direct.dispatcher.runningCallsCount().toLong()else 0L,
            "escapeQueued" to if(directClient.isInitialized())direct.dispatcher.queuedCallsCount().toLong()else 0L)+publisher.stats()
    }
    override fun close(){publisher.close();stored.clear();if(directClient.isInitialized()){
        direct.dispatcher.cancelAll();direct.dispatcher.executorService.shutdown()
    }}
    fun awaitCacheWrites()=publisher.awaitIdle()
    override fun awaitClosed()=publisher.awaitClosed()
    override fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit)=network(direct,key){result->
        // Enqueue only: a cache-free winner must not re-enter a stuck journal
        // before delivery, but should survive encoded-RAM eviction/restart.
        result.getOrNull()?.let{body->runCatching{escapeWrite(key,body)}}
        done(result)
    }
    private fun network(http:OkHttpClient,key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
        check(!key.offline){"Offline network request rejected"}
        publisher.lookup(key)?.let{done(Result.success(it));return{}}
        val call=http.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").build())
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){metrics?.delivered(call,e);done(Result.failure(e))}
            override fun onResponse(call:Call,response:Response){response.use{
                val result=runCatching{read(it,false)}
                if(http.cache==null)result.getOrNull()?.let{body->publisher.publish(response,body,key.url)}
                metrics?.delivered(call,result.exceptionOrNull())
                done(result)
            }}
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
