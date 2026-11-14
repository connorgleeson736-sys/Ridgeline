package nz.ridgeline.nativeapp
import okhttp3.*
import java.io.IOException

/** The local path is explicitly cache-only. It never waits on a network permit. */
internal class HttpTileBackend(private val client:OkHttpClient,
    private val escapeRead:(ResourceKey)->TileBody?={null},
    private val escapeWrite:(ResourceKey,TileBody)->Unit={_,_->},
    cacheThreadFactory:java.util.concurrent.ThreadFactory=java.util.concurrent.Executors.defaultThreadFactory(),
    private val metrics:HttpMetrics?=null,
    /** A request still without response headers after this long is repeated on
     * a fresh connection; the first answer wins. Healthy LINZ/Esri responses
     * start well within this, so it only acts on stalled connections. */
    private val hedgeDelayMillis:Long=2500,
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
    // HTTP/2 multiplexes every LINZ request onto one connection. When that
    // connection dies silently (Wi-Fi/mobile handover, tower change, NAT
    // timeout), OkHttp keeps assigning requests to it until each one reaches
    // the read timeout. Hedges use a separate pool, so they always open a new
    // connection; a network change replaces that pool and hedges at once.
    private fun freshClient()=direct.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher().apply{
        maxRequests=DownloadLimits.HTTP_CAPACITY;maxRequestsPerHost=DownloadLimits.HTTP_CAPACITY}).build()
    @Volatile private var fresh:OkHttpClient?=null
    /** Cache-free requests use this pool after a hedge proves the original
     * connection dead: on HTTP/2, new requests would otherwise keep landing on
     * that connection while its stalled streams keep it busy. */
    @Volatile private var proven:OkHttpClient?=null
    private fun hedgeClient():OkHttpClient=fresh?:synchronized(this){fresh?:freshClient().also{fresh=it}}
    @Volatile private var networkEpoch=0L
    private val races=java.util.concurrent.ConcurrentHashMap.newKeySet<Race>()
    private val hedgeTimers=java.util.concurrent.Executors.newSingleThreadScheduledExecutor{work->Thread(work,"RidgelineHttpHedge").apply{isDaemon=true}}
    private val hedgesStarted=java.util.concurrent.atomic.AtomicLong()
    private val hedgesWon=java.util.concurrent.atomic.AtomicLong()
    private val networkChanges=java.util.concurrent.atomic.AtomicLong()
    /** Android's default network changed: idle pooled connections belong to the
     * old network. Drop them, and immediately repeat every request that has not
     * received headers on a connection opened over the new network. */
    fun networkChanged(){
        networkChanges.incrementAndGet()
        synchronized(this){networkEpoch++;fresh?.connectionPool?.evictAll();fresh=null;proven?.connectionPool?.evictAll();proven=null}
        client.connectionPool.evictAll()
        for(race in races)race.hedge(force=true)
    }
    /** Open pooled connections (DNS, TCP, TLS) before tiles need them. HEAD
     * responses bypass every cache and are discarded; failures are ignored. */
    fun warm(origins:List<String>){
        for(origin in origins)runCatching{direct.newCall(Request.Builder().url(origin).head().header("User-Agent","Ridgeline-Native/0.1.0")
            .cacheControl(CacheControl.FORCE_NETWORK).build()).enqueue(object:Callback{
                // Warm-ups are not tile transfers: drop their spans, which were
                // never delivered and showed as an ever-older "oldest" call.
                override fun onFailure(call:Call,e:IOException){metrics?.forget(call)}
                override fun onResponse(call:Call,response:Response){response.close();metrics?.forget(call)}
            })}
    }
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit {
        check(!key.offline){"Offline network request rejected"}
        publisher.lookup(key)?.let{done(Result.success(it));return{}}
        val retired=client.dispatcher.runningCalls().any{it.isCanceled()}||client.dispatcher.queuedCalls().any{it.isCanceled()}
        val validate=stored.remove(key)==true
        // A genuine miss never re-enters the disk cache on its network thread.
        // Existing entries retain OkHttp's standard ETag/304/no-cache semantics.
        return if(retired||!validate)network(proven?:direct,key,done)else network(client,key,done)
    }
    fun stats():Map<String,Long> {
        val running=client.dispatcher.runningCalls();val queued=client.dispatcher.queuedCalls()
        return mapOf("httpRunning" to running.size.toLong(),"httpQueued" to queued.size.toLong(),
            "retiredHttpRunning" to running.count{it.isCanceled()}.toLong(),"retiredHttpQueued" to queued.count{it.isCanceled()}.toLong(),
            "escapeRunning" to if(directClient.isInitialized())direct.dispatcher.runningCallsCount().toLong()else 0L,
            "escapeQueued" to if(directClient.isInitialized())direct.dispatcher.queuedCallsCount().toLong()else 0L,
            "hedgesStarted" to hedgesStarted.get(),"hedgesWon" to hedgesWon.get(),"networkChanges" to networkChanges.get())+publisher.stats()
    }
    override fun close(){hedgeTimers.shutdownNow();for(race in races)race.cancel();for(extra in listOfNotNull(fresh,proven)){extra.dispatcher.cancelAll();extra.dispatcher.executorService.shutdown()}
        publisher.close();stored.clear();if(directClient.isInitialized()){
        direct.dispatcher.cancelAll();direct.dispatcher.executorService.shutdown()
    }}
    fun awaitCacheWrites()=publisher.awaitIdle()
    override fun awaitClosed()=publisher.awaitClosed()
    override fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit)=network(proven?:direct,key){result->
        // Enqueue only: a cache-free winner must not re-enter a stuck journal
        // before delivery, but should survive encoded-RAM eviction/restart.
        result.getOrNull()?.let{body->runCatching{escapeWrite(key,body)}}
        done(result)
    }
    private fun network(http:OkHttpClient,key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
        check(!key.offline){"Offline network request rejected"}
        publisher.lookup(key)?.let{done(Result.success(it));return{}}
        val race=Race(key,done);race.start(http);return{race.cancel()}
    }
    /** One logical request: the primary call plus at most one hedge per network
     * epoch. The first success is delivered; a failure is delivered only once no
     * attempt remains. [done] runs exactly once, outside any lock. */
    private inner class Race(val key:ResourceKey,val done:(Result<TileBody>)->Unit){
        private val calls=ArrayList<Call>()
        private var pending=0;private var finished=false;private var headers=false;private var cancelled=false
        private var hedgedEpoch=-1L;private var primaryCall:Call?=null;private var hedgePool:OkHttpClient?=null
        private var timer:java.util.concurrent.ScheduledFuture<*>?=null
        fun start(http:OkHttpClient){
            races.add(this)
            launch(http,primary=true)
            runCatching{timer=hedgeTimers.schedule({hedge(force=false)},hedgeDelayMillis,java.util.concurrent.TimeUnit.MILLISECONDS)}
        }
        fun hedge(force:Boolean){
            val epoch=networkEpoch
            synchronized(this){
                if(finished||cancelled||headers||hedgedEpoch==epoch||(!force&&hedgedEpoch>=0))return
                hedgedEpoch=epoch
            }
            hedgesStarted.incrementAndGet()
            launch(hedgeClient(),primary=false)
        }
        private fun launch(http:OkHttpClient,primary:Boolean){
            val call=http.newCall(Request.Builder().url(key.url).header("User-Agent","Ridgeline-Native/0.1.0").build())
            synchronized(this){if(finished||cancelled)return;calls.add(call);pending++;if(primary)primaryCall=call else hedgePool=http}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){metrics?.delivered(call,e);complete(call,Result.failure(e))}
                override fun onResponse(call:Call,response:Response){
                    synchronized(this@Race){headers=true}
                    response.use{
                        val result=runCatching{read(it,false)}
                        if(http.cache==null)result.getOrNull()?.let{body->publisher.publish(response,body,key.url)}
                        metrics?.delivered(call,result.exceptionOrNull())
                        complete(call,result)
                    }
                }
            })
        }
        private fun complete(call:Call,result:Result<TileBody>){
            val others:List<Call>;val evict:Boolean
            synchronized(this){
                pending--
                if(finished||result.isFailure&&pending>0)return
                finished=true;others=calls.filter{it!==call};evict=result.isSuccess&&call!==primaryCall
            }
            timer?.cancel(false);races.remove(this)
            for(other in others)other.cancel()
            if(evict){
                // The hedge beat a primary that had no headers: the primary's
                // connection is suspect. New requests move to the proven pool;
                // later hedges get a new pool of their own. Idle connections in
                // the old pools reconnect on next use.
                hedgesWon.incrementAndGet()
                synchronized(this@HttpTileBackend){
                    val winner=hedgePool
                    if(winner!=null&&fresh===winner){val old=proven;proven=winner;fresh=null;old?.connectionPool?.evictAll()}
                }
                client.connectionPool.evictAll()
            }
            done(result)
        }
        fun cancel(){
            val all:List<Call>;synchronized(this){cancelled=true;all=calls.toList()}
            timer?.cancel(false);for(call in all)call.cancel()
        }
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
