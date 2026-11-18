package nz.ridgeline.nativeapp

import kotlin.math.cos
import kotlin.math.max

/** Huts where a planned route starts or ends, marked on its elevation profile:
 * the nearest hut (offline place search: DOC's huts) within [REACH] metres of the
 * route's first point, at 0, and of its last point, at its length. A point locked
 * onto a hut on the map sits on the map's hut symbol, which can be a little from
 * DOC's position for it. Pure Kotlin, unit tested. */
internal object RouteHuts {
    const val REACH=200.0
    fun ends(points:List<Coordinate>,length:Double,within:(DoubleArray)->List<PlaceSearch.Place>,reach:Double=REACH):List<Pair<Double,String>>{
        if(points.size<2||!(length>0))return emptyList()
        fun at(p:Coordinate):PlaceSearch.Place?{
            val dy=reach/111_320.0;val dx=dy/max(.2,cos(Math.toRadians(p.latitude)))
            return within(doubleArrayOf(p.longitude-dx,p.latitude-dy,p.longitude+dx,p.latitude+dy)).asSequence().filter{it.kind=="hut"}
                .map{it to DocTracks.meters(p,Coordinate(it.longitude,it.latitude))}.filter{it.second<=reach}.minByOrNull{it.second}?.first
        }
        return listOfNotNull(at(points.first())?.let{0.0 to DocTracks.hutName(it.name)},at(points.last())?.let{length to DocTracks.hutName(it.name)})
    }
}
