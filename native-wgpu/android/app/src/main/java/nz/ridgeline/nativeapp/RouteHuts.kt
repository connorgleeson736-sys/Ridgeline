package nz.ridgeline.nativeapp

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Huts on a planned route, marked on its elevation profile: every DOC hut
 * (offline place search) within [REACH] metres of the route's line, at its
 * distance along the profile (its nearest point on the line), so a hut at the
 * start, at the end or passed on the way shows where it is. A point locked onto
 * a hut on the map sits on the map's hut symbol, which can be a little from DOC's
 * position for it. Pure Kotlin, unit tested. */
internal object RouteHuts {
    const val REACH=200.0
    fun along(profile:List<NativeGeo.ProfilePoint>,within:(DoubleArray)->List<PlaceSearch.Place>,reach:Double=REACH):List<Pair<Double,String>>{
        if(profile.size<2)return emptyList()
        val line=profile.map{it.point}
        val box=DocTracks.bounds(line,reach)?:return emptyList()
        val lat=line.sumOf{it.latitude}/line.size
        // Local metres (equirectangular about the route): plenty at hut distances.
        val my=111_320.0;val mx=my*cos(Math.toRadians(lat))
        val best=HashMap<String,Triple<Double,Double,String>>()
        for(h in within(box)){if(h.kind!="hut")continue
            val px=h.longitude*mx;val py=h.latitude*my
            var near=Double.MAX_VALUE;var at=0.0
            for(i in 1 until profile.size){val a=line[i-1];val b=line[i]
                val ax=a.longitude*mx;val ay=a.latitude*my;val dx=b.longitude*mx-ax;val dy=b.latitude*my-ay
                val len=dx*dx+dy*dy;val t=if(len>0)(((px-ax)*dx+(py-ay)*dy)/len).coerceIn(0.0,1.0)else 0.0
                val d=hypot(ax+dx*t-px,ay+dy*t-py)
                if(d<near){near=d;at=profile[i-1].distance+(profile[i].distance-profile[i-1].distance)*t}}
            if(near>reach)continue
            val name=DocTracks.hutName(h.name);val key=name.lowercase()
            if((best[key]?.first?:Double.MAX_VALUE)>near)best[key]=Triple(near,at,name)
        }
        return best.values.sortedBy{it.second}.map{it.second to it.third}
    }
}
