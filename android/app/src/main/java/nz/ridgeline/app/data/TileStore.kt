package nz.ridgeline.app.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.ridgeline.app.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit downloads live in filesDir, never an evictable HTTP cache. Credentials are not cache keys. */
class TileStore(context: Context, private val store: AppStore) {
    private val root = File(context.filesDir,"offline-tiles").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .callTimeout(40,TimeUnit.SECONDS).cache(Cache(File(context.cacheDir,"http"),64L*1024*1024)).build()
    val mapClient: OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val tile = fromUrl(chain.request().url)
        val cached = tile?.let { file(it).takeIf(File::isFile) }
        if (cached != null) Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline tile")
            .body(cached.readBytes().toResponseBody((if (tile.kind == TileKind.DEM) "image/png" else "image/webp").toMediaType()))
            .header("Cache-Control","max-age=86400").build()
        else chain.proceed(chain.request())
    }.build()
    private fun file(tile: TileId) = File(root,tile.path)
    fun has(tile: TileId) = file(tile).isFile
    fun bytesUsed(): Long = root.walkTopDown().filter(File::isFile).sumOf(File::length)
    fun freeBytes() = root.usableSpace
    fun url(tile: TileId): HttpUrl {
        require(store.linzKey.isNotBlank()) { "Add your LINZ Basemaps API key in Settings" }
        return HttpUrl.Builder().scheme("https").host("basemaps.linz.govt.nz")
            .addPathSegments("v1/tiles/${tile.kind.path}/WebMercatorQuad/${tile.z}/${tile.x}/${tile.y}.${tile.kind.extension}")
            .addQueryParameter("api",store.linzKey).apply { if(tile.kind == TileKind.DEM) addQueryParameter("pipeline","terrain-rgb") }.build()
    }
    private fun fromUrl(url: HttpUrl): TileId? {
        if (url.host != "basemaps.linz.govt.nz") return null
        val parts = url.pathSegments
        if (parts.size != 7 || parts.take(2) != listOf("v1","tiles")) return null
        return runCatching {
            TileId(TileKind.entries.first { it.path == parts[2] },parts[4].toInt(),parts[5].toInt(),parts[6].substringBefore('.').toInt())
        }.getOrNull()
    }
    fun readSaved(tile: TileId): ByteArray? = file(tile).takeIf(File::isFile)?.readBytes()
    suspend fun get(tile: TileId): ByteArray = withContext(Dispatchers.IO) {
        readSaved(tile) ?: fetch(tile)
    }
    class HttpFailure(val status: Int) : IOException("Map provider returned HTTP $status")
    private suspend fun fetch(tile: TileId): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = try { client.newCall(Request.Builder().url(url(tile)).build()) }
            catch(e: Exception) { continuation.resumeWithException(e); return@suspendCancellableCoroutine }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                try {
                    val bytes=response.use {
                        if (!it.isSuccessful) throw HttpFailure(it.code)
                        val body = it.body?.byteStream()?.use { stream -> stream.readBounded(1024*1024) } ?: throw IOException("Empty map tile")
                        require(body.size <= 1024*1024) { "Map tile too large" }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(body,0,body.size,options)
                        require(options.outWidth in 256..512 && options.outHeight in 256..512) { "Invalid map tile" }
                        body
                    }
                    if(continuation.isActive)continuation.resume(bytes)
                } catch(e: Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        })
    }
    suspend fun download(tile: TileId,regionId: String) = withContext(Dispatchers.IO) {
        if (!has(tile)) {
            val bytes = fetch(tile)
            currentCoroutineContext().ensureActive()
            val target = file(tile); target.parentFile!!.mkdirs()
            val temporary = File(target.parentFile,"${target.name}.${UUID.randomUUID()}.part")
            try {
                synchronized(this@TileStore) {
                    if(store.read().regions.none { it.id==regionId }) return@withContext
                    temporary.writeBytes(bytes); check(temporary.renameTo(target)) { "Unable to save tile" }
                }
            }
            finally { temporary.delete() }
        }
    }
    /** Called only when no region worker is running. Shared tiles belonging to other regions survive. */
    @Synchronized fun prune(regions: List<OfflineRegion>) {
        val keep = regions.asSequence().flatMap { it.tiles() }.map { it.path }.toHashSet()
        root.walkTopDown().filter(File::isFile).forEach { if (it.relativeTo(root).invariantSeparatorsPath !in keep) it.delete() }
    }
}
