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
    val automaticCacheBytes=minOf(1_000_000_000L,(context.cacheDir.usableSpace/8).coerceAtLeast(4_194_304L))
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "native-http-v1"), automaticCacheBytes))
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val key = context.assets.open("map-defaults.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")
    }
    private fun linz(kind: String, id: Id, ext: String): String =
        "https://basemaps.linz.govt.nz/v1/tiles/$kind/WebMercatorQuad/${id.z}/${id.x}/${id.y}.$ext?" +
            (if (kind == "elevation") "pipeline=terrain-rgb&" else "") + "api=$key"
    private val offline=OfflineStore(context)
    private val downloadJobs=Executors.newSingleThreadExecutor()
    private val regionContext=ThreadLocal<String>()
    private val downloadCancel=java.util.concurrent.atomic.AtomicBoolean(false)
    private val preferences=context.getSharedPreferences("display",0)
    @Volatile var offlineOnly=preferences.getBoolean("offlineOnly",false);private set
    fun offlineOnly(value:Boolean){offlineOnly=value;preferences.edit().putBoolean("offlineOnly",value).apply();cameraChanged()}
    val overviews=OverviewAttachments(context)
    private val attachmentJobs=Executors.newSingleThreadExecutor()
    private val imagery = NativeImagery(context, ::fetch, {z,x,y->linz("aerial",Id(z,x,y),"webp")},overviews)
    fun attachOverview(uri:android.net.Uri,done:(String?)->Unit){attachmentJobs.execute{
        val error=try{overviews.attach(uri);failures.clear();cameraChanged();null}catch(e:Exception){e.message?:"Could not attach imagery ZIP."}
        view.post{if(!dead)done(error)}
    }}
    fun detachOverview(payload:String,done:()->Unit){attachmentJobs.execute{overviews.detach(payload);view.post{if(!dead)done()}}}
    private val am = context.getSystemService(ActivityManager::class.java)
    private val imageLimit = if (am.isLowRamDevice) 32 else (am.memoryClass / 2).coerceIn(64, 128)
    private val geometryLimit = (imageLimit/2).coerceIn(16,96)
    private val readyByteLimit = imageLimit * 256*256*4 + geometryLimit * (129*129*4+16)
    private var readyBytes = 0
    private val demLimit = if (am.isLowRamDevice) 24 else (am.memoryClass / 2).coerceIn(48, 128)
    private val ready = LinkedHashMap<Source, ByteBuffer>(16, .75f, true)
    private val decoded = LinkedHashMap<Id, Dem>(16, .75f, true)
    private val demJobs = ConcurrentHashMap<Id, CompletableFuture<Dem>>()
    private val jobs = ConcurrentHashMap<Source, Task>()
    private val sequence = AtomicLong()
    private data class Plan(val ranked:Map<Source,Int>)
    private val plans=DemandPlanCache<DemandPlanKey,Plan>()
    private val planBuffer=ByteBuffer.allocateDirect(512*12).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var dead = false
    @Volatile private var active = true
    @Volatile private var wanted = emptySet<Source>()
    private var timer: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
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
    private inner class Task(val source: Source, val priority: Int): Runnable, Comparable<Task> {
        private val order = sequence.getAndIncrement()
        override fun compareTo(other: Task) = compareValuesBy(this, other, { it.priority }, { it.order })
        override fun run() {
            try {
                if (dead || source !in wanted || resident(source)) return
                val data = if(source.geometry)loadGeometry(source.id)else loadImage(source.id)
                failures.remove(source)
                if (!dead) {
                    synchronized(ready) {
                        ready.put(source,data)?.let{readyBytes-=it.capacity()}
                        readyBytes+=data.capacity()
                        while(readyBytes>readyByteLimit){
                            val first=ready.keys.firstOrNull{it !in wanted}?:ready.keys.first()
                            readyBytes-=ready.remove(first)!!.capacity()
                        }
                    }
                    if (source in wanted&&!resident(source)) upload(source,data)
                }
            } catch (e: Exception) {
                val attempt=(failures[source]?.attempts?:0)+1
                val delay=when(attempt){1->1000L;2->4000L;else->15000L}
                failures[source]=Failure(attempt,android.os.SystemClock.elapsedRealtime()+delay)
                if(!dead&&!offlineOnly&&source in wanted&&attempt<=3)runCatching{scheduler.schedule({cameraChanged()},delay,TimeUnit.MILLISECONDS)}
                if (!dead && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.w("RidgelineSource","Load failed: ${e.javaClass.simpleName}; cause=${e.cause?.javaClass?.simpleName}")
                if (!dead && source in wanted) view.post { view.onStatus("Terrain load failed: " +
                    (if (e is TileHttpException) "provider HTTP ${e.code}" else "source unavailable")) }
            } finally { jobs.remove(source, this) }
        }
    }
    fun cameraChanged() = synchronized(this) {
        // Throttle using the latest immutable camera; a continuous drag must not
        // indefinitely postpone source selection, as a cancel/reschedule debounce did.
        if(!dead&&active&&timer==null)timer=scheduler.schedule({
            synchronized(this){timer=null}
            loadView()
        },50,TimeUnit.MILLISECONDS)
    }
    private fun sourcePlan(geometry:Boolean,key:DemandPlanKey):List<Id> {
        val pose=key.pose
        val output=planBuffer;output.rewind()
        val budget=maxOf(16,(if(geometry)geometryLimit else imageLimit)*3/4)
        val count=NativeBridge.plan(key.width,key.height,pose.longitude,pose.latitude,
            pose.zoom,pose.bearing,pose.pitch,pose.elevation,budget,
            if(geometry)431.0 else 256.0/2.0.pow(.25),if(geometry)22 else 19,output)
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
        view.updateUploadDemand(wanted.filter{it.geometry}.map{it.id.gpu()}.toSet(),
            wanted.filter{!it.geometry}.map{it.id.gpu()}.toSet())
        failures.keys.removeAll{it !in wanted}
        // Queued tasks must inherit the latest screen priority after a pan.
        for ((source, task) in jobs) if ((source !in wanted||ranked[source]!=task.priority) && workers(source).remove(task)) jobs.remove(source, task)
        for ((source, priority) in ranked) {
            if(resident(source))continue
            val old = synchronized(ready) { ready[source] }
            if (old != null) { upload(source,old); continue }
            val failure=failures[source]
            if(failure!=null&&android.os.SystemClock.elapsedRealtime()<failure.retryAt)continue
            val task = Task(source, priority)
            if (jobs.putIfAbsent(source, task) == null && !dead) {
                try{workers(source).execute(task)}catch(_:RejectedExecutionException){jobs.remove(source,task)}
            }
        }
    }
    private data class FetchKey(val url:String,val offline:Boolean)
    private val bodyJobs=ConcurrentHashMap<FetchKey,CompletableFuture<ByteArray>>()
    private fun fetch(url:String):ByteArray{
        check(!dead)
        val key=FetchKey(url,offlineOnly&&regionContext.get()==null)
        val future=CompletableFuture<ByteArray>();val existing=bodyJobs.putIfAbsent(key,future)
        val bytes=if(existing!=null){
            try{existing.get()}catch(e:ExecutionException){throw(e.cause as? Exception?:IOException("Shared tile request failed"))}
        }else try{
            fetchBody(url,key.offline).also{future.complete(it)}
        }catch(e:Exception){future.completeExceptionally(e);throw e}
        finally{bodyJobs.remove(key,future)}
        regionContext.get()?.let{offline.keep(it,url,bytes)}
        return bytes
    }
    private fun fetchBody(url: String,cacheOnly:Boolean): ByteArray {
        check(!dead)
        offline.read(url)?.let{return it}
        val request = Request.Builder().url(url).header("User-Agent", "Ridgeline-Native/0.1.0").apply{
            if(cacheOnly)cacheControl(CacheControl.FORCE_CACHE)
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TileHttpException(response.code)
            val body = response.body ?: throw IOException("Empty tile")
            // Bound the stream itself, including chunked responses, before allocation grows.
            require(body.contentLength() <= 8 * 1024 * 1024)
            val source = body.source()
            val sink = okio.Buffer()
            while (true) {
                val remaining = 8L * 1024 * 1024 + 1 - sink.size
                check(remaining > 0) { "Oversized tile" }
                if (source.read(sink, minOf(8192L, remaining)) == -1L) break
            }
            require(sink.size <= 8 * 1024 * 1024)
            return sink.readByteArray()
        }
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
    private fun raw(id: Id): Dem {
        synchronized(decoded) { decoded[id]?.let { return it } }
        val future = CompletableFuture<Dem>(); val existing = demJobs.putIfAbsent(id, future)
        if (existing != null) return existing.get()
        try {
            val pixels = try { demPixels(fetch(linz("elevation", id, "png"))) }
                catch (e: TileHttpException) { if (e.code == 404 && id.z > 0) null else throw e }
            val record = if (pixels == null) raw(id.parent()) else {
                if (pixels.any { !it.isFinite() }) {
                    check(id.z > 0) { "Missing root elevation" }
                    val parent = raw(id.parent())
                    for (y in 0..255) for (x in 0..255) if (!pixels[y * 256 + x].isFinite())
                        pixels[y * 256 + x] = sample(parent, id.x * 256.0 + x, id.y * 256.0 + y, id.z)
                }
                Dem(id.gpu(), pixels)
            }
            synchronized(decoded) {
                decoded[id] = record
                while (decoded.size > demLimit) decoded.remove(decoded.keys.first())
            }
            future.complete(record); return record
        } catch (e: Exception) { future.completeExceptionally(e); throw e }
        finally { demJobs.remove(id, future) }
    }
    private fun loadGeometry(id: Id): ByteBuffer {
        val heights=ByteBuffer.allocateDirect(129*129*4+16).order(ByteOrder.LITTLE_ENDIAN)
        DemSampling.terrainMesh(id.z,id.x,id.y,heights){raw(Id(it.z,it.x,it.y))}
        heights.putDouble(129*129*4,Double.NaN);heights.putDouble(129*129*4+8,Double.NaN)
        NativeBridge.terrainProfile(heights)
        return heights
    }
    private fun loadImage(id:Id):ByteBuffer {
        val image = imagery.load(id.z, id.x, id.y)
        val rgba = ByteBuffer.allocateDirect(256 * 256 * 4)
        for (p in image) {
            val a = Color.alpha(p)
            // The reference shader receives premultiplied textures and unpremultiplies before grading.
            rgba.put(((Color.red(p) * a + 127) / 255).toByte())
            rgba.put(((Color.green(p) * a + 127) / 255).toByte())
            rgba.put(((Color.blue(p) * a + 127) / 255).toByte()); rgba.put(a.toByte())
        }
        rgba.rewind(); return rgba
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
    fun cancelDownload(){downloadCancel.set(true)}
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
        synchronized(decoded){while(decoded.size>minOf(16,demLimit))decoded.remove(decoded.keys.first())}
    }
    fun setActive(value:Boolean){active=value;if(value)cameraChanged()}
    fun close() {
        dead = true;attachmentJobs.shutdown();downloadCancel.set(true);downloadJobs.shutdown(); profileRevision.incrementAndGet();profileJob?.cancel(true);profileJobs.shutdownNow();timer?.cancel(true); scheduler.shutdownNow(); geometryWorkers.shutdownNow();imageWorkers.shutdownNow()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.cache?.close(); client.dispatcher.executorService.shutdown(); synchronized(ready) { ready.clear(); readyBytes=0 }; synchronized(decoded) { decoded.clear() }
        Thread({downloadJobs.awaitTermination(60,TimeUnit.SECONDS);profileJobs.awaitTermination(60,TimeUnit.SECONDS);geometryWorkers.awaitTermination(60,TimeUnit.SECONDS);imageWorkers.awaitTermination(60,TimeUnit.SECONDS);attachmentJobs.awaitTermination(60,TimeUnit.SECONDS);overviews.close();offline.close()},"ridgeline-offline-close").start()
    }
}
