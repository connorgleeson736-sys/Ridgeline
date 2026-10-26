package nz.ridgeline.app.data

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nz.ridgeline.app.model.*
import kotlin.math.*

class ElevationRepository(private val tiles: TileStore, private val store: AppStore) {
    // 32 decoded 256px tiles = 8 MiB. Work runs on IO and is cancelled after route edits.
    private val cache = object : LruCache<TileId, FloatArray>(8*1024*1024) {
        override fun sizeOf(key: TileId, value: FloatArray) = value.size * 4
    }
    private suspend fun heights(tile: TileId): FloatArray {
        cache.get(tile)?.let { return it }
        val bytes = tiles.get(tile)
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB) }
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Could not decode elevation")
        try {
            require(bitmap.width == 256 && bitmap.height == 256)
            val pixels = IntArray(65536); bitmap.getPixels(pixels,0,256,0,0,256,256)
            val result = FloatArray(pixels.size) { i ->
                val p = pixels[i]
                if(Color.alpha(p)==0) Float.NaN else (-10000 + (Color.red(p)*65536+Color.green(p)*256+Color.blue(p))*.1).toFloat()
            }
            cache.put(tile,result); return result
        } finally { bitmap.recycle() }
    }
    suspend fun profile(points: List<Coordinate>): List<ElevationSample> {
        val regions = store.read().regions.filter { it.completed }
        return Geography.samples(points).map { (distance, p) ->
            currentCoroutineContext().ensureActive()
            val zoom = regions.filter { it.bounds.contains(p) }.maxOfOrNull { it.maxZoom } ?: 14
            val height = try { sample(p,zoom) } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            ElevationSample(distance,height)
        }
    }
    private suspend fun sample(p: Coordinate,z: Int): Double? {
        val n = 1 shl z
        val gx = (p.longitude+180)/360*n*256-.5
        val gy = (1-asinh(tan(Math.toRadians(p.latitude)))/PI)/2*n*256-.5
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        suspend fun value(x: Int,y: Int): Double {
            val tx = Math.floorDiv(x,256); val ty = Math.floorDiv(y,256)
            return heights(TileId(TileKind.DEM,z,tx,ty))[Math.floorMod(y,256)*256+Math.floorMod(x,256)].toDouble()
        }
        val fx = gx-ix; val fy = gy-iy
        val a = value(ix,iy); val b = value(ix+1,iy); val c = value(ix,iy+1); val d = value(ix+1,iy+1)
        val h = a*(1-fx)*(1-fy)+b*fx*(1-fy)+c*(1-fx)*fy+d*fx*fy
        return h.takeIf(Double::isFinite)
    }
}
