package nz.ridgeline.app.model

import kotlin.math.*

data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(listOf(west, south, east, north).all(Double::isFinite))
        require(west >= 165 && east <= 180 && south >= -49 && north <= -33 && west < east && south < north) {
            "Choose a rectangle within New Zealand (165–180° E, 49–33° S)"
        }
    }
    fun contains(p: Coordinate) = p.longitude in west..east && p.latitude in south..north
    companion object {
        fun between(a: Coordinate, b: Coordinate) = Bounds(min(a.longitude,b.longitude), min(a.latitude,b.latitude), max(a.longitude,b.longitude),max(a.latitude,b.latitude))
    }
}

enum class TileKind(val path: String, val extension: String) {
    AERIAL("aerial", "webp"), TOPO("topo-raster", "webp"), DEM("elevation", "png")
}
data class TileId(val kind: TileKind, val z: Int, val x: Int, val y: Int) {
    init { require(z in 0..19 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) }
    val path get() = "${kind.name}/$z/$x/$y.${kind.extension}"
    companion object {
        fun at(point: Coordinate, zoom: Int, kind: TileKind): TileId {
            val n = 1 shl zoom
            val x = floor((point.longitude + 180) / 360 * n).toInt().coerceIn(0, n-1)
            val y = floor((1 - asinh(tan(Math.toRadians(point.latitude))) / PI) / 2 * n).toInt().coerceIn(0,n-1)
            return TileId(kind, zoom, x, y)
        }
    }
}
data class OfflineRegion(
    val id: String,
    val name: String,
    val bounds: Bounds,
    val maxZoom: Int = 14,
    val completed: Boolean = false,
) {
    init { require(maxZoom in 10..16) }
    /** Include low-zoom parents and a one-tile DEM halo for hillshade and bilinear sampling. */
    fun tiles(): Sequence<TileId> = sequence {
        for (z in 0..maxZoom) {
            val nw = TileId.at(Coordinate(bounds.west,bounds.north),z,TileKind.AERIAL)
            val se = TileId.at(Coordinate(bounds.east,bounds.south),z,TileKind.AERIAL)
            for (kind in TileKind.entries) {
                val halo = if (kind == TileKind.DEM) 1 else 0
                for (x in max(0,nw.x-halo)..min((1 shl z)-1,se.x+halo))
                    for (y in max(0,nw.y-halo)..min((1 shl z)-1,se.y+halo)) yield(TileId(kind,z,x,y))
            }
        }
    }
    fun tileCount(): Int = tiles().take(MAX_TILES + 1).count()
    companion object { const val MAX_TILES = 8000 }
}
