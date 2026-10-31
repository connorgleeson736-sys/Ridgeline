package nz.ridgeline.nativeapp

import java.io.IOException
import java.util.concurrent.*

/** Source lifetimes are absolute. Hash new bodies once, never on a fresh reuse. */
internal data class SourceStamp(val key:String,val fingerprint:String,val until:Long)
internal data class TileBody(val bytes:ByteArray,val status:Int,val until:Long,val stamp:SourceStamp?=null)
internal data class ResourceKey(val url:String,val offline:Boolean){val id:String by lazy{PreparedTerrainCache.digest((if(offline)"offline:" else "online:").plus(url).toByteArray())}}
internal interface TileBackend:AutoCloseable {
    override fun close(){}
    /** Local storage only; a miss MUST NOT initiate HTTP. */
    fun local(key:ResourceKey):TileBody?
    fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit
    /** Must not wait on the local cache being raced. Backends without a disk
     * cache can share their ordinary asynchronous network implementation. */
    fun uncachedNetwork(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=network(key,done)
}
internal class ResourcePending(val ready:CompletableFuture<*>):RuntimeException(null,null,false,false)

/** Local lookups have their own executor, independent of admitted network work.
 * CPU consumers suspend by continuation instead of occupying a decode worker. */
internal class TileTransport(private val backend:TileBackend,ramLimit:Long,
    private val clock:()->Long=System::currentTimeMillis,private val graceMs:Long=600,
    private val localWaitMs:Long=40,private val localCooldownMs:Long=30000):AutoCloseable {
    private val lock=Any()
    private var ramLimit=ramLimit.coerceAtLeast(0)
    fun setRamLimit(limit:Long){synchronized(lock){
        ramLimit=limit.coerceAtLeast(0)
        while(bytes>ramLimit&&ram.isNotEmpty())bytes-=ram.remove(ram.keys.first())!!.bytes.size
    }}
    private val local=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private var nextOrder:Long=0L
    private val timers=Executors.newSingleThreadScheduledExecutor()
    private val jobs=LinkedHashMap<ResourceKey,Job>()
    private val ram=LinkedHashMap<ResourceKey,TileBody>(16,.75f,true)
    private val metadata=LinkedHashMap<String,SourceStamp>(16,.75f,true)
    private var nextExpiry=Long.MAX_VALUE
    private fun prune(){val now=clock();if(now<nextExpiry)return;nextExpiry=Long.MAX_VALUE;val it=ram.entries.iterator();while(it.hasNext()){val row=it.next();if(row.value.until<=now){bytes-=row.value.bytes.size;it.remove()}else nextExpiry=minOf(nextExpiry,row.value.until)}}
    private var bytes=0L;private var networkActive=0;private var backgroundActive=0
    private var foregroundLimit=12;private var closed=false;private var demandBatch=0
    private var imageryPending=false
    private val localEpoch=System.nanoTime()
    private fun localNow()=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-localEpoch)
    private var localWatch:ScheduledFuture<*>?=null
    private var localWatchAt=Long.MAX_VALUE;private var localWatchEpoch=0L;private var localWatchRuns=0L
    private var localCooldownUntil=0L;private var localTimeouts=0L;private var localErrors=0L;private var bypasses=0L
    private var ramHits=0L;private var localHits=0L;private var networkStarts=0L;private var shares=0L;private var cancellations=0L
    private var lastNetworkMs=0L;private var maxNetworkMs=0L;private var lastAdmissionMs=0L
    private enum class Phase { LOCAL,QUEUED,NETWORK,DONE }
    private inner class Job(val key:ResourceKey){
        val result=CompletableFuture<TileBody>();val users=LinkedHashSet<Consumer>()
        var phase=Phase.LOCAL;var stopped=false;var background=false;var cancel:(()->Unit)?=null
        val order:Long=nextOrder++
        val createdNs=System.nanoTime();var networkNs=0L
        @Volatile var localRank=Int.MAX_VALUE
        var localWork:LocalWork?=null
        var localFinished=false;var localHit=false;var bypassCache=false;var networkTried=false
        var networkError:Throwable?=null
        var localDeadline=Long.MAX_VALUE
        var expiry:ScheduledFuture<*>?=null
        fun speculative()=users.isNotEmpty()&&users.all{it.speculative}
        fun imagery()=users.any{it.imagery&&!it.speculative}
        fun rank()=users.minOfOrNull{it.priority+(if(it.speculative)10000 else 0)}?:Int.MAX_VALUE
    }
    private inner class LocalWork(val job:Job):Runnable,Comparable<LocalWork>{
        override fun compareTo(other:LocalWork):Int {val rank=job.localRank.compareTo(other.job.localRank);return if(rank!=0)rank else job.order.compareTo(other.job.order)}
        override fun run(){try{
            if(synchronized(lock){job.stopped||job.phase==Phase.DONE})return
            localDone(job,Result.success(backend.local(job.key)))
        }catch(e:Exception){localDone(job,Result.failure(e))}}
    }
    /** A foreground request gets a bounded disk-first opportunity. Online
     * progress then uses an independent cache-free HTTP path, within the same
     * network limits. Offline/speculative readers never start a hedge. */
    private fun watchable(job:Job)=!job.stopped&&job.phase==Phase.LOCAL&&!job.key.offline&&
        !job.networkTried&&!job.localFinished&&job.localWork!=null&&job.users.any{!it.speculative}
    private fun scheduleLocalWatch(at:Long){
        if(localWatch!=null&&localWatchAt<=at)return
        localWatch?.cancel(false);localWatchAt=at
        val epoch=++localWatchEpoch
        localWatch=timers.schedule({pollLocal(epoch)},(at-localNow()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
    }
    private fun watchLocal(job:Job){
        if(closed||!watchable(job))return
        val now=localNow()
        job.localDeadline=minOf(job.localDeadline,now+if(now<localCooldownUntil)0 else localWaitMs)
        scheduleLocalWatch(job.localDeadline)
    }
    private fun pollLocal(epoch:Long){
        val escaped=synchronized(lock){
            if(closed||epoch!=localWatchEpoch)return
            localWatch=null;localWatchAt=Long.MAX_VALUE;localWatchRuns++
            val now=localNow();var next=Long.MAX_VALUE;var escape=now<localCooldownUntil
            for(job in jobs.values)if(watchable(job)){
                next=minOf(next,job.localDeadline)
                if(job.localDeadline<=now)escape=true
            }
            if(escape){
                var changed=false
                for(job in jobs.values)if(watchable(job)){
                    localTimeouts++;job.bypassCache=true;job.phase=Phase.QUEUED;changed=true
                }
                if(changed)localCooldownUntil=now+localCooldownMs
                changed
            }else{if(next!=Long.MAX_VALUE)scheduleLocalWatch(next);false}
        }
        if(escaped)pump()
    }
    private fun localDone(job:Job,result:Result<TileBody?>){
        var completed:Result<TileBody>?=null
        synchronized(lock){
            // Progress reopens the disk path even if this particular reader
            // lost the race. A late body never replaces the selected response.
            if(result.isSuccess)localCooldownUntil=0
            if(job.stopped||job.phase==Phase.DONE)return
            job.localFinished=true;job.localDeadline=Long.MAX_VALUE
            val body=result.getOrNull()
            if(body!=null){job.localHit=true;localHits++;completed=Result.success(body)}
            else if(job.key.offline)completed=Result.failure(result.exceptionOrNull()?:IOException("Offline tile unavailable"))
            else if(job.networkError!=null)completed=Result.failure(job.networkError!!)
            else if(job.phase==Phase.LOCAL){
                if(result.isFailure){localErrors++;localCooldownUntil=localNow()+localCooldownMs;job.bypassCache=true}
                job.phase=Phase.QUEUED
            }
        }
        completed?.let{finish(job,it)}?:pump()
    }
    private fun networkDone(job:Job,result:Result<TileBody>){
        val waitForLocal=synchronized(lock){
            if(job.stopped||job.phase!=Phase.NETWORK)return
            lastNetworkMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-job.networkNs);maxNetworkMs=maxOf(maxNetworkMs,lastNetworkMs)
            if(result.isFailure&&job.localHit)return
            if(result.isFailure&&!job.localFinished){
                // A failed hedge must not suppress a possible saved/disk hit.
                networkActive--;if(job.background)backgroundActive--
                job.phase=Phase.LOCAL;job.networkError=result.exceptionOrNull();true
            }else false
        }
        if(waitForLocal)pump()else finish(job,result,true)
    }
    // Caller owns lock. Queue comparators read only volatile scalar ranks, never
    // acquire lock while the priority queue itself is locked.
    private fun reprioritize(job:Job){
        job.localRank=job.rank()
        if(!closed&&!job.stopped&&job.phase==Phase.LOCAL)job.localWork?.let{work->if(local.remove(work))local.execute(work)}
        if(job.phase==Phase.NETWORK){
            val background=job.users.isEmpty()||job.speculative()
            if(background!=job.background){backgroundActive+=if(background)1 else -1;job.background=background}
        }
        watchLocal(job)
    }
    inner class Consumer internal constructor(internal var priority:Int,internal var speculative:Boolean,internal val imagery:Boolean):AutoCloseable {
        private val owned=LinkedHashMap<ResourceKey,Job>()
        private val immediate=LinkedHashMap<ResourceKey,CompletableFuture<TileBody>>()
        private var released=false
        fun promote(priority:Int,speculative:Boolean=false){synchronized(lock){
            if(released||this.priority==priority&&this.speculative==speculative)return
            this.priority=priority;this.speculative=speculative
            for(job in owned.values)reprioritize(job)
        };pump()}
        /** Preserve a returning request only if its whole dependency fan-out
         * fits the two background slots. One DEM job can own several transfers. */
        fun park():Boolean {
            synchronized(lock){
                if(released||closed)return false
                val extra=owned.values.count{job->job.phase==Phase.NETWORK&&!job.background&&
                    job.users.none{it!==this&&!it.speculative}}
                if(backgroundActive+extra>2)return false
                priority=1000;speculative=true
                for(job in owned.values)reprioritize(job)
            }
            pump();return true
        }
        fun acquire(key:ResourceKey):CompletableFuture<TileBody>{
            var start:Job?=null
            var forward:Pair<Job,CompletableFuture<TileBody>>?=null
            val future=synchronized(lock){
                check(!released&&!closed){"Tile consumer closed"}
                immediate[key]?.let{return@synchronized it}
                prune();val cached=ram[key]
                if(cached!=null&&cached.until>clock()){
                    ramHits++;return@synchronized CompletableFuture.completedFuture(cached).also{immediate[key]=it}
                }
                if(cached!=null){ram.remove(key);bytes-=cached.bytes.size}
                var job=jobs[key]
                if(job==null){require(jobs.size<512){"Tile request guard reached"};job=Job(key);jobs[key]=job;start=job}else shares++
                job.expiry?.cancel(false);job.expiry=null;job.users.add(this);owned[key]=job
                reprioritize(job)
                CompletableFuture<TileBody>().also{immediate[key]=it;forward=job to it}
            }
            forward?.let{(job,view)->job.result.whenComplete{body,error->if(error!=null)view.completeExceptionally(error)else view.complete(body)}}
            start?.let{job->synchronized(lock){if(!job.stopped&&!closed&&job.phase==Phase.LOCAL&&!job.networkTried){
                if(!job.key.offline&&!job.speculative()&&localNow()<localCooldownUntil){
                    job.bypassCache=true;job.localFinished=true;job.phase=Phase.QUEUED
                    job.localDeadline=Long.MAX_VALUE
                }else{
                    val work=LocalWork(job);job.localWork=work;job.localRank=job.rank();local.execute(work)
                    watchLocal(job)
                }
            }}}
            if(forward!=null)pump();return future
        }
        override fun close()=release(false)
        fun release(force:Boolean){
            val cancelledViews=ArrayList<CompletableFuture<TileBody>>()
            val abandoned=ArrayList<Job>()
            synchronized(lock){
                if(released)return;released=true
                val out=owned.values.toList();owned.clear();cancelledViews.addAll(immediate.values);immediate.clear()
                for(job in out){job.users.remove(this);reprioritize(job)
                    if(job.users.isEmpty()&&job.phase!=Phase.DONE&&!job.stopped){
                        if(speculative||force)abandoned+=job
                        else job.expiry=timers.schedule({abandon(job)},graceMs,TimeUnit.MILLISECONDS)
                    }
                }
            }
            for(job in abandoned)abandon(job)
            // Cancel this reader immediately; other consumers keep the shared job.
            for(view in cancelledViews)view.completeExceptionally(PreparationAborted())
            pump()
        }

    }
    fun consumer(priority:Int=0,speculative:Boolean=false,imagery:Boolean=false)=Consumer(priority,speculative,imagery)
    /** Publish before CPU work can fan out DEM dependencies. Keep two existing
     * foreground slots available for sharper images until visible demand is
     * satisfied; no extra connections or cancellation of shared terrain work. */
    fun imageryDemand(pending:Boolean){synchronized(lock){imageryPending=pending};pump()}
    fun movement(moving:Boolean){synchronized(lock){val next=if(moving)8 else 12;if(next==foregroundLimit)return;foregroundLimit=next};pump()}
    private fun abandon(job:Job){
        val cancel=synchronized(lock){
            if(job.users.isNotEmpty()||job.phase==Phase.DONE||job.stopped)return
            job.stopped=true;job.expiry?.cancel(false);job.expiry=null;job.localWork?.let{local.remove(it)};jobs.remove(job.key,job);cancellations++
            if(job.phase==Phase.NETWORK){networkActive--;if(job.background)backgroundActive--}
            job.phase=Phase.DONE;job.cancel
        }
        cancel?.invoke();job.result.completeExceptionally(PreparationAborted());pump()
    }
    /** Nonblocking ownership changes only. Publish all removals/promotions
     * before filling freed slots, so a pan cannot start jobs being canceled
     * moments later in the same demand update. Nested batches are supported. */
    fun batchDemand(change:()->Unit){
        synchronized(lock){demandBatch++}
        try{change()}finally{synchronized(lock){demandBatch--};pump()}
    }
    private fun pump(){
        val starts=ArrayList<Job>()
        synchronized(lock){
            if(closed||demandBatch>0)return
            while(true){
                if(networkActive>=foregroundLimit+2)break
                var best:Job?=null;var bestRank=Int.MAX_VALUE
                val reserve=imageryPending||jobs.values.any{it.phase!=Phase.DONE&&it.imagery()}
                val nonImageActive=if(reserve)jobs.values.count{it.phase==Phase.NETWORK&&!it.background&&!it.imagery()}else 0
                // One scan, no temporary filtered lists per admitted transfer.
                for(candidate in jobs.values){
                    if(candidate.phase!=Phase.QUEUED||candidate.stopped||candidate.users.isEmpty())continue
                    if(if(candidate.speculative())backgroundActive>=2 else networkActive-backgroundActive>=foregroundLimit)continue
                    if(reserve&&!candidate.speculative()&&!candidate.imagery()&&nonImageActive>=foregroundLimit-2)continue
                    val rank=candidate.rank()
                    if(best==null||rank<bestRank){best=candidate;bestRank=rank}
                }
                val job=best?:break
                job.networkNs=System.nanoTime();lastAdmissionMs=TimeUnit.NANOSECONDS.toMillis(job.networkNs-job.createdNs)
                job.phase=Phase.NETWORK;job.networkTried=true;job.background=job.speculative();networkActive++;if(job.background)backgroundActive++
                networkStarts++;starts+=job
            }
        }
        for(job in starts){
            if(synchronized(lock){job.stopped||job.phase!=Phase.NETWORK})continue
            val cancel=try{
                if(job.bypassCache){synchronized(lock){bypasses++};backend.uncachedNetwork(job.key){networkDone(job,it)}}
                else backend.network(job.key){networkDone(job,it)}
            }catch(e:Exception){networkDone(job,Result.failure(e));continue}
            val obsolete=synchronized(lock){job.cancel=cancel;job.stopped||job.phase!=Phase.NETWORK};if(obsolete)cancel()
        }
    }
    private fun finish(job:Job,result:Result<TileBody>,fromNetwork:Boolean=false){
        if(synchronized(lock){job.stopped||job.phase==Phase.DONE})return
        val stamped=result.map{body->
            val fingerprint=if(body.status==404)"404" else PreparedTerrainCache.digest(body.bytes)
            body.copy(stamp=SourceStamp(job.key.id,fingerprint,body.until))
        }
        var cancelNetwork:(()->Unit)?=null
        synchronized(lock){
            if(job.stopped||job.phase==Phase.DONE)return
            if(!fromNetwork&&job.phase==Phase.NETWORK)cancelNetwork=job.cancel
            if(job.phase==Phase.NETWORK){networkActive--;if(job.background)backgroundActive--}
            job.phase=Phase.DONE;job.expiry?.cancel(false);
            job.localWork?.let{local.remove(it)};jobs.remove(job.key,job)
            stamped.getOrNull()?.let{body->
                metadata[job.key.id]=body.stamp!!;while(metadata.size>4096)metadata.remove(metadata.keys.first())
                prune()
                if(body.until>clock()&&body.bytes.size<=ramLimit&&
                    (!job.speculative()||bytes+body.bytes.size<=ramLimit*9/10)){
                    ram.remove(job.key)?.let{bytes-=it.bytes.size};ram[job.key]=body;bytes+=body.bytes.size;nextExpiry=minOf(nextExpiry,body.until)
                    while(bytes>ramLimit&&ram.isNotEmpty())bytes-=ram.remove(ram.keys.first())!!.bytes.size
                }
            }
        }
        cancelNetwork?.invoke()
        stamped.fold({job.result.complete(it)},{job.result.completeExceptionally(it)});pump()
    }
    fun fresh(stamp:SourceStamp):Boolean=synchronized(lock){
        val latest=metadata[stamp.key]
        if(latest!=null&&latest.fingerprint!=stamp.fingerprint)false else (latest?.until?:stamp.until)>clock()
    }
    fun contains(key:ResourceKey)=synchronized(lock){prune();ram[key]?.until?.let{it>clock()}==true}
    fun memoryPressure(){synchronized(lock){ram.clear();bytes=0;nextExpiry=Long.MAX_VALUE}}
    fun invalidate(){synchronized(lock){ram.clear();bytes=0;nextExpiry=Long.MAX_VALUE;for((key,old)in metadata.toMap())metadata[key]=old.copy(fingerprint="invalidated",until=0)}}
    fun stats():Map<String,Long> = synchronized(lock){prune();mapOf("encodedBytes" to bytes,"ramHits" to ramHits,"localHits" to localHits,"networkStarts" to networkStarts,"shared" to shares,"cancelled" to cancellations,"pending" to jobs.size.toLong(),"networkActive" to networkActive.toLong(),"backgroundActive" to backgroundActive.toLong(),"localQueued" to local.queue.size.toLong(),"networkQueued" to jobs.values.count{it.phase==Phase.QUEUED}.toLong(),"localTimeouts" to localTimeouts,"localWatchRuns" to localWatchRuns,"localErrors" to localErrors,"uncachedStarts" to bypasses,"localCooldownMs" to (localCooldownUntil-localNow()).coerceAtLeast(0))}
    fun latencyStats():Map<String,Long> = synchronized(lock){
        val now=System.nanoTime()
        mapOf("lastHttpMs" to lastNetworkMs,"maxHttpMs" to maxNetworkMs,"lastAdmissionMs" to lastAdmissionMs,
            "oldestActiveHttpMs" to (jobs.values.filter{it.phase==Phase.NETWORK}.maxOfOrNull{TimeUnit.NANOSECONDS.toMillis(now-it.networkNs)}?:0L),
            "oldestWaitingMs" to (jobs.values.filter{it.phase==Phase.QUEUED||it.phase==Phase.LOCAL}.maxOfOrNull{TimeUnit.NANOSECONDS.toMillis(now-it.createdNs)}?:0L))
    }
    fun awaitClosed(){local.awaitTermination(5,TimeUnit.SECONDS);timers.awaitTermination(5,TimeUnit.SECONDS)}
    override fun close(){
        val rows=synchronized(lock){if(closed)return;closed=true;localWatchEpoch++;localWatch?.cancel(false);localWatch=null;val rows=jobs.values.toList();for(job in rows){job.stopped=true;job.expiry?.cancel(false)};jobs.clear();ram.clear();metadata.clear();bytes=0;rows}
        for(job in rows){job.cancel?.invoke();job.result.completeExceptionally(PreparationAborted())}
        local.shutdownNow();timers.shutdownNow()
        backend.close()
    }
}
