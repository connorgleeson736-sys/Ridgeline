package nz.ridgeline.nativeapp

import java.io.IOException
import java.util.concurrent.*

/** Source lifetimes are absolute. Hash new bodies once, never on a fresh reuse. */
internal data class SourceStamp(val key:String,val fingerprint:String,val until:Long)
internal data class TileBody(val bytes:ByteArray,val status:Int,val until:Long,val stamp:SourceStamp?=null)
internal data class ResourceKey(val url:String,val offline:Boolean){val id:String by lazy{PreparedTerrainCache.digest((if(offline)"offline:" else "online:").plus(url).toByteArray())}}
internal interface TileBackend {
    /** Local storage only; a miss MUST NOT initiate HTTP. */
    fun local(key:ResourceKey):TileBody?
    fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit
}
internal class ResourcePending(val ready:CompletableFuture<*>):RuntimeException(null,null,false,false)

/** Local lookups have their own executor, independent of admitted network work.
 * CPU consumers suspend by continuation instead of occupying a decode worker. */
internal class TileTransport(private val backend:TileBackend,private val ramLimit:Long,
    private val clock:()->Long=System::currentTimeMillis,private val graceMs:Long=600):AutoCloseable {
    private val lock=Any()
    private val local=Executors.newFixedThreadPool(2)
    private val timers=Executors.newSingleThreadScheduledExecutor()
    private val jobs=LinkedHashMap<ResourceKey,Job>()
    private val ram=LinkedHashMap<ResourceKey,TileBody>(16,.75f,true)
    private val metadata=LinkedHashMap<String,SourceStamp>(16,.75f,true)
    private var nextExpiry=Long.MAX_VALUE
    private fun prune(){val now=clock();if(now<nextExpiry)return;nextExpiry=Long.MAX_VALUE;val it=ram.entries.iterator();while(it.hasNext()){val row=it.next();if(row.value.until<=now){bytes-=row.value.bytes.size;it.remove()}else nextExpiry=minOf(nextExpiry,row.value.until)}}
    private var bytes=0L;private var networkActive=0;private var backgroundActive=0
    private var foregroundLimit=12;private var closed=false
    private var ramHits=0L;private var localHits=0L;private var networkStarts=0L;private var shares=0L;private var cancellations=0L
    private enum class Phase { LOCAL,QUEUED,NETWORK,DONE }
    private inner class Job(val key:ResourceKey){
        val result=CompletableFuture<TileBody>();val users=LinkedHashSet<Consumer>()
        var phase=Phase.LOCAL;var stopped=false;var background=false;var cancel:(()->Unit)?=null
        var expiry:ScheduledFuture<*>?=null
        fun speculative()=users.isNotEmpty()&&users.all{it.speculative}
        fun rank()=users.minOfOrNull{it.priority+(if(it.speculative)10000 else 0)}?:Int.MAX_VALUE
    }
    inner class Consumer internal constructor(internal var priority:Int,internal var speculative:Boolean):AutoCloseable {
        private val owned=LinkedHashMap<ResourceKey,Job>()
        private val immediate=LinkedHashMap<ResourceKey,CompletableFuture<TileBody>>()
        private var released=false
        fun promote(priority:Int,speculative:Boolean=false){synchronized(lock){this.priority=priority;this.speculative=speculative
            for(job in owned.values)if(job.phase==Phase.NETWORK&&job.background&&!job.speculative()){job.background=false;backgroundActive--}
        };pump()}
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
                if(job.phase==Phase.NETWORK&&job.background&&!job.speculative()){job.background=false;backgroundActive--}
                CompletableFuture<TileBody>().also{immediate[key]=it;forward=job to it}
            }
            forward?.let{(job,view)->job.result.whenComplete{body,error->if(error!=null)view.completeExceptionally(error)else view.complete(body)}}
            start?.let{job->local.execute{try{
                if(synchronized(lock){job.stopped})return@execute
                val body=backend.local(job.key)
                if(body!=null){synchronized(lock){localHits++};finish(job,Result.success(body))}
                else if(job.key.offline)finish(job,Result.failure(IOException("Offline tile unavailable")))
                else{synchronized(lock){if(!job.stopped)job.phase=Phase.QUEUED};pump()}
            }catch(e:Exception){finish(job,Result.failure(e))}}}
            pump();return future
        }
        override fun close()=release(false)
        fun release(force:Boolean){
            val cancelledViews=ArrayList<CompletableFuture<TileBody>>()
            synchronized(lock){
                if(released)return;released=true
                val out=owned.values.toList();owned.clear();cancelledViews.addAll(immediate.values);immediate.clear()
                for(job in out){job.users.remove(this)
                    if(job.users.isEmpty()&&job.phase!=Phase.DONE&&!job.stopped){
                        job.expiry=timers.schedule({abandon(job)},if(speculative||force)0 else graceMs,TimeUnit.MILLISECONDS)
                    }
                }
            }
            // Cancel this reader immediately; other consumers keep the shared job.
            for(view in cancelledViews)view.completeExceptionally(PreparationAborted())
            pump()
        }

    }
    fun consumer(priority:Int=0,speculative:Boolean=false)=Consumer(priority,speculative)
    fun movement(moving:Boolean){synchronized(lock){foregroundLimit=if(moving)8 else 12};pump()}
    private fun abandon(job:Job){
        val cancel=synchronized(lock){
            if(job.users.isNotEmpty()||job.phase==Phase.DONE||job.stopped)return
            job.stopped=true;job.expiry=null;jobs.remove(job.key,job);cancellations++
            if(job.phase==Phase.NETWORK){networkActive--;if(job.background)backgroundActive--}
            job.phase=Phase.DONE;job.cancel
        }
        cancel?.invoke();job.result.completeExceptionally(PreparationAborted());pump()
    }
    private fun pump(){
        val starts=ArrayList<Job>()
        synchronized(lock){
            if(closed)return
            while(true){
                val job=jobs.values.filter{it.phase==Phase.QUEUED&&!it.stopped&&it.users.isNotEmpty()}
                    .filter{if(it.speculative())backgroundActive<2 else networkActive-backgroundActive<foregroundLimit}
                    .minByOrNull{it.rank()}?:break
                job.phase=Phase.NETWORK;job.background=job.speculative();networkActive++;if(job.background)backgroundActive++
                networkStarts++;starts+=job
            }
        }
        for(job in starts){
            if(synchronized(lock){job.stopped})continue
            val cancel=try{backend.network(job.key){finish(job,it)}}catch(e:Exception){finish(job,Result.failure(e));continue}
            val stopped=synchronized(lock){job.cancel=cancel;job.stopped};if(stopped)cancel()
        }
    }
    private fun finish(job:Job,result:Result<TileBody>){
        val stamped=result.map{body->
            val fingerprint=if(body.status==404)"404" else PreparedTerrainCache.digest(body.bytes)
            body.copy(stamp=SourceStamp(job.key.id,fingerprint,body.until))
        }
        synchronized(lock){
            if(job.stopped||job.phase==Phase.DONE)return
            if(job.phase==Phase.NETWORK){networkActive--;if(job.background)backgroundActive--}
            job.phase=Phase.DONE;job.expiry?.cancel(false);jobs.remove(job.key,job)
            stamped.getOrNull()?.let{body->
                metadata[job.key.id]=body.stamp!!;while(metadata.size>4096)metadata.remove(metadata.keys.first())
                prune()
                if(body.until>clock()&&body.bytes.size<=ramLimit){
                    ram.remove(job.key)?.let{bytes-=it.bytes.size};ram[job.key]=body;bytes+=body.bytes.size;nextExpiry=minOf(nextExpiry,body.until)
                    while(bytes>ramLimit&&ram.isNotEmpty())bytes-=ram.remove(ram.keys.first())!!.bytes.size
                }
            }
        }
        stamped.fold({job.result.complete(it)},{job.result.completeExceptionally(it)});pump()
    }
    fun fresh(stamp:SourceStamp):Boolean=synchronized(lock){
        val latest=metadata[stamp.key]
        if(latest!=null&&latest.fingerprint!=stamp.fingerprint)false else (latest?.until?:stamp.until)>clock()
    }
    fun contains(key:ResourceKey)=synchronized(lock){prune();ram[key]?.until?.let{it>clock()}==true}
    fun memoryPressure(){synchronized(lock){ram.clear();bytes=0;nextExpiry=Long.MAX_VALUE}}
    fun invalidate(){synchronized(lock){ram.clear();bytes=0;nextExpiry=Long.MAX_VALUE;for((key,old)in metadata.toMap())metadata[key]=old.copy(fingerprint="invalidated",until=0)}}
    fun stats():Map<String,Long> = synchronized(lock){prune();mapOf("encodedBytes" to bytes,"ramHits" to ramHits,"localHits" to localHits,"networkStarts" to networkStarts,"shared" to shares,"cancelled" to cancellations,"pending" to jobs.size.toLong())}
    fun awaitClosed(){local.awaitTermination(5,TimeUnit.SECONDS);timers.awaitTermination(5,TimeUnit.SECONDS)}
    override fun close(){
        val rows=synchronized(lock){if(closed)return;closed=true;val rows=jobs.values.toList();for(job in rows){job.stopped=true;job.expiry?.cancel(false)};jobs.clear();ram.clear();metadata.clear();bytes=0;rows}
        for(job in rows){job.cancel?.invoke();job.result.completeExceptionally(PreparationAborted())}
        local.shutdownNow();timers.shutdownNow()
    }
}
