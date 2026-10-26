package nz.ridgeline.app.model

import java.util.UUID
import kotlin.math.*

data class Coordinate(val longitude: Double, val latitude: Double) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
        require(latitude.isFinite() && latitude in -85.0..85.0) { "Latitude must be between −85° and 85°" }
    }
}

data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled route",
    val points: List<Coordinate> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
) {
    init { require(points.size <= MAX_POINTS) { "Routes support up to $MAX_POINTS points" } }
    companion object { const val MAX_POINTS = 2000 }
}

data class ElevationSample(val distance: Double, val height: Double?)
data class ElevationStats(val ascent: Double, val descent: Double, val highest: Double?, val complete: Boolean)

object Geography {
    private const val EARTH_RADIUS = 6371008.8
    fun distance(a: Coordinate, b: Coordinate): Double {
        val p = Math.toRadians(b.latitude - a.latitude)
        val q = Math.toRadians(b.longitude - a.longitude)
        val h = (sin(p / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(q / 2).pow(2)).coerceIn(0.0, 1.0)
        return EARTH_RADIUS * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
    fun length(points: List<Coordinate>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /** Uniform samples plus exact corners; preserves the original planner's switchbacks. */
    fun samples(points: List<Coordinate>, limit: Int = 240, spacing: Double = 100.0): List<Pair<Double, Coordinate>> {
        require(limit >= 2 && spacing > 0)
        if (points.size < 2) return points.map { 0.0 to it }
        val distances = DoubleArray(points.size)
        for (i in 1 until points.size) distances[i] = distances[i - 1] + distance(points[i - 1], points[i])
        val total = distances.last()
        if (total == 0.0) return listOf(0.0 to points.first())
        val count = max(2, min(limit, ceil(total / spacing).toInt() + 1))
        var segment = 1
        val result = (0 until count).map { i ->
            val d = i * total / (count - 1)
            while (segment < points.lastIndex && distances[segment] < d) segment++
            val span = distances[segment] - distances[segment - 1]
            val t = if (span > 0) (d - distances[segment - 1]) / span else 0.0
            val a = points[segment - 1]; val b = points[segment]
            // Interpolate across the antimeridian along the short arc.
            val delta = ((b.longitude - a.longitude + 540) % 360) - 180
            val lon = ((a.longitude + delta * t + 540) % 360) - 180
            d to Coordinate(lon, a.latitude + (b.latitude - a.latitude) * t)
        } + points.mapIndexed { i, p -> distances[i] to p }
        return result.sortedBy { it.first }.fold(mutableListOf()) { acc, sample ->
            if (acc.isEmpty() || sample.first - acc.last().first > .01) acc.add(sample)
            else acc[acc.lastIndex] = sample // exact corners follow uniform samples at the same distance
            acc
        }
    }

    /** Same three-sample smoothing / 5 m hysteresis as the website. Gaps never count as flat ground. */
    fun elevationStats(profile: List<ElevationSample>): ElevationStats {
        val values = profile.map { it.height?.takeIf(Double::isFinite) }
        var ascent = 0.0; var descent = 0.0; var anchor: Double? = null
        values.indices.forEach { i ->
            if (values[i] == null) { anchor = null; return@forEach }
            val local = values.subList(max(0, i - 1), min(values.size, i + 2)).filterNotNull()
            val height = local.average()
            anchor?.let { previous ->
                val change = height - previous
                if (abs(change) >= 5) {
                    if (change > 0) ascent += change else descent -= change
                    anchor = height
                }
            } ?: run { anchor = height }
        }
        return ElevationStats(ascent, descent, values.filterNotNull().maxOrNull(), values.isNotEmpty() && values.all { it != null })
    }
}
