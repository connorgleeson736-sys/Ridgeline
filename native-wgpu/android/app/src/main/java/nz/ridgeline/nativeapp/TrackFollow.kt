package nz.ridgeline.nativeapp

import kotlin.math.*

/** A drawn route's "from here to there" along the map's tracks: the shortest
 * way over the tracks (WalkOptions.Network) from the route's last point to a
 * tapped point, each joining its nearest track in a straight line. Pure
 * Kotlin, unit tested. */
internal object TrackFollow {
    /** A tapped point joins a track this close; further, it is drawn straight. */
    const val REACH_METERS=200.0
    /** Long straight pieces of track are split at this spacing, so a tap joins
     * the track beside it, not the piece's far end. */
    const val SPLIT_METERS=20.0
    /** Points kept where the line bends by more than this (metres off the line). */
    const val TOLERANCE_METERS=2.0

    /** [from] to [to] along [tracks] (name, line), or null when either point is
     * not within [REACH_METERS] of a track or the tracks do not join. */
    fun route(from:Coordinate,to:Coordinate,tracks:List<Pair<String,List<Coordinate>>>):List<Coordinate>?{
        val usable=tracks.filter{it.second.size>=2}.map{(n,l)->n to split(l)}
        if(usable.isEmpty())return null
        val net=WalkOptions.Network(usable)
        val a=net.nearest(from,REACH_METERS)?:return null
        val b=net.nearest(to,REACH_METERS)?:return null
        if(a==b)return null
        val (dist,prev)=net.from(a)
        if(dist[b]==Double.MAX_VALUE)return null
        val line=ArrayList<Coordinate>();line.add(from)
        for(v in net.path(prev,b))line.add(net.points[v])
        line.add(to)
        return simplify(line.filterIndexed{i,p->i==0||p!=line[i-1]})
    }
    /** [line] with points added so no piece is longer than [SPLIT_METERS]. */
    fun split(line:List<Coordinate>):List<Coordinate>{
        val out=ArrayList<Coordinate>(line.size);out.add(line[0])
        for(i in 1 until line.size){val a=line[i-1];val b=line[i];val n=ceil(DocTracks.meters(a,b)/SPLIT_METERS).toInt().coerceIn(1,500)
            for(k in 1 until n){val t=k.toDouble()/n;out.add(Coordinate(a.longitude+(b.longitude-a.longitude)*t,a.latitude+(b.latitude-a.latitude)*t))}
            out.add(b)}
        return out
    }
    /** Douglas–Peucker at [TOLERANCE_METERS]: the ends always kept. */
    fun simplify(line:List<Coordinate>):List<Coordinate>{
        if(line.size<=2)return line
        val keep=BooleanArray(line.size);keep[0]=true;keep[line.lastIndex]=true
        val stack=ArrayDeque<Pair<Int,Int>>();stack.add(0 to line.lastIndex)
        while(stack.isNotEmpty()){val (a,b)=stack.removeLast();var far=-1;var farD=TOLERANCE_METERS
            for(i in a+1 until b){val d=DocTracks.distanceTo(listOf(line[a],line[b]),line[i]);if(d>farD){farD=d;far=i}}
            if(far>=0){keep[far]=true;stack.add(a to far);stack.add(far to b)}}
        return line.filterIndexed{i,_->keep[i]}
    }
}
