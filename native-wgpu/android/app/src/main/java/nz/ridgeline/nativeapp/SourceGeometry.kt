package nz.ridgeline.nativeapp

import kotlin.math.*

/** Pixel-centre even/odd polygons, identical to regional-blend.js (including holes). */
object SourceGeometry {
    data class Point(val x: Double, val y: Double)
    fun relation(rings: List<List<Point>>, box: DoubleArray): Int {
        for (ring in rings) for (i in 1 until ring.size) {
            val a = ring[i - 1]; val b = ring[i]
            if (max(a.x, b.x) >= box[0] && min(a.x, b.x) <= box[2] &&
                max(a.y, b.y) >= box[1] && min(a.y, b.y) <= box[3]) return 0
        }
        val x = (box[0] + box[2]) / 2; val y = (box[1] + box[3]) / 2
        var hit = false
        for (ring in rings) {
            var j = ring.lastIndex
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) hit = !hit
                j = i
            }
        }
        return if (hit) 1 else -1
    }
    fun mode(z: Int, x: Int, y: Int, mz: Int, mx: Int, my: Int, width: Int, height: Int,
             outer: List<List<Point>>, inner: List<List<Point>>, seams: List<List<Point>>, vector: Boolean): String {
        val size = 256 * 2.0.pow(mz - z); val px = x * size - mx * 256.0; val py = y * size - my * 256.0
        if (px + size < 0 || py + size < 0 || px > width || py > height) return "linz"
        val box = doubleArrayOf(px - 2, py - 2, px + size + 2, py + size + 2)
        if (seams.isNotEmpty() && relation(seams, box) != -1) return "boundary"
        if (relation(outer, box) == -1) return "linz"
        if (relation(if (vector) outer else inner, box) == 1) return "esri"
        return "boundary"
    }
    fun mask(rings: List<List<Point>>, z: Int, x: Int, y: Int, mz: Int, mx: Int, my: Int): BooleanArray {
        val f = 2.0.pow(z - mz); val dx = (mx * f - x) * 256; val dy = (my * f - y) * 256
        val edges = ArrayList<DoubleArray>()
        for (ring in rings) for (i in 1 until ring.size) {
            val a = ring[i - 1]; val b = ring[i]
            val ax = a.x * f + dx; val ay = a.y * f + dy; val bx = b.x * f + dx; val by = b.y * f + dy
            if (ay != by && max(ay, by) > .5 && min(ay, by) < 255.5) edges.add(doubleArrayOf(ax, ay, bx, by))
        }
        val result = BooleanArray(256 * 256)
        val xs = ArrayList<Double>()
        for (row in 0..255) {
            val py = row + .5; xs.clear()
            for (e in edges) if ((e[1] > py) != (e[3] > py)) xs.add(e[0] + (py - e[1]) * (e[2] - e[0]) / (e[3] - e[1]))
            xs.sort()
            for (j in 0 until xs.size - 1 step 2) {
                val lo = max(0, ceil(xs[j] - .5).toInt()); val hi = min(256, ceil(xs[j + 1] - .5).toInt())
                for (col in lo until hi) result[row * 256 + col] = true
            }
        }
        return result
    }
}
