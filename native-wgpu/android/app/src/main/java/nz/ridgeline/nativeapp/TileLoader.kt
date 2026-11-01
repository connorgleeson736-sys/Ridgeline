package nz.ridgeline.nativeapp

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

private typealias Dem = DemSampling.Raster

internal class TileHttpException(val code:Int):IOException("Provider HTTP $code")

/** Native-source prototype; viewport/LOD parity and performance acceptance remain open. */
class TileLoader(private val context: Context, private val view: TerrainView) {
    private data class Id(val z: Int, val x: Int, val y: Int) {
        fun parent() = Id(z - 1, x / 2, y / 2)
        fun gpu() = GpuUploadQueue.Id(z,x,y)
    }
    private data class Source(val id:Id,val geometry:Boolean)
    // Automatic HTTP cache is disposable. Saved regions/packs are never evicted here.
    private val preferences=context.getSharedPreferences("display",0)
    @Volatile private var downloadLimits=DownloadLimits(preferences.getInt("downloadMoving",DownloadLimits.DEFAULT.moving),preferences.getInt("downloadStationary",DownloadLimits.DEFAULT.stationary),preferences.getInt("downloadBackground",DownloadLimits.DEFAULT.background)).normalized()
    fun downloadJobs(mode:DownloadLimits.Mode)=downloadLimits.value(mode)
    fun downloadJobs(mode:DownloadLimits.Mode,value:Int)=setDownloadLimits(downloadLimits.with(mode,value))
    fun resetDownloadJobs()=setDownloadLimits(DownloadLimits.DEFAULT)
    private fun setDownloadLimits(value:DownloadLimits){
        downloadLimits=value.normalized()
        preferences.edit().putInt("downloadMoving",downloadLimits.moving).putInt("downloadStationary",downloadLimits.stationary).putInt("downloadBackground",downloadLimits.background).apply()
        transport.limits(downloadLimits)
    }
    @Volatile private var detailSteps=preferences.getInt("terrainDetail",DetailPolicy.DEFAULT).coerceIn(0,DetailPolicy.MAX) to
        preferences.getInt("imageryDetail",DetailPolicy.DEFAULT).coerceIn(0,DetailPolicy.MAX)
    val terrainDetail get()=detailSteps.first
    val imageryDetail get()=detailSteps.second
    fun detail(geometry:Boolean,value:Int){
        val step=value.coerceIn(0,DetailPolicy.MAX)
        detailSteps=if(geometry)step to imageryDetail else terrainDetail to step
        preferences.edit().putInt(if(geometry)"terrainDetail" else "imageryDetail",step).apply()
        // Settings are part of the immutable plan key. Keep useful resident data.
        runCatching{scheduler.execute{viewRevision++;preparedRevision=-1;clearWarm();cameraChanged()}}
    }
    val automaticCacheMiB get()=preferences.getInt("automaticCacheMiB",1000).coerceIn(0,1000)
    fun automaticCacheMiB(value:Int){preferences.edit().putInt("automaticCacheMiB",value.coerceIn(0,1000)).apply()}
    val automaticCacheBytes=AutomaticCacheBudget.bytes(automaticCacheMiB,context.cacheDir.usableSpace)
    private val preparedBytes=minOf(128L*1024*1024,automaticCacheBytes/8)
    private val escapedBytes=minOf(16L*1024*1024,preparedBytes/4)
    private val compositionBytes=(preparedBytes-escapedBytes)/2
    private val escaped by lazy{HedgedBodyCache(File(context.cacheDir,"native-escaped-v1"),escapedBytes)}
    private val escapedWrites=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(8),ThreadFactory{work->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);work.run()},"RidgelineSourceCache")},ThreadPoolExecutor.DiscardOldestPolicy())
    private val preparedWrites=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(8),ThreadFactory{work->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);work.run()},"RidgelinePreparedCache")},ThreadPoolExecutor.DiscardOldestPolicy())
    private val derivedReads=DerivedReadGate()
    private val client = OkHttpClient.Builder()
        .cache(if(automaticCacheBytes>0)Cache(File(context.cacheDir, "native-http-v1"), automaticCacheBytes-preparedBytes)else null)
        .dispatcher(Dispatcher().apply{maxRequests=DownloadLimits.HTTP_CAPACITY;maxRequestsPerHost=DownloadLimits.HTTP_CAPACITY})
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    init {
        if(automaticCacheBytes==0L)preparedWrites.execute {
            // No HTTP cache is open and derived stores have a zero write limit.
            for(name in listOf("native-http-v1","native-terrain-v3","native-compositions-v1","native-escaped-v1"))
                runCatching{File(context.cacheDir,name).deleteRecursively()}
        }
    }
    private val key = context.assets.open("map-defaults.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")
    }
    private val prepared by lazy{PreparedTerrainCache(File(context.cacheDir,"native-terrain-v3"),preparedBytes-escapedBytes-compositionBytes,"terrain-source-delta1-dem18-grid128-profile-v2:$key")}
    private val preparation=PreparationControl()
    private fun checkPreparation(){preparation.checkpoint();taskContext.get()?.latePrepared()?.let{throw PreparedAvailable(it)}}
    private val geometryDependencies=ThreadLocal<MutableMap<GpuUploadQueue.Id,PreparedTerrainCache.Dependency>>()
    private fun linz(kind: String, id: Id, ext: String): String =
        "https://basemaps.linz.govt.nz/v1/tiles/$kind/WebMercatorQuad/${id.z}/${id.x}/${id.y}.$ext?" +
            (if (kind == "elevation") "pipeline=terrain-rgb&" else "") + "api=$key"
    private val offline=OfflineStore(context)
    private val downloadJobs=Executors.newSingleThreadExecutor()
    // A region download may run for minutes. Read-only UI queries must not
    // enter that executor's FIFO; the reader thread expires while idle.
    private val offlineQueries=ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>())
    private val regionContext=ThreadLocal<String>()
    private val regionSources=ThreadLocal<TileTransport.Consumer>()
    @Volatile private var regionPrefetch:OfflinePrefetch?=null
    @Volatile private var downloadLease:TileTransport.Consumer?=null
    private val downloadCancel=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var offlineOnly=preferences.getBoolean("offlineOnly",false);private set
    val textureBackend get()=preferences.getInt("textureBackend",0)
    fun textureBackend(mode:Int)=view.textureBackend(mode)
    fun offlineOnly(value:Boolean){offlineOnly=value;preferences.edit().putBoolean("offlineOnly",value).apply();transport.batchDemand{for(task in jobs.values)task.cancel();clearWarm()};synchronized(ready){ready.clear();readyBytes=0};synchronized(decoded){decoded.clear()};cameraChanged()}
    val overviews=OverviewAttachments(context)
    private val attachmentJobs=Executors.newSingleThreadExecutor()
    private val imagery = NativeImagery(context, ::fetch, {z,x,y->linz("aerial",Id(z,x,y),"webp")},overviews,::checkPreparation,::localImage)
    private val imageryReady=CompletableFuture.runAsync({imagery.initialize()},attachmentJobs)
    fun attachOverview(uri:android.net.Uri,done:(String?)->Unit){attachmentJobs.execute{
        val error=try{overviews.attach(uri);imageryChanged();null}catch(e:Exception){e.message?:"Could not attach imagery ZIP."}
        view.post{if(!dead)done(error)}
    }}
    fun detachOverview(payload:String,done:()->Unit){attachmentJobs.execute{overviews.detach(payload);imageryChanged();view.post{if(!dead)done()}}}
    private val am = context.getSystemService(ActivityManager::class.java)
    val recommendedRamCacheMiB=RamCacheBudget.recommended(am.memoryClass,am.isLowRamDevice)
    val ramCacheMiB get()=preferences.getInt("ramCacheMiB",recommendedRamCacheMiB).coerceIn(0,1000)
    @Volatile private var ramBudget=RamCacheBudget.split(ramCacheMiB)
    fun ramCacheMiB(value:Int){
        val mib=value.coerceIn(0,1000)
        preferences.edit().putInt("ramCacheMiB",mib).apply()
        ramBudget=RamCacheBudget.split(mib)
        transport.setRamLimit(ramBudget.encoded)
        synchronized(ready){trimReady()}
        synchronized(decoded){trimDecoded()}
    }
    private val imageLimit = if (am.isLowRamDevice) 32 else (am.memoryClass / 2).coerceIn(64, 128)
    private val geometryLimit = (imageLimit/2).coerceIn(16,96)
    private var readyBytes = 0
    private val demLimit get()=(ramBudget.decoded/(256*256*4)).toInt()
    private val ready = LinkedHashMap<Source, ByteBuffer>(16, .75f, true)
    private data class Decoded(val raster:Dem,val dependencies:List<PreparedTerrainCache.Dependency>)
    private val decoded = LinkedHashMap<Id, Decoded>(16, .75f, true)
    private val decoding=SharedDecode<Pair<Id,String>,FloatArray>()
    // Call only while holding the corresponding map monitor. New limits are
    // read under that lock so concurrent insertions cannot restore an old budget.
    private fun trimReady(){while(readyBytes.toLong()>ramBudget.prepared&&ready.isNotEmpty()){
        val first=ready.keys.firstOrNull{it !in wanted}?:ready.keys.first()
        readyBytes-=ready.remove(first)!!.capacity()
    }}
    private fun trimDecoded(){while(decoded.size>demLimit)decoded.remove(decoded.keys.first())}
    @Volatile private var pipelinePauseUntil=0L
    private val taskContext=ThreadLocal<Task>()
    private val sourceRecords=ThreadLocal<MutableMap<String,SourceStamp>>()
    private val jobs = ConcurrentHashMap<Source, Task>()
    private val sequence = AtomicLong()
    private val latePreparedWins=AtomicLong()
    private data class Plan(val ranked:Map<Source,Int>)
    private val plans=DemandPlanCache<DemandPlanKey,Plan>()
    private val planBuffer=ByteBuffer.allocateDirect(512*12).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var dead = false
    @Volatile private var active = true
    @Volatile private var wanted = emptySet<Source>()
    private var timer: ScheduledFuture<*>? = null
    private val planCadence=PlanCadence()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var warmWanted=emptySet<Source>()
    @Volatile var prepareAhead=preferences.getBoolean("prepareAhead",false);private set
    fun prepareAhead(value:Boolean){prepareAhead=value;preferences.edit().putBoolean("prepareAhead",value).apply();clearWarm();cameraChanged()}
    private var previousPose:CameraPose?=null
    private var previousFocus:SurfaceFocus?=null
    private var viewRevision=0L
    private var preparedRevision=-1L
    private var idleWake:ScheduledFuture<*>?=null
    private val predictions=LinkedHashMap<ResourceKey,TileTransport.Consumer>()
    private var predictionAt=0L
    private val predictionRecent=java.util.ArrayDeque<Long>()
    private var predictionCount=0
    private var lastGesture=false
    private var compositionStore:PreparedTerrainCache?=null
    private val compositionLock=Any()
    private fun imageStore():PreparedTerrainCache=synchronized(compositionLock){compositionStore?:PreparedTerrainCache(File(context.cacheDir,"native-compositions-v1"),compositionBytes,"rgba-premultiplied-gap-v1:$key:"+preferences.getLong("imageryGeneration",0),256*256*4).also{compositionStore=it}}
    // DEM halo work cannot occupy every imagery worker. Four bounded requests,
    // shared DEM futures, and no per-halo executor multiplication.
    private val geometryWorkers = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private val imageWorkers = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private val localImages=SharedLocalReads()
    private fun workers(source:Source)=if(source.geometry)geometryWorkers else imageWorkers
    private data class Failure(val attempts:Int,val retryAt:Long)
    private val failures=ConcurrentHashMap<Source,Failure>()
    private fun upload(source:Source,data:ByteBuffer) {
        val id=source.id
        if(source.geometry)view.uploadGeometry(id.z,id.x,id.y,data)
        else view.uploadImage(id.z,id.x,id.y,data)
    }
    private fun resident(source:Source)=view.isResident(source.id.gpu(),source.geometry)
    private inner class Task(val source:Source,@Volatile var priority:Int,@Volatile var speculative:Boolean=false):Runnable,Comparable<Task>{
        private val order=sequence.getAndIncrement()
        private val queued=java.util.concurrent.atomic.AtomicBoolean(false)
        private val finished=java.util.concurrent.atomic.AtomicBoolean(false)
        val consumer=transport.consumer(priority,speculative,imagery=!source.geometry)
        private val imageryGeneration=preferences.getLong("imageryGeneration",0)
        val local=localImages.consumer(priority+if(speculative)10000 else 0)
        fun localInput(key:String,read:()->ByteArray?)=local.acquire("$imageryGeneration:$key",read)
        val records=LinkedHashMap<String,SourceStamp>()
        val decodeWait=SharedDecode.Waiter<Pair<Id,String>,FloatArray>()
        @Volatile private var derivedRead:PreparedShortcut?=null
        fun latePrepared()=derivedRead?.takeLate()
        fun derived(read:()->PreparedTerrainCache.Entry?):ByteBuffer? {
            checkPreparation()
            val shortcut=derivedRead?:PreparedShortcut(derivedReads.race(priority){if(eligible())read()else null},
                valid={entry->eligible()&&(source.geometry||imageryGeneration==preferences.getLong("imageryGeneration",0))&&entry.dependencies.all(::dependencyFresh)},
                onLate={latePreparedWins.incrementAndGet()}).also{derivedRead=it}
            if(finished.get()){shortcut.close();throw PreparationAborted()}
            return shortcut.ready().also{checkPreparation()}
        }
        val inputs=GeometryInputPipeline<Id,Decoded>(retain={pipelineEligible()})
        fun pipelineEligible()=source.geometry&&!speculative&&priority<=2&&parkedUntil==0L&&!finished.get()&&
            (pipelinePauseUntil==0L||System.nanoTime()-pipelinePauseUntil>=0)
        @Volatile var started=false;private set
        @Volatile private var parkedUntil=0L
        fun eligible()=!dead&&active&&!finished.get()&&(source in wanted||source in warmWanted&&prepareAhead||parkedUntil>System.currentTimeMillis())
        override fun compareTo(other:Task)=compareValuesBy(this,other,{it.priority+(if(it.speculative||it.parkedUntil!=0L)10000 else 0)},{it.order})
        fun enqueue(){if(!finished.get()&&!dead&&queued.compareAndSet(false,true))try{workers(source).execute(this)}catch(_:RejectedExecutionException){queued.set(false);cancel()}}
        fun promote(rank:Int){if(priority==rank&&!speculative&&parkedUntil==0L)return;priority=rank;speculative=false;parkedUntil=0;consumer.promote(rank);local.promote(rank);if(!pipelineEligible())inputs.clear();if(workers(source).remove(this)){queued.set(false);enqueue()}}
        fun park(){if(parkedUntil!=0L)return;inputs.clear();if(!consumer.park()){cancel();return};local.promote(priority+10000);parkedUntil=System.currentTimeMillis()+600;
            if(workers(source).remove(this)){queued.set(false);enqueue()};scheduler.schedule({if(source !in wanted)cancel()},600,TimeUnit.MILLISECONDS)}
        fun cancel(){if(finished.compareAndSet(false,true)){workers(source).remove(this);jobs.remove(source,this);consumer.release(true);local.close();inputs.clear();decodeWait.clear();derivedRead?.close()}}
        override fun run(){
            queued.set(false);if(!eligible()){cancel();return}
            var waiting:CompletableFuture<*>?=null
            try{
                started=true;preparation.enter{eligible()};taskContext.set(this);sourceRecords.set(records)
                val data=try{val loaded=if(source.geometry)loadGeometry(source.id)else loadImage(source.id);checkPreparation();loaded}catch(ready:PreparedAvailable){ready.data}
                preparation.checkpoint();failures.remove(source)
                synchronized(ready){ready.put(source,data)?.let{readyBytes-=it.capacity()};readyBytes+=data.capacity()
                    trimReady()}
                if((source in wanted||source in warmWanted)&&!resident(source))upload(source,data)
            }catch(pending:ResourcePending){waiting=pending.ready
            }catch(_:PreparationAborted){
            }catch(e:Exception){
                if(eligible()&&source in wanted){
                    val attempt=(failures[source]?.attempts?:0)+1;val delay=when(attempt){1->1000L;2->4000L;else->15000L}
                    failures[source]=Failure(attempt,android.os.SystemClock.elapsedRealtime()+delay)
                    if(!offlineOnly&&attempt<=3)runCatching{scheduler.schedule({cameraChanged()},delay,TimeUnit.MILLISECONDS)}
                    view.post{view.onStatus("Terrain load failed: "+if(e is TileHttpException)"provider HTTP ${e.code}" else "source unavailable")}
                }
            }finally{
                preparation.leave();geometryDependencies.remove();sourceRecords.remove();taskContext.remove()
                if(finished.get()){inputs.clear();decodeWait.clear()}
                if(waiting!=null&&!finished.get())(derivedRead?.resumeOn(waiting)?:waiting).whenComplete{_,_->enqueue()} else{
                    cancel();if(!dead&&active&&source in wanted&&!resident(source))cameraChanged()
                }
            }
        }
    }
    fun cameraChanged() = synchronized(this) {
        // Throttle using the latest immutable camera; a continuous drag must not
        // indefinitely postpone source selection, as a cancel/reschedule debounce did.
        if(!dead&&active&&timer==null)timer=scheduler.schedule({
            synchronized(this){timer=null;planCadence.started(android.os.SystemClock.elapsedRealtime())}
            loadView()
        },planCadence.delay(android.os.SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS)
    }
    private fun sourcePlan(geometry:Boolean,key:DemandPlanKey):List<Id> {
        val pose=key.pose
        val output=planBuffer;output.rewind()
        val budget=maxOf(16,(if(geometry)geometryLimit else imageLimit)*3/4)
        val focus=key.surfaceFocus
        val count=NativeBridge.planFocused(key.width,key.height,pose.longitude,pose.latitude,
            pose.zoom,pose.bearing,pose.pitch,pose.elevation,budget,
            DetailPolicy.target(geometry,if(geometry)key.terrainDetail else key.imageryDetail),if(geometry)22 else 19,1.0,
            focus?.longitude?:Double.NaN,focus?.latitude?:Double.NaN,focus?.range?:Double.NaN,output)
        return List(count){Id(output.int,output.int,output.int)}
    }
    private fun loadView() {
        if (dead||!active||view.width==0||view.height==0) return
        if(!imageryReady.isDone||imageryReady.isCompletedExceptionally)return
        val planningPose=view.planningPose
        val key=DemandPlanKey((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),
            (view.height/view.pixelScale).roundToInt().coerceAtLeast(1),planningPose,terrainDetail,imageryDetail,view.surfaceFocusFor(planningPose))
        val plan=plans.get(key) {
          val next=LinkedHashMap<Source,Int>()
          val imageIds=sourcePlan(false,key)
          val focus=imageIds.firstOrNull()
          val bootstrap=focus?.takeIf{!view.hasResidentCoverage(it.gpu(),false)}?.let{imagery.bootstrap(it.z,it.x,it.y)}
          for(geometry in listOf(true,false)) {
            val ids=if(geometry)sourcePlan(true,key)else imageIds
            val limit=if(geometry)geometryLimit else imageLimit
            val original=SourceDemand.bounded(ids.map{it.gpu()},limit)
            val demand=if(bootstrap!=null&&(!geometry||!view.hasResidentCoverage(focus!!.gpu(),true)))SourceDemand.bootstrap(original,bootstrap,limit)else original
            for((id,priority)in demand)next[Source(Id(id.z,id.x,id.y),geometry)]=priority
          }
          Plan(next.toMap())
        }
        val ranked=plan.ranked
        val prior=previousPose
        // Publish the whole camera generation before any freed network slot
        // is reused. Only memory lookups, queue operations and async scheduling
        // belong here; predictive catalog/source work stays outside this scope.
        transport.batchDemand {
            if(prior!=key.pose||previousFocus!=key.surfaceFocus){viewRevision++;clearWarm();previousPose=key.pose;previousFocus=key.surfaceFocus}
            wanted = ranked.keys.toSet()
            transport.imageryDemand(wanted.any{!it.geometry&&!view.hasResidentCoverage(it.id.gpu(),false,true)})
            // Restore already prepared parents before requesting more detailed work.
            // A zoom jump can skip several levels; the exact new plan need not name
            // the parent that was visible moments ago. Geometry/photos are independent.
            val fallbackSources=HashSet<Source>()
            for(source in wanted){var id=source.id;while(id.z>0){id=id.parent();val ancestor=Source(id,source.geometry);if(ancestor in wanted)fallbackSources.add(ancestor)}}
            val cachedParents=LinkedHashMap<Source,ByteBuffer>()
            for(source in wanted){
                // A ready fine tile does not cover its parent's siblings. Restore
                // backgrounds for ideal leaves, not recursively for fallback requests.
                if(source in fallbackSources)continue
                val parent=synchronized(ready){CachedFallback.nearest(source.id.gpu()){id->ready[Source(Id(id.z,id.x,id.y),source.geometry)]}}
                if(parent!=null){val id=parent.first;val owner=Source(Id(id.z,id.x,id.y),source.geometry)
                    if(!resident(owner))cachedParents[owner]=parent.second}
            }
            view.updateRankedUploadDemand(ranked.filterKeys{it.geometry}.mapKeys{it.key.id.gpu()},
                ranked.filterKeys{!it.geometry}.mapKeys{it.key.id.gpu()})
            for((parent,data)in cachedParents)upload(parent,data)
            failures.keys.removeAll{it !in wanted}
            if(view.gestureInProgress&&!lastGesture){predictionCount=0;cancelPredictions()}
            lastGesture=view.gestureInProgress
            transport.movement(view.gestureInProgress)
            val returning=jobs.values.filter{it.source !in wanted&&!it.speculative&&it.started}.sortedBy{it.priority}.take(2).toSet()
            for(task in jobs.values){if(task.source in wanted)task.promote(ranked[task.source]?:0)else if(task.source in warmWanted&&prepareAhead&&!view.gestureInProgress){}else if(task !in returning)task.cancel()else task.park()}
            for((source,priority)in ranked.entries.sortedBy{it.value}){
                if(resident(source))continue
                val fallback=source in fallbackSources
                if(view.hasResidentCoverage(source.id.gpu(),source.geometry,!fallback))continue
                val old=synchronized(ready){ready[source]};if(old!=null){upload(source,old);continue}
                val failure=failures[source];if(failure!=null&&android.os.SystemClock.elapsedRealtime()<failure.retryAt)continue
                schedule(source,priority,false)
            }
        }
        if(prior!=key.pose)predict(prior,key)
        scheduleIdle(key,viewRevision)
    }
    private fun schedule(source:Source,priority:Int,speculative:Boolean){
        val task=Task(source,priority,speculative)
        if(jobs.putIfAbsent(source,task)==null)task.enqueue()else task.cancel()
    }
    @Synchronized private fun cancelPredictions(){
        // Completing a canceled future runs its callback synchronously; remove
        // ownership before callbacks can mutate the same prediction map.
        val obsolete=predictions.values.toList();predictions.clear()
        transport.batchDemand{for(consumer in obsolete)consumer.release(true)}
    }
    @Synchronized private fun clearWarm(cancelPredictions:Boolean=true){
        idleWake?.cancel(false);idleWake=null;warmWanted=emptySet();view.updateWarmDemand(emptySet(),emptySet())
        transport.batchDemand {
            for(task in jobs.values)if(task.speculative)task.cancel()
            if(cancelPredictions)cancelPredictions()
        }
    }
    private fun predict(prior:CameraPose?,key:DemandPlanKey){
        val now=System.currentTimeMillis();if(prior==null||offlineOnly||now-predictionAt<250)return
        if(jobs.values.count{!it.speculative}>3||!view.canPrepareAhead&& !view.gestureInProgress){cancelPredictions();return}
        while(predictionRecent.isNotEmpty()&&now-predictionRecent.first>60000)predictionRecent.removeFirst()
        if(predictionRecent.size>=60||predictionCount>=24)return
        predictionAt=now
        val pose=key.pose;val dx=(pose.longitude-prior.longitude).coerceIn(-.01,.01);val dy=(pose.latitude-prior.latitude).coerceIn(-.01,.01)
        val turn=((pose.bearing-prior.bearing+540)%360-180).coerceIn(-15f,15f)
        if(kotlin.math.abs(dx)+kotlin.math.abs(dy)<1e-8&&kotlin.math.abs(turn)<1&&kotlin.math.abs(pose.zoom-prior.zoom)<.015)return
        val future=pose.copy(longitude=(pose.longitude+dx).coerceIn(165.0,180.0),latitude=(pose.latitude+dy).coerceIn(-49.0,-33.0),bearing=pose.bearing+turn,zoom=(pose.zoom+(pose.zoom-prior.zoom).coerceIn(-.5,.5)).coerceIn(4.0,19.0))
        val candidates=if(pose.zoom<prior.zoom-.015){
            val leaf=sourcePlan(false,key).firstOrNull();if(leaf==null)emptyList()else generateSequence(leaf.parent()){if(it.z>5)it.parent()else null}.take(2).toList()
        }else sourcePlan(false,key.copy(pose=future,surfaceFocus=null)).filter{Source(it,false) !in wanted}.take(3)
        val urls=candidates.filter{it.z in 0..19}.flatMap{imagery.inputs(it.z,it.x,it.y)}.distinct().take(3)
        for(url in urls){if(predictionRecent.size>=60||predictionCount>=24)break
            val resource=resourceKey(url);if(transport.contains(resource)||synchronized(this){resource in predictions})continue
            val consumer=transport.consumer(1000,true,imagery=true);synchronized(this){predictions[resource]=consumer};predictionCount++;predictionRecent.addLast(now)
            consumer.acquire(resource).whenComplete{_,_->consumer.close();synchronized(this){predictions.remove(resource,consumer)}}
        }
    }
    private fun scheduleIdle(key:DemandPlanKey,revision:Long){
        if(!prepareAhead||offlineOnly||preparedRevision==revision||idleWake!=null||view.gestureInProgress||jobs.values.any{!it.speculative}||wanted.any{!resident(it)&&!view.hasResidentCoverage(it.id.gpu(),it.geometry)})return
        idleWake=scheduler.schedule({
            idleWake=null
            // Poll completion on the GPU owner without drawing an otherwise idle frame.
            view.checkPreparationRoom{room->if(room&&!dead)runCatching{scheduler.execute{prepareIdle(key,revision)}}}
        },650,TimeUnit.MILLISECONDS)
    }
    private fun prepareIdle(key:DemandPlanKey,revision:Long){
            if(!prepareAhead||revision!=viewRevision||preparedRevision==revision||dead||!active||!view.canPrepareAhead||jobs.values.any{!it.speculative}||wanted.any{!resident(it)&&!view.hasResidentCoverage(it.id.gpu(),it.geometry)})return
            preparedRevision=revision
            val extras=LinkedHashSet<Source>()
            for(geometry in listOf(true,false)){
                planBuffer.rewind();val pose=key.pose
                val count=NativeBridge.plan(key.width,key.height,pose.longitude,pose.latitude,pose.zoom,pose.bearing,pose.pitch,pose.elevation,
                    if(geometry)geometryLimit else imageLimit,DetailPolicy.target(geometry,if(geometry)key.terrainDetail else key.imageryDetail),if(geometry)22 else 19,1.3,planBuffer)
                val rows=List(count){Id(planBuffer.int,planBuffer.int,planBuffer.int)}
                extras.addAll(rows.map{Source(it,geometry)}.filter{it !in wanted&&!resident(it)}.take(2))
            }
            // One small idle batch; speculative upload ownership never enters draw demand.
            warmWanted=extras;view.updateWarmDemand(extras.filter{it.geometry}.map{it.id.gpu()}.toSet(),extras.filter{!it.geometry}.map{it.id.gpu()}.toSet())
            for(source in extras){val readyData=synchronized(ready){ready[source]};if(readyData!=null)upload(source,readyData)else schedule(source,1000,true)}
    }
    private val backend by lazy{HttpTileBackend(client,
        escapeRead={escaped.get(it)},
        escapeWrite={resource,body->if(escapedBytes>0&&!escapedWrites.isShutdown)runCatching{escapedWrites.execute{escaped.put(resource,body)}}},
        offlineRead=offline::read)}
    private val transport by lazy{TileTransport(backend,ramBudget.encoded,initialLimits=downloadLimits)}
    fun loadingDiagnostics():String {
        val counts=transport.stats();val times=transport.latencyStats()
        val http=backend.stats()
        val limits=transport.limitStats()
        val local=localImages.stats()
        return "Native loading snapshot\n"+
            "Download limits moving/stationary/background: ${limits["movingLimit"]}/${limits["stationaryLimit"]}/${limits["backgroundLimit"]} · current foreground: ${limits["foregroundLimit"]}\n"+
            "HTTP active/queued: ${counts["networkActive"]}/${counts["networkQueued"]} · local reads queued: ${counts["localQueued"]}\n"+
            "Underlying HTTP running/queued: ${http["httpRunning"]}/${http["httpQueued"]} · canceled but still occupying slots: ${http["retiredHttpRunning"]}/${http["retiredHttpQueued"]}\n"+
            "Isolated escape HTTP running/queued: ${http["escapeRunning"]}/${http["escapeQueued"]}\n"+
            "Oldest active HTTP: ${times["oldestActiveHttpMs"]} ms · waiting source: ${times["oldestWaitingMs"]} ms\n"+
            "Last HTTP stage: ${times["lastHttpMs"]} ms · max this session: ${times["maxHttpMs"]} ms\n"+
            "Last local/queue wait before HTTP: ${times["lastAdmissionMs"]} ms\n"+
            "Geometry CPU active/queued: ${geometryWorkers.activeCount}/${geometryWorkers.queue.size}\n"+
            "Imagery CPU active/queued: ${imageWorkers.activeCount}/${imageWorkers.queue.size}\n"+
            "Original imagery I/O active/queued: ${local["active"]}/${local["queued"]} · oldest ${local["oldestMs"]} ms · last/max read ${local["lastMs"]}/${local["maxMs"]} ms\n"+
            "Tiles in progress: ${jobs.size} · retry backoffs: ${failures.size}\n"+
            "Late prepared-cache wins: ${latePreparedWins.get()}\n"+
            "RAM/disk hits: ${counts["ramHits"]}/${counts["localHits"]} · cache escape starts: ${counts["uncachedStarts"]}\n"+
            view.loadingSnapshot()+"\nHTTP stage includes cache, connection, provider and body transfer; it does not isolate Wi-Fi latency."
    }
    private fun resourceKey(url:String)=ResourceKey(url,offlineOnly&&regionContext.get()==null)
    private fun awaitInputs(urls:List<String>,local:List<NativeImagery.LocalInput> = emptyList()){
        val task=taskContext.get()?:return
        // Admit independent HTTP and original local reads together, before decode.
        val futures=urls.distinct().map{task.consumer.acquire(resourceKey(it))}+local.map{task.localInput(it.key,it.read)}
        if(futures.any{!it.isDone})throw ResourcePending(CompletableFuture.allOf(*futures.toTypedArray()))
    }
    private fun localImage(key:String,read:()->ByteArray?):ByteArray? {
        val task=taskContext.get()?:return read() // Offline preparation owns its separate worker.
        checkPreparation();val future=task.localInput(key,read)
        if(!future.isDone)throw ResourcePending(future)
        return try{future.getNow(null).also{checkPreparation()}}
        catch(e:CompletionException){throw(e.cause as? Exception?:IOException("Original imagery unavailable"))}
    }
    private fun fetchRecord(url:String):TileBody{
        check(!dead);val current=taskContext.get();val regionLease=regionSources.get()
        val owned=current?.consumer?:regionLease?:transport.consumer(if(regionContext.get()!=null)1000 else 16,regionContext.get()!=null)
        val downloading=current==null&&regionContext.get()!=null
        if(downloading){downloadLease=owned;if(downloadCancel.get()){owned.release(true);throw PreparationAborted()}}
        try{
            val future=owned.acquire(resourceKey(url))
            if(current!=null&&!future.isDone)throw ResourcePending(future)
            val body=try{future.get()}catch(e:ExecutionException){throw(e.cause as? Exception?:IOException("Shared tile request failed"))}
            body.stamp?.let{sourceRecords.get()?.set(it.key,it)}
            return body
        }finally{if(current==null&&regionLease==null)owned.close();if(downloading&&downloadLease===owned)downloadLease=null}
    }
    private fun fetch(url:String):ByteArray{
        val body=fetchRecord(url);if(body.status !in 200..299)throw TileHttpException(body.status)
        regionContext.get()?.let{offline.keep(it,url,body.bytes)};return body.bytes
    }
    private fun dependencyFresh(dep:PreparedTerrainCache.Dependency)=transport.fresh(SourceStamp(dep.resourceKey,dep.fingerprint,dep.until))
    private fun imageryChanged(){
        for(task in jobs.values)if(!task.source.geometry)task.cancel()
        clearWarm()
        synchronized(compositionLock){preferences.edit().putLong("imageryGeneration",preferences.getLong("imageryGeneration",0)+1).apply();compositionStore=null}
        synchronized(ready){val iter=ready.entries.iterator();while(iter.hasNext()){val row=iter.next();if(!row.key.geometry){readyBytes-=row.value.capacity();iter.remove()}}}
        transport.invalidate();failures.clear();cameraChanged()
    }
    private fun demPixels(data: ByteArray): FloatArray {
        val options = BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: throw IOException("DEM decode failed")
        try {
            require(bitmap.width == 256 && bitmap.height == 256)
            val colors = IntArray(65536); bitmap.getPixels(colors, 0, 256, 0, 0, 256, 256)
            return FloatArray(colors.size) { i ->
                if (Color.alpha(colors[i]) == 0) Float.NaN else (-10000 + (colors[i] and 0xffffff) * .1).toFloat()
            }
        } finally { bitmap.recycle() }
    }
    private fun sample(record:Dem,gx:Double,gy:Double,z:Int)=DemSampling.sample(record,gx,gy,z)
    private fun raw(id:Id):Dem {
        val record=rawRecord(id)
        geometryDependencies.get()?.let{dependencies->for(dep in record.dependencies)dependencies[dep.id]=dep}
        return record.raster
    }
    private fun rawRecord(id:Id):Decoded{
        val task=taskContext.get()
        if(task?.pipelineEligible()==true)task.inputs.get(id)?.let{return it}
        val record=loadRawRecord(id)
        return if(task?.pipelineEligible()==true)task.inputs.remember(id,record)else record
    }
    private fun loadRawRecord(id:Id):Decoded{
        val old=synchronized(decoded){decoded[id]}
        if(old!=null&&old.dependencies.all(::dependencyFresh))return old
        val body=fetchRecord(linz("elevation",id,"png"))
        if(body.status!=200&&body.status!=404)throw TileHttpException(body.status)
        if(body.status==404&&id.z==0)throw TileHttpException(404)
        val stamp=body.stamp!!
        val dependency=PreparedTerrainCache.Dependency(id.gpu(),stamp.fingerprint,stamp.until,stamp.key)
        if(old!=null&&old.dependencies.firstOrNull()?.fingerprint==stamp.fingerprint&&old.dependencies.drop(1).all(::dependencyFresh)){
            return old.copy(dependencies=listOf(dependency)+old.dependencies.drop(1)).also{synchronized(decoded){decoded[id]=it;trimDecoded()}}
        }
        // Fetch/freshness are consumer-owned. Only the immutable CPU PNG
        // decode is shared; missing-pixel parent repair remains private.
        val sharedPixels=if(body.status==404)null else decoding.get(id to stamp.fingerprint,taskContext.get()?.decodeWait){demPixels(body.bytes)}
        checkPreparation()
        val missing=sharedPixels?.any{!it.isFinite()}==true
        val pixels=if(missing)sharedPixels!!.copyOf()else sharedPixels
        val dependencies=LinkedHashMap<GpuUploadQueue.Id,PreparedTerrainCache.Dependency>();dependencies[dependency.id]=dependency
        fun parent():Dem{val row=rawRecord(id.parent());for(dep in row.dependencies)dependencies[dep.id]=dep;return row.raster}
        val raster=if(pixels==null)parent()else{
            if(missing){check(id.z>0);val coarse=parent()
                for(y in 0..255)for(x in 0..255)if(!pixels[y*256+x].isFinite())pixels[y*256+x]=sample(coarse,id.x*256.0+x,id.y*256.0+y,id.z)}
            Dem(id.gpu(),pixels)
        }
        return Decoded(raster,dependencies.values.toList()).also{synchronized(decoded){decoded[id]=it;trimDecoded()}}
    }
    private fun loadGeometry(id: Id): ByteBuffer {
        checkPreparation()
        // Derived files are optional. Do not let their index, read or gzip
        // delay source admission or occupy the two geometry workers. Expired
        // provenance takes the normal source path instead of awaiting sources
        // from inside an open derived file.
        val cached=taskContext.get()?.derived{prepared.getEntry(id.gpu(),::dependencyFresh)}
        checkPreparation();if(cached!=null)return cached
        val footprint=DemSampling.footprint(id.z,id.x,id.y,DemSampling.terrainSourceZoom(id.z))
        val task=taskContext.get()
        if(task?.pipelineEligible()==true){
            // Bound early decoding to the focus backdrop and first two sharp
            // leaves. At most eight raster references per operation; cancellation
            // releases them. Remaining jobs keep ordinary all-input admission.
            task.inputs.prepare(footprint.map{Id(it.z,it.x,it.y)},request={input->
                checkPreparation()
                val old=synchronized(decoded){decoded[input]}
                if(old!=null&&old.dependencies.all(::dependencyFresh)){
                    task.inputs.remember(input,old);CompletableFuture.completedFuture(Unit)
                }else task.consumer.acquire(resourceKey(linz("elevation",input,"png")))
            },decode={input->checkPreparation();rawRecord(input)})
        }else{
            // Fresh decoded inputs need no encoded disk/network lookup.
            val missing=footprint.filter{input->
                val old=synchronized(decoded){decoded[Id(input.z,input.x,input.y)]}
                old==null||!old.dependencies.all(::dependencyFresh)
            }
            awaitInputs(missing.map{linz("elevation",Id(it.z,it.x,it.y),"png")})
        }
        val dependencies=LinkedHashMap<GpuUploadQueue.Id,PreparedTerrainCache.Dependency>();geometryDependencies.set(dependencies)
        val heights=ByteBuffer.allocateDirect(129*129*4+16).order(ByteOrder.LITTLE_ENDIAN)
        DemSampling.terrainMesh(id.z,id.x,id.y,heights,::checkPreparation){checkPreparation();raw(Id(it.z,it.x,it.y)).also{checkPreparation()}}
        checkPreparation()
        heights.putDouble(129*129*4,Double.NaN);heights.putDouble(129*129*4+8,Double.NaN)
        NativeBridge.terrainProfile(heights)
        checkPreparation()
        val provenance=dependencies.values.toList()
        if(provenance.all{it.until>System.currentTimeMillis()}&&!preparedWrites.isShutdown)runCatching{preparedWrites.execute{prepared.put(id.gpu(),heights,provenance)}}
        return heights
    }
    private fun loadImage(id:Id):ByteBuffer {
        val imageryGeneration=preferences.getLong("imageryGeneration",0)
        val composed=imagery.composed(id.z,id.x,id.y)
        if(composed)taskContext.get()?.derived{imageStore().getEntry(id.gpu(),::dependencyFresh)}?.let{return it}
        awaitInputs(imagery.inputs(id.z,id.x,id.y),imagery.localInputs(id.z,id.x,id.y))
        val image = imagery.load(id.z, id.x, id.y)
        checkPreparation()
        val rgba=PixelPacking.rgbaOwned(image,::checkPreparation)
        if(composed){val provenance=sourceRecords.get()?.values?.map{PreparedTerrainCache.Dependency(id.gpu(),it.fingerprint,it.until,it.key)}?:emptyList()
            if(provenance.all{it.until>System.currentTimeMillis()}&&!preparedWrites.isShutdown)runCatching{preparedWrites.execute{
                // Store initialization may still be running on a slow reader.
                // Keep that wait on the cache executor, never a decode worker.
                val store=synchronized(compositionLock){if(preferences.getLong("imageryGeneration",0)==imageryGeneration)imageStore()else null}
                store?.put(id.gpu(),rgba,provenance)
            }}}
        return rgba
    }
    data class Bounds(val west:Double,val south:Double,val east:Double,val north:Double)
    private fun regionTiles(bounds:Bounds,maxZoom:Int):List<Id>{
        require(maxZoom in 8..19&&bounds.west>=165&&bounds.east<=180&&bounds.south>=-49&&bounds.north<=-33&&bounds.west<bounds.east&&bounds.south<bounds.north){"Choose a valid New Zealand region."}
        val result=ArrayList<Id>()
        for(z in 0..maxZoom){
            val n=1 shl z
            fun x(v:Double)=floor((v+180)/360*n).toInt().coerceIn(0,n-1)
            fun y(v:Double)=floor((1-asinh(tan(Math.toRadians(v)))/PI)/2*n).toInt().coerceIn(0,n-1)
            val x0=x(bounds.west);val x1=x(bounds.east);val y0=y(bounds.north);val y1=y(bounds.south)
            require(result.size.toLong()+(x1-x0+1).toLong()*(y1-y0+1)<=2000){"Choose a smaller region or lower offline detail (2,000 image tiles per region)."}
            for(yy in y0..y1)for(xx in x0..x1)result.add(Id(z,xx,yy))
        };return result
    }
    fun regionEstimate(bounds:Bounds,maxZoom:Int)=regionTiles(bounds,maxZoom).size
    fun regions(callback:(List<OfflineStore.Region>)->Unit){offlineQueries.execute{val rows=offline.regions();view.post{if(!dead)callback(rows)}}}
    fun removeRegion(id:String,callback:()->Unit){downloadJobs.execute{offline.delete(id);view.post{if(!dead)callback()}}}
    fun cancelDownload(){downloadCancel.set(true);regionPrefetch?.close();downloadLease?.release(true)}
    fun downloadRegion(name:String,bounds:Bounds,maxZoom:Int,progress:(String)->Unit,done:()->Unit){
        val tiles=regionTiles(bounds,maxZoom);downloadCancel.set(false)
        downloadJobs.execute{
            val region=offline.begin(name);regionContext.set(region);val demDone=HashSet<Id>()
            val prefetch=OfflinePrefetch(transport);regionPrefetch=prefetch
            fun checkActive(){check(!dead&&!downloadCancel.get()){ "Download cancelled" }}
            fun inputs(id:Id):List<ResourceKey>{
                val urls=ArrayList(imagery.inputs(id.z,id.x,id.y))
                val dz=min(id.z,18);val f=1 shl(id.z-dz);val n=1 shl dz
                for(dy in -1..1)for(dx in -1..1){val dem=Id(dz,Math.floorMod(id.x/f+dx,n),(id.y/f+dy).coerceIn(0,n-1));if(dem !in demDone)urls+=linz("elevation",dem,"png")}
                return urls.distinct().map{ResourceKey(it,false)}
            }
            fun dem(id:Id){
                if(!demDone.add(id))return;checkActive()
                val pixels=try{demPixels(fetch(linz("elevation",id,"png")))}catch(e:TileHttpException){if(e.code==404&&id.z>0)null else throw e}
                if((pixels==null||pixels.any{!it.isFinite()})&&id.z>0)dem(id.parent())
            }
            try{
                for((index,id)in tiles.withIndex()){
                    checkActive()
                    regionSources.set(prefetch.advance(index,tiles.subList(index,minOf(index+2,tiles.size)).map(::inputs)))
                    imagery.load(id.z,id.x,id.y)
                    val dz=min(id.z,18);val f=1 shl(id.z-dz);val n=1 shl dz
                    for(dy in -1..1)for(dx in -1..1)dem(Id(dz,Math.floorMod(id.x/f+dx,n),(id.y/f+dy).coerceIn(0,n-1)))
                    if(index%5==0||index==tiles.lastIndex)view.post{if(!dead)progress("${index+1}/${tiles.size} image tiles · terrain included")}
                }
                offline.status(region,"Ready")
            }catch(e:Exception){offline.status(region,if(downloadCancel.get()||dead)"Partial · cancelled"else"Partial · source unavailable")}
            finally{regionSources.remove();prefetch.close();if(regionPrefetch===prefetch)regionPrefetch=null;regionContext.remove();view.post{if(!dead)done()}}
        }
    }
    private val profileJobs=Executors.newSingleThreadScheduledExecutor()
    private var profileJob:Future<*>?=null
    private val profileRevision=AtomicLong()
    fun profile(points:List<Coordinate>,callback:(List<NativeGeo.ProfilePoint>)->Unit){
        val revision=profileRevision.incrementAndGet();profileJob?.cancel(true)
        val samples=NativeGeo.samples(points)
        profileJob=profileJobs.schedule({
            val result=ArrayList<NativeGeo.ProfilePoint>(samples.size)
            for(p in samples){
                if(dead||revision!=profileRevision.get()||Thread.currentThread().isInterrupted)return@schedule
                val mx=(p.point.longitude+180)/360
                val my=(1-asinh(tan(Math.toRadians(p.point.latitude)))/PI)/2
                val height=try{DemSampling.point(14,mx,my){raw(Id(it.z,it.x,it.y))}?.toDouble()}catch(_:Exception){null}
                result.add(p.copy(height=height))
            }
            view.post{if(!dead&&revision==profileRevision.get())callback(result)}
        },350,TimeUnit.MILLISECONDS)
    }
    fun memoryPressure(){
        pipelinePauseUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        for(task in jobs.values){task.inputs.clear();task.decodeWait.clear()}
        synchronized(ready){val it=ready.entries.iterator();while(it.hasNext()){val row=it.next();if(row.key !in wanted){readyBytes-=row.value.capacity();it.remove()}}}
        transport.memoryPressure();clearWarm()
        synchronized(decoded){while(decoded.size>minOf(16,demLimit))decoded.remove(decoded.keys.first())}
    }
    fun setActive(value:Boolean){active=value;if(value)cameraChanged()else{transport.batchDemand{for(task in jobs.values)task.cancel();clearWarm()}}}
    fun close() {
        escapedWrites.shutdown()
        derivedReads.close()
        dead = true;transport.batchDemand{for(task in jobs.values)task.cancel();clearWarm();transport.close()};localImages.close();preparedWrites.shutdownNow();attachmentJobs.shutdown();downloadCancel.set(true);downloadJobs.shutdown();offlineQueries.shutdownNow(); profileRevision.incrementAndGet();profileJob?.cancel(true);profileJobs.shutdownNow();timer?.cancel(true); scheduler.shutdownNow(); geometryWorkers.shutdownNow();imageWorkers.shutdownNow()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); synchronized(ready) { ready.clear(); readyBytes=0 }; synchronized(decoded) { decoded.clear() }
        Thread({downloadJobs.awaitTermination(60,TimeUnit.SECONDS);offlineQueries.awaitTermination(5,TimeUnit.SECONDS);profileJobs.awaitTermination(60,TimeUnit.SECONDS);geometryWorkers.awaitTermination(60,TimeUnit.SECONDS);imageWorkers.awaitTermination(60,TimeUnit.SECONDS);attachmentJobs.awaitTermination(60,TimeUnit.SECONDS);localImages.awaitClosed();transport.awaitClosed();runCatching{overviews.close()};runCatching{offline.close()};runCatching{client.cache?.close()}},"ridgeline-offline-close").start()
    }
    init{
        imageryReady.whenComplete{_,error->if(error==null)cameraChanged()else view.post{if(!dead)view.onStatus("Imagery metadata unavailable")}}
        view.onInvalidUpload={tile->
            for(geometry in listOf(true,false)){
                if(tile.kind==GpuUploadQueue.Kind.GEOMETRY&&!geometry||tile.kind==GpuUploadQueue.Kind.IMAGE&&geometry)continue
                val source=Source(Id(tile.id.z,tile.id.x,tile.id.y),geometry)
                val bytes=if(geometry)tile.heights else tile.rgba
                val removed=synchronized(ready){if(ready[source]===bytes){readyBytes-=ready.remove(source)!!.capacity();true}else false}
                // Do not re-offer the same permanently invalid buffer each frame.
                if(removed)failures[source]=Failure(1,Long.MAX_VALUE)
            }
            view.onStatus("Invalid prepared tile; see loading diagnostics")
        }
    }
}
