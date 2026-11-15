package nz.ridgeline.nativeapp

import kotlin.math.hypot

/** Taps on drawn track lines (MapLayers.trackAt). Pure Kotlin, unit tested. */
internal object TrackHits {
    /** Distance from ([x], [y]) to the polyline [path], in its own units (Web
     * Mercator world units: isotropic, so nearest is nearest on the ground). */
    fun distance(path:List<VectorTile.Point>,x:Double,y:Double):Double{
        if(path.isEmpty())return Double.POSITIVE_INFINITY
        if(path.size==1)return hypot(path[0].x-x,path[0].y-y)
        var best=Double.POSITIVE_INFINITY
        for(i in 1 until path.size){val a=path[i-1];val b=path[i]
            val dx=b.x-a.x;val dy=b.y-a.y;val len=dx*dx+dy*dy
            val t=if(len==0.0)0.0 else(((x-a.x)*dx+(y-a.y)*dy)/len).coerceIn(0.0,1.0)
            val d=hypot(a.x+dx*t-x,a.y+dy*t-y);if(d<best)best=d}
        return best
    }
}
