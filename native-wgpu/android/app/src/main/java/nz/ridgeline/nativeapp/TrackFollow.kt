package nz.ridgeline.nativeapp

import kotlin.math.*

/** A drawn route's "from here to there" along the map's tracks: a tap close
 * to a track is reached from the route's last point by the shortest way over
 * the tracks (WalkOptions.Network), ending on the track. Pure Kotlin, unit tested. */
internal object TrackFollow {
    /** The route's last point (a hut, say) joins a track this close. */
    const val START_REACH_METERS=200.0
    /** Long straight pieces of track are split at this spacing, so a tap joins
     * the track beside it, not the piece's far end. */
    const val SPLIT_METERS=20.0
    /** Points kept where the line bends by more than this (metres off the line). */
    const val TOLERANCE_METERS=2.0

    /** From [from] (the route's last point) along [tracks] (name, line) to the
     * point on a track nearest [to]: point to point along the track, ending on
     * it (not branching off to [to] itself). [from] joins the track straight
     * where it is off it. Null when [to] is not within [reach] metres of a
     * track, [from] not within [START_REACH_METERS], or the tracks do not join. */
    fun route(from:Coordinate,to:Coordinate,tracks:List<Pair<String,List<Coordinate>>>,reach:Double):List<Coordinate>?{
        val usable=tracks.filter{it.second.size>=2}.map{(n,l)->n to split(l)}
        if(usable.isEmpty())return null
        val net=WalkOptions.Network(usable)
        val b=net.nearest(to,reach+SPLIT_METERS)?:return null
        val end=onTrack(net,b,to);if(DocTracks.meters(end.first,to)>reach)return null
        val a=net.nearest(from,START_REACH_METERS+SPLIT_METERS)?:return null
        val start=onTrack(net,a,from);if(DocTracks.meters(start.first,from)>START_REACH_METERS)return null
        val (dist,prev)=net.from(a)
        if(dist[b]==Double.MAX_VALUE)return null
        val nodes=net.path(prev,b)
        val line=ArrayList<Coordinate>(nodes.size+3);line.add(from)
        // The exact points beside [from] and [to] on the pieces next to the end
        // nodes: replacing the node where the path runs over that piece anyway.
        // Both on the same piece of track: straight along it.
        if(nodes.size==1&&start.second>=0&&start.second==end.second){line.add(start.first);line.add(end.first)
            return simplify(line.filterIndexed{i,p->i==0||DocTracks.meters(p,line[i-1])>.05})}
        if(nodes.size>=2&&nodes[1]==start.second)line.add(start.first)else{line.add(start.first);line.add(net.points[a])}
        for(i in 1 until nodes.size-1)line.add(net.points[nodes[i]])
        if(nodes.size>=2){if(nodes[nodes.size-2]==end.second)line.add(end.first)else{line.add(net.points[b]);line.add(end.first)}}
        else line.add(end.first)
        return simplify(line.filterIndexed{i,p->i==0||DocTracks.meters(p,line[i-1])>.05})
    }
    /** The point on the track nearest [p] beside node [v] (on one of its track
     * pieces), and that piece's other node (-1: the node itself). */
    private fun onTrack(net:WalkOptions.Network,v:Int,p:Coordinate):Pair<Coordinate,Int>{
        var best=net.points[v];var bestD=DocTracks.meters(best,p);var other=-1
        for((n,_,t) in net.edges[v]){if(t<0)continue
            val a=net.points[v];val c=net.points[n];if(DocTracks.meters(a,c)>SPLIT_METERS*1.05)continue
            val k=cos(Math.toRadians(p.latitude));val ax=(a.longitude-p.longitude)*k;val ay=a.latitude-p.latitude;val dx=(c.longitude-a.longitude)*k;val dy=c.latitude-a.latitude
            val l=dx*dx+dy*dy;if(l==0.0)continue
            val f=(-(ax*dx+ay*dy)/l).coerceIn(0.0,1.0)
            val q=Coordinate(a.longitude+(c.longitude-a.longitude)*f,a.latitude+(c.latitude-a.latitude)*f)
            val d=DocTracks.meters(q,p);if(d<bestD-1e-9){bestD=d;best=q;other=if(f>0.0)n else -1}}
        return best to other
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
