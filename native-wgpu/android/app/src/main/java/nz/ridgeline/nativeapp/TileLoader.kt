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
    val automaticCacheMiB get()=preferences.getInt("automaticCacheMiB",1000).coerceIn(0,1000)
    fun automaticCacheMiB(value:Int){preferences.edit().putInt("automaticCacheMiB",value.coerceIn(0,1000)).apply()}
    val automaticCacheBytes=AutomaticCacheBudget.bytes(automaticCacheMiB,context.cacheDir.usableSpace)
    private val preparedBytes=minOf(128L*1024*1024,automaticCacheBytes/8)
    private val compositionBytes=preparedBytes/2
    private val preparedWrites=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(8),ThreadFactory{work->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);work.run()},"RidgelinePreparedCache")},ThreadPoolExecutor.DiscardOldestPolicy())
    private val client = OkHttpClient.Builder()
        .cache(if(automaticCacheBytes>0)Cache(File(context.cacheDir, "native-http-v1"), automaticCacheBytes-preparedBytes)else null)
        .dispatcher(Dispatcher().apply{maxRequests=32;maxRequestsPerHost=16})
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    init {
        if(automaticCacheBytes==0L)preparedWrites.execute {
            // No HTTP cache is open and derived stores have a zero write limit.
            for(name in listOf("native-http-v1","native-terrain-v3","native-compositions-v1"))
                runCatching{File(context.cacheDir,name).deleteRecursively()}
        }
    }
    private val key = context.assets.open("map-defaults.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")
    }
    private val prepared by lazy{PreparedTerrainCache(File(context.cacheDir,"native-terrain-v3"),preparedBytes-compositionBytes,"terrain-source-delta1-dem18-grid128-profile-v2:$key")}
    private val preparation=PreparationControl()
    private fun checkPreparation()=preparation.checkpoint()
    private val geometryDependencies=ThreadLocal<MutableMap<GpuUploadQueue.Id,PreparedTerrainCache.Dependency>>()
    private fun linz(kind: String, id: Id, ext: String): String =
        "https://basemaps.linz.govt.nz/v1/tiles/$kind/WebMercatorQuad/${id.z}/${id.x}/${id.y}.$ext?" +
            (if (kind == "elevation") "pipeline=terrain-rgb&" else "") + "api=$key"
    private val offline=OfflineStore(context)
    private val downloadJobs=Executors.newSingleThreadExecutor()
    private val regionContext=ThreadLocal<String>()
    @Volatile private var downloadLease:TileTransport.Consumer?=null
    private val downloadCancel=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var offlineOnly=preferences.getBoolean("offlineOnly",false);private set
    val textureBackend get()=preferences.getInt("textureBackend",0)
    fun textureBackend(mode:Int)=view.textureBackend(mode)
    fun offlineOnly(value:Boolean){offlineOnly=value;preferences.edit().putBoolean("offlineOnly",value).apply();for(task in jobs.values)task.cancel();clearWarm();synchronized(ready){ready.clear();readyBytes=0};synchronized(decoded){decoded.clear()};cameraChanged()}
    val overviews=OverviewAttachments(context)
    private val attachmentJobs=Executors.newSingleThreadExecutor()
    private val imagery = NativeImagery(context, ::fetch, {z,x,y->linz("aerial",Id(z,x,y),"webp")},overviews,::checkPreparation)
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
    private val taskContext=ThreadLocal<Task>()
    private val sourceRecords=ThreadLocal<MutableMap<String,SourceStamp>>()
    private val jobs = ConcurrentHashMap<Source, Task>()
    private val sequence = AtomicLong()
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
    private var viewRevision=0L
    private var preparedRevision=-1L
    private var idleWake:ScheduledFuture<*>?=null
    private val predictions=LinkedHashMap<ResourceKey,TileTransport.Consumer>()
    private var predictionAt=0L
    private val predictionRecent=java.util.ArrayDeque<Long>()
    private var predictionCount=0
    private var lastGesture=false
    private var compositionStore:PreparedTerrainCache?=null
    private fun imageStore():PreparedTerrainCache=synchronized(this){compositionStore?:PreparedTerrainCache(File(context.cacheDir,"native-compositions-v1"),compositionBytes,"rgba-premultiplied-gap-v1:$key:"+preferences.getLong("imageryGeneration",0),256*256*4).also{compositionStore=it}}
    // DEM halo work cannot occupy every imagery worker. Four bounded requests,
    // shared DEM futures, and no per-halo executor multiplication.
    private val geometryWorkers = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private val imageWorkers = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
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
        val consumer=transport.consumer(priority,speculative)
        val records=LinkedHashMap<String,SourceStamp>()
        @Volatile var started=false;private set
        @Volatile private var parkedUntil=0L
        fun eligible()=!dead&&active&&!finished.get()&&(source in wanted||source in warmWanted&&prepareAhead||parkedUntil>System.currentTimeMillis())
        override fun compareTo(other:Task)=compareValuesBy(this,other,{it.priority+(if(it.speculative)10000 else 0)},{it.order})
        fun enqueue(){if(!finished.get()&&!dead&&queued.compareAndSet(false,true))try{workers(source).execute(this)}catch(_:RejectedExecutionException){queued.set(false);cancel()}}
        fun promote(rank:Int){priority=rank;speculative=false;parkedUntil=0;consumer.promote(rank);if(workers(source).remove(this)){queued.set(false);enqueue()}}
        fun park(){if(parkedUntil!=0L)return;parkedUntil=System.currentTimeMillis()+600;scheduler.schedule({if(source !in wanted)cancel()},600,TimeUnit.MILLISECONDS)}
        fun cancel(){if(finished.compareAndSet(false,true)){workers(source).remove(this);jobs.remove(source,this);consumer.release(true)}}
        override fun run(){
            queued.set(false);if(!eligible()){cancel();return}
            var waiting:CompletableFuture<*>?=null
            try{
                started=true;preparation.enter{eligible()};taskContext.set(this);sourceRecords.set(records)
                val data=if(source.geometry)loadGeometry(source.id)else loadImage(source.id)
                checkPreparation();failures.remove(source)
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
                if(waiting!=null&&!finished.get())waiting.whenComplete{_,_->enqueue()} else{
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
        val count=NativeBridge.plan(key.width,key.height,pose.longitude,pose.latitude,
            pose.zoom,pose.bearing,pose.pitch,pose.elevation,budget,
            if(geometry)431.0 else 256.0/2.0.pow(.25),if(geometry)22 else 19,1.0,output)
        return List(count){Id(output.int,output.int,output.int)}
    }
    private fun loadView() {
        if (dead||!active||view.width==0||view.height==0) return
        val key=DemandPlanKey((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),
            (view.height/view.pixelScale).roundToInt().coerceAtLeast(1),view.planningPose)
        val plan=plans.get(key) {
          val next=LinkedHashMap<Source,Int>()
          for(geometry in listOf(true,false)) {
            val ids=sourcePlan(geometry,key)
            val demand=SourceDemand.bounded(ids.map{it.gpu()},if(geometry)geometryLimit else imageLimit)
            for((id,priority)in demand)next[Source(Id(id.z,id.x,id.y),geometry)]=priority
          }
          Plan(next.toMap())
        }
        val ranked=plan.ranked
        wanted = ranked.keys.toSet()
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
        view.updateUploadDemand(wanted.filter{it.geometry}.map{it.id.gpu()}.toSet(),
            wanted.filter{!it.geometry}.map{it.id.gpu()}.toSet())
        for((parent,data)in cachedParents)upload(parent,data)
        failures.keys.removeAll{it !in wanted}
        if(view.gestureInProgress&&!lastGesture){predictionCount=0;cancelPredictions()}
        lastGesture=view.gestureInProgress
        transport.movement(view.gestureInProgress)
        val returning=jobs.values.filter{it.source !in wanted&&!it.speculative&&it.started}.sortedBy{it.priority}.take(8).toSet()
        for(task in jobs.values){if(task.source in wanted)task.promote(ranked[task.source]?:0)else if(task.source in warmWanted&&prepareAhead&&!view.gestureInProgress){}else if(task !in returning)task.cancel()else task.park()}
        for((source,priority)in ranked){
            if(resident(source))continue
            val fallback=source in fallbackSources
            if(view.hasResidentCoverage(source.id.gpu(),source.geometry,!fallback))continue
            val old=synchronized(ready){ready[source]};if(old!=null){upload(source,old);continue}
            val failure=failures[source];if(failure!=null&&android.os.SystemClock.elapsedRealtime()<failure.retryAt)continue
            schedule(source,priority,false)
        }
        val prior=previousPose
        if(prior!=key.pose){viewRevision++;clearWarm(false);predict(prior,key);previousPose=key.pose}
        scheduleIdle(key,viewRevision)
    }
    private fun schedule(source:Source,priority:Int,speculative:Boolean){
        val task=Task(source,priority,speculative)
        if(jobs.putIfAbsent(source,task)==null)task.enqueue()else task.cancel()
    }
    @Synchronized private fun cancelPredictions(){for(consumer in predictions.values)consumer.release(true);predictions.clear()}
    @Synchronized private fun clearWarm(cancelPredictions:Boolean=true){
        idleWake?.cancel(false);idleWake=null;warmWanted=emptySet();view.updateWarmDemand(emptySet(),emptySet())
        for(task in jobs.values)if(task.speculative)task.cancel()
        if(cancelPredictions)cancelPredictions()
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
        }else sourcePlan(false,key.copy(pose=future)).filter{Source(it,false) !in wanted}.take(3)
        val urls=candidates.filter{it.z in 0..19}.flatMap{imagery.inputs(it.z,it.x,it.y)}.distinct().take(3)
        for(url in urls){if(predictionRecent.size>=60||predictionCount>=24)break
            val resource=resourceKey(url);if(transport.contains(resource)||synchronized(this){resource in predictions})continue
            val consumer=transport.consumer(1000,true);synchronized(this){predictions[resource]=consumer};predictionCount++;predictionRecent.addLast(now)
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
                    if(geometry)geometryLimit else imageLimit,if(geometry)431.0 else 256.0/2.0.pow(.25),if(geometry)22 else 19,1.3,planBuffer)
                val rows=List(count){Id(planBuffer.int,planBuffer.int,planBuffer.int)}
                extras.addAll(rows.map{Source(it,geometry)}.filter{it !in wanted&&!resident(it)}.take(2))
            }
            // One small idle batch; speculative upload ownership never enters draw demand.
            warmWanted=extras;view.updateWarmDemand(extras.filter{it.geometry}.map{it.id.gpu()}.toSet(),extras.filter{!it.geometry}.map{it.id.gpu()}.toSet())
            for(source in extras){val readyData=synchronized(ready){ready[source]};if(readyData!=null)upload(source,readyData)else schedule(source,1000,true)}
    }
    private val transport by lazy{TileTransport(HttpTileBackend(client,offline::read),ramBudget.encoded)}
    private fun resourceKey(url:String)=ResourceKey(url,offlineOnly&&regionContext.get()==null)
    private fun awaitInputs(urls:List<String>){
        val consumer=taskContext.get()?.consumer?:return
        val futures=urls.distinct().map{consumer.acquire(resourceKey(it))}
        if(futures.any{!it.isDone})throw ResourcePending(CompletableFuture.allOf(*futures.toTypedArray()))
    }
    private fun fetchRecord(url:String):TileBody{
        check(!dead);val current=taskContext.get();val owned=current?.consumer?:transport.consumer(if(regionContext.get()!=null)1000 else 16,regionContext.get()!=null)
        val downloading=current==null&&regionContext.get()!=null
        if(downloading){downloadLease=owned;if(downloadCancel.get()){owned.release(true);throw PreparationAborted()}}
        try{
            val future=owned.acquire(resourceKey(url))
            if(current!=null&&!future.isDone)throw ResourcePending(future)
            val body=try{future.get()}catch(e:ExecutionException){throw(e.cause as? Exception?:IOException("Shared tile request failed"))}
            body.stamp?.let{sourceRecords.get()?.set(it.key,it)}
            return body
        }finally{if(current==null)owned.close();if(downloading&&downloadLease===owned)downloadLease=null}
    }
    private fun fetch(url:String):ByteArray{
        val body=fetchRecord(url);if(body.status !in 200..299)throw TileHttpException(body.status)
        regionContext.get()?.let{offline.keep(it,url,body.bytes)};return body.bytes
    }
    private fun dependencyFresh(dep:PreparedTerrainCache.Dependency)=transport.fresh(SourceStamp(dep.resourceKey,dep.fingerprint,dep.until))
    private fun imageryChanged(){
        for(task in jobs.values)if(!task.source.geometry)task.cancel()
        clearWarm()
        synchronized(this){preferences.edit().putLong("imageryGeneration",preferences.getLong("imageryGeneration",0)+1).apply();compositionStore=null}
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
        val sharedPixels=if(body.status==404)null else decoding.get(id to stamp.fingerprint){demPixels(body.bytes)}
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
        val cached=prepared.getChecked(id.gpu()){dependencies->
            checkPreparation()
            val stale=dependencies.filter{!dependencyFresh(it)}
            awaitInputs(stale.map{linz("elevation",Id(it.id.z,it.id.x,it.id.y),"png")})
            dependencies.all{dep->if(dependencyFresh(dep))true else{val body=fetchRecord(linz("elevation",Id(dep.id.z,dep.id.x,dep.id.y),"png"));body.stamp?.fingerprint==dep.fingerprint&&body.until>System.currentTimeMillis()}}
        }
        checkPreparation();if(cached!=null)return cached
        awaitInputs(DemSampling.footprint(id.z,id.x,id.y,DemSampling.terrainSourceZoom(id.z)).map{linz("elevation",Id(it.z,it.x,it.y),"png")})
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
        val composed=imagery.composed(id.z,id.x,id.y)
        if(composed)imageStore().get(id.gpu()){dependencyFresh(it)}?.let{return it}
        awaitInputs(imagery.inputs(id.z,id.x,id.y))
        val image = imagery.load(id.z, id.x, id.y)
        checkPreparation()
        val rgba=PixelPacking.rgbaOwned(image,::checkPreparation)
        if(composed){val provenance=sourceRecords.get()?.values?.map{PreparedTerrainCache.Dependency(id.gpu(),it.fingerprint,it.until,it.key)}?:emptyList()
            if(provenance.all{it.until>System.currentTimeMillis()}&&!preparedWrites.isShutdown){val store=imageStore();runCatching{preparedWrites.execute{store.put(id.gpu(),rgba,provenance)}}}}
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
    fun regions(callback:(List<OfflineStore.Region>)->Unit){downloadJobs.execute{val rows=offline.regions();view.post{if(!dead)callback(rows)}}}
    fun removeRegion(id:String,callback:()->Unit){downloadJobs.execute{offline.delete(id);view.post{if(!dead)callback()}}}
    fun cancelDownload(){downloadCancel.set(true);downloadLease?.release(true)}
    fun downloadRegion(name:String,bounds:Bounds,maxZoom:Int,progress:(String)->Unit,done:()->Unit){
        val tiles=regionTiles(bounds,maxZoom);downloadCancel.set(false)
        downloadJobs.execute{
            val region=offline.begin(name);regionContext.set(region);val demDone=HashSet<Id>()
            fun checkActive(){check(!dead&&!downloadCancel.get()){ "Download cancelled" }}
            fun dem(id:Id){
                if(!demDone.add(id))return;checkActive()
                val pixels=try{demPixels(fetch(linz("elevation",id,"png")))}catch(e:TileHttpException){if(e.code==404&&id.z>0)null else throw e}
                if((pixels==null||pixels.any{!it.isFinite()})&&id.z>0)dem(id.parent())
            }
            try{
                for((index,id)in tiles.withIndex()){
                    checkActive();imagery.load(id.z,id.x,id.y)
                    val dz=min(id.z,18);val f=1 shl(id.z-dz);val n=1 shl dz
                    for(dy in -1..1)for(dx in -1..1)dem(Id(dz,Math.floorMod(id.x/f+dx,n),(id.y/f+dy).coerceIn(0,n-1)))
                    if(index%5==0||index==tiles.lastIndex)view.post{if(!dead)progress("${index+1}/${tiles.size} image tiles · terrain included")}
                }
                offline.status(region,"Ready")
            }catch(e:Exception){offline.status(region,if(downloadCancel.get()||dead)"Partial · cancelled"else"Partial · source unavailable")}
            finally{regionContext.remove();view.post{if(!dead)done()}}
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
        synchronized(ready){val it=ready.entries.iterator();while(it.hasNext()){val row=it.next();if(row.key !in wanted){readyBytes-=row.value.capacity();it.remove()}}}
        transport.memoryPressure();clearWarm()
        synchronized(decoded){while(decoded.size>minOf(16,demLimit))decoded.remove(decoded.keys.first())}
    }
    fun setActive(value:Boolean){active=value;if(value)cameraChanged()else{for(task in jobs.values)task.cancel();clearWarm()}}
    fun close() {
        dead = true;for(task in jobs.values)task.cancel();clearWarm();transport.close();preparedWrites.shutdownNow();attachmentJobs.shutdown();downloadCancel.set(true);downloadJobs.shutdown(); profileRevision.incrementAndGet();profileJob?.cancel(true);profileJobs.shutdownNow();timer?.cancel(true); scheduler.shutdownNow(); geometryWorkers.shutdownNow();imageWorkers.shutdownNow()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.cache?.close(); client.dispatcher.executorService.shutdown(); synchronized(ready) { ready.clear(); readyBytes=0 }; synchronized(decoded) { decoded.clear() }
        Thread({downloadJobs.awaitTermination(60,TimeUnit.SECONDS);profileJobs.awaitTermination(60,TimeUnit.SECONDS);geometryWorkers.awaitTermination(60,TimeUnit.SECONDS);imageWorkers.awaitTermination(60,TimeUnit.SECONDS);attachmentJobs.awaitTermination(60,TimeUnit.SECONDS);transport.awaitClosed();overviews.close();offline.close()},"ridgeline-offline-close").start()
    }
}
