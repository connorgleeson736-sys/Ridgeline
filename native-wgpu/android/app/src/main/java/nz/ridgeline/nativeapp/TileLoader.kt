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

internal class TileHttpException(val code:Int):IOException("Provider HTTP $code")

/** Native-source prototype; viewport/LOD parity and performance acceptance remain open. */
class TileLoader(private val context: Context, private val view: TerrainView) {
    private data class Id(val z: Int, val x: Int, val y: Int) {
        fun parent() = Id(z - 1, x / 2, y / 2)
        fun gpu() = GpuUploadQueue.Id(z,x,y)
    }
    private data class Dem(val id: Id, val samples: FloatArray)
    private data class Source(val id:Id,val geometry:Boolean)
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "native-http-v1"), 256_000_000))
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val key = context.assets.open("map-defaults.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")
    }
    private fun linz(kind: String, id: Id, ext: String): String =
        "https://basemaps.linz.govt.nz/v1/tiles/$kind/WebMercatorQuad/${id.z}/${id.x}/${id.y}.$ext?" +
            (if (kind == "elevation") "pipeline=terrain-rgb&" else "") + "api=$key"
    private val imagery = NativeImagery(context, ::fetch) { z, x, y -> linz("aerial", Id(z, x, y), "webp") }
    private val am = context.getSystemService(ActivityManager::class.java)
    private val imageLimit = if (am.isLowRamDevice) 32 else (am.memoryClass / 2).coerceIn(64, 128)
    private val geometryLimit = (imageLimit/2).coerceIn(16,96)
    private val readyByteLimit = imageLimit * 256*256*4 + geometryLimit * 129*129*4
    private var readyBytes = 0
    private val demLimit = if (am.isLowRamDevice) 24 else (am.memoryClass / 2).coerceIn(48, 128)
    private val ready = LinkedHashMap<Source, ByteBuffer>(16, .75f, true)
    private val decoded = LinkedHashMap<Id, Dem>(16, .75f, true)
    private val demJobs = ConcurrentHashMap<Id, CompletableFuture<Dem>>()
    private val jobs = ConcurrentHashMap<Source, Task>()
    private val sequence = AtomicLong()
    private val planBuffer=ByteBuffer.allocateDirect(512*12).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var dead = false
    @Volatile private var wanted = emptySet<Source>()
    private var timer: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    // Bounded workers, priority queue, no multiplication of network concurrency for halos.
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
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
                if (!dead) {
                    synchronized(ready) {
                        ready.put(source,data)?.let{readyBytes-=it.capacity()}
                        readyBytes+=data.capacity()
                        while(readyBytes>readyByteLimit){val first=ready.keys.first();readyBytes-=ready.remove(first)!!.capacity()}
                    }
                    if (source in wanted&&!resident(source)) upload(source,data)
                }
            } catch (e: Exception) {
                if (!dead && source in wanted) view.post { view.onStatus("Terrain load failed: " +
                    (if (e is TileHttpException) "provider HTTP ${e.code}" else "source unavailable")) }
            } finally { jobs.remove(source, this) }
        }
    }
    fun cameraChanged() = synchronized(this) {
        // Throttle using the latest immutable camera; a continuous drag must not
        // indefinitely postpone source selection, as a cancel/reschedule debounce did.
        if(!dead&&timer==null)timer=scheduler.schedule({
            synchronized(this){timer=null}
            loadView()
        },100,TimeUnit.MILLISECONDS)
    }
    private fun sourcePlan(geometry:Boolean,pose:CameraPose):List<Id> {
        val output=planBuffer;output.rewind()
        val budget=if(geometry)geometryLimit else imageLimit
        val count=NativeBridge.plan((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),
            (view.height/view.pixelScale).roundToInt().coerceAtLeast(1),pose.longitude,pose.latitude,
            pose.zoom,pose.bearing,pose.pitch,pose.elevation,budget,
            if(geometry)431.0 else 256.0/2.0.pow(.25),if(geometry)22 else 19,output)
        return List(count){Id(output.int,output.int,output.int)}
    }
    private fun loadView() {
        if (dead||view.width==0||view.height==0) return
        val pose=view.cameraPose
        val ranked=LinkedHashMap<Source,Int>()
        for(geometry in listOf(true,false)) {
            val ids=sourcePlan(geometry,pose)
            val demand=SourceDemand.bounded(ids.map{it.gpu()},if(geometry)geometryLimit else imageLimit)
            for((id,priority)in demand)ranked[Source(Id(id.z,id.x,id.y),geometry)]=priority
        }
        wanted = ranked.keys.toSet()
        for ((source, task) in jobs) if (source !in wanted && workers.remove(task)) jobs.remove(source, task)
        for ((source, priority) in ranked) {
            if(resident(source))continue
            val old = synchronized(ready) { ready[source] }
            if (old != null) { upload(source,old); continue }
            val task = Task(source, priority)
            if (jobs.putIfAbsent(source, task) == null && !dead) {
                try{workers.execute(task)}catch(_:RejectedExecutionException){jobs.remove(source,task)}
            }
        }
    }
    private fun fetch(url: String): ByteArray {
        check(!dead)
        val request = Request.Builder().url(url).header("User-Agent", "Ridgeline-Native/0.1.0").build()
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
    private fun sample(record: Dem, gx: Double, gy: Double, z: Int): Float {
        val scale = 2.0.pow(z - record.id.z)
        val px = ((gx + .5) / scale - .5 - record.id.x * 256).coerceIn(0.0, 255.0)
        val py = ((gy + .5) / scale - .5 - record.id.y * 256).coerceIn(0.0, 255.0)
        val x = floor(px).toInt(); val y = floor(py).toInt(); val fx = px - x; val fy = py - y
        fun h(a: Int, b: Int) = record.samples[b * 256 + a]
        return (h(x,y)*(1-fx)*(1-fy) + h(min(255,x+1),y)*fx*(1-fy) +
            h(x,min(255,y+1))*(1-fx)*fy + h(min(255,x+1),min(255,y+1))*fx*fy).toFloat()
    }
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
                Dem(id, pixels)
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
        val dz = min(id.z, 18); val factor = 1 shl (id.z - dz); val n = 1 shl dz
        val tx = id.x / factor; val ty = id.y / factor
        val halo = HashMap<Id, Dem>()
        for (dy in -1..1) for (dx in -1..1) {
            val neighbor = Id(dz, Math.floorMod(tx + dx, n), (ty + dy).coerceIn(0, n - 1))
            if (neighbor !in halo) halo[neighbor] = raw(neighbor)
        }
        fun height(gx: Int, gy: Int): Float {
            val x = Math.floorMod(gx, n * 256); val y = gy.coerceIn(0, n * 256 - 1)
            val record = halo.getValue(Id(dz, x / 256, y / 256))
            return sample(record, x.toDouble(), y.toDouble(), dz)
        }
        val heights = ByteBuffer.allocateDirect(129 * 129 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0..128) for (x in 0..128) {
            val px = (id.x * 256.0 + x * 2.0) / factor - .5
            val py = (id.y * 256.0 + y * 2.0) / factor - .5
            val ix = floor(px).toInt(); val iy = floor(py).toInt(); val fx = px - ix; val fy = py - iy
            val h = (height(ix,iy)*(1-fx)*(1-fy) + height(ix+1,iy)*fx*(1-fy) +
                height(ix,iy+1)*(1-fx)*fy + height(ix+1,iy+1)*fx*fy).toFloat()
            require(h.isFinite()); heights.putFloat(h)
        }
        heights.rewind();return heights
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
    fun close() {
        dead = true; timer?.cancel(true); scheduler.shutdownNow(); workers.shutdownNow()
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.cache?.close(); client.dispatcher.executorService.shutdown(); synchronized(ready) { ready.clear(); readyBytes=0 }; synchronized(decoded) { decoded.clear() }
    }
}
