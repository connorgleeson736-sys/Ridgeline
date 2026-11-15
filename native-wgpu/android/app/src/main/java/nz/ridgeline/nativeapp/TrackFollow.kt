package nz.ridgeline.nativeapp

import kotlin.math.*

/** A drawn route's "from here to there" along the map's tracks and walkable
 * roads: a tap close to one is reached from the route's last point by the
 * shortest way (WalkOptions.Network), ending on it, fording a river or stream
 * where that is within reason. Pure Kotlin, unit tested. */
internal object TrackFollow {
    /** The route's last point (a hut, say) joins a track this close. */
    const val START_REACH_METERS=200.0
    /** Long straight pieces of track are split at this spacing, so a tap joins
     * the track beside it, not the piece's far end. */
    const val SPLIT_METERS=20.0
    /** Points kept where the line bends by more than this (metres off the line). */
    const val TOLERANCE_METERS=2.0

    /** A metre of road counts this much against a metre of track: tracks are
     * preferred, roads used where they are the way (or much shorter). */
    const val ROAD_COST=1.25
    /** A ford: a straight crossing of a mapped river or stream, at most this wide… */
    const val FORD_MAX_METERS=120.0
    /** …counted this many times its width plus [FORD_EXTRA_METERS], so a bridge or
     * track round is taken unless the ford saves a real detour. */
    const val FORD_FACTOR=3.0
    const val FORD_EXTRA_METERS=150.0

    /** From [from] (the route's last point) along [tracks] and walkable [roads]
     * (name, line) to the point on a track or road nearest [to]: point to point,
     * ending on it (not branching off to [to] itself). [from] joins straight
     * where it is off them. Where [water] (rivers and streams) separates the way,
     * one ford within reason ([FORD_MAX_METERS]) may cross it. Null when [to] is
     * not within [reach] metres of a track or road, [from] not within
     * [START_REACH_METERS], or nothing joins them. */
    fun route(from:Coordinate,to:Coordinate,tracks:List<Pair<String,List<Coordinate>>>,reach:Double,
              roads:List<Pair<String,List<Coordinate>>> = emptyList(),water:List<List<Coordinate>> = emptyList()):List<Coordinate>?{
        // Split only near the two taps (where a tap joins the track): a long leg's
        // area holds hundreds of kilometres of track.
        val near={c:Coordinate->DocTracks.meters(c,from)<=SPLIT_NEAR_METERS||DocTracks.meters(c,to)<=SPLIT_NEAR_METERS}
        val graph=Graph(tracks,roads,water,near)
        return if(graph.net.points.isEmpty())null else route(from,to,graph,reach)
    }
    /** The tracks and walkable roads of an area as one network, built once
     * (dragging a point re-routes along it at every move). [near]: where long
     * pieces are split so a point joins the track beside it (everywhere by default). */
    class Graph(tracks:List<Pair<String,List<Coordinate>>>,roads:List<Pair<String,List<Coordinate>>> = emptyList(),
                val water:List<List<Coordinate>> = emptyList(),near:(Coordinate)->Boolean={true}){
        val net:WalkOptions.Network
        init{
            val lines=ArrayList<Pair<String,List<Coordinate>>>();val road=ArrayList<Boolean>()
            for(t in tracks)if(t.second.size>=2){lines.add(t.first to split(t.second,near));road.add(false)}
            for(t in roads)if(t.second.size>=2){lines.add(t.first to split(t.second,near));road.add(true)}
            net=WalkOptions.Network(lines,cost={i->if(road[i])ROAD_COST else 1.0})
        }
        /** The point on a track or road nearest [p], if within [reach] metres. */
        fun snap(p:Coordinate,reach:Double):Coordinate?{
            val v=net.nearest(p,reach+SPLIT_METERS)?:return null
            val q=onTrack(net,v,p).first;return q.takeIf{DocTracks.meters(it,p)<=reach}
        }
    }
    /** A dragged point and the route beside it: [before] runs from just after the
     * previous point to the dragged point (its last), [after] from just after it
     * to just before the next point. */
    class Dragged(val point:Coordinate,val before:List<Coordinate>,val after:List<Coordinate>)
    /** Dragging a point between [prev] and [next] (null at the route's ends) to [p]:
     * within [reach] of a track or road it slides along it, and both pieces beside
     * it follow the tracks (each straight where they do not join); further away it
     * comes off the track and both pieces are straight. */
    fun drag(graph:Graph,prev:Coordinate?,p:Coordinate,next:Coordinate?,reach:Double):Dragged{
        val on=graph.snap(p,reach)?:return Dragged(p,listOf(p),emptyList())
        val before=prev?.let{route(it,on,graph,reach)}?.drop(1)?.takeIf{it.isNotEmpty()}?:listOf(on)
        val point=before.last()
        val after=next?.let{n->route(n,point,graph,reach)?.reversed()}?.let{l->l.subList(1,l.size-1)}.orEmpty()
        return Dragged(point,before,after)
    }
    /** As [route] over a [graph] already built. */
    fun route(from:Coordinate,to:Coordinate,graph:Graph,reach:Double):List<Coordinate>?{
        val net=graph.net;val water=graph.water
        if(net.points.isEmpty())return null
        val b=net.nearest(to,reach+SPLIT_METERS)?:return null
        val end=onTrack(net,b,to);if(DocTracks.meters(end.first,to)>reach)return null
        val a=net.nearest(from,START_REACH_METERS+SPLIT_METERS)?:return null
        val start=onTrack(net,a,from);if(DocTracks.meters(start.first,from)>START_REACH_METERS)return null
        val (dist,prev)=net.from(a)
        var nodes:List<Int>?=if(dist[b]<Double.MAX_VALUE)net.path(prev,b)else null
        if(water.isNotEmpty()){
            val (back,prevB)=net.from(b)
            ford(net,dist,back,water,if(nodes!=null)dist[b]else Double.MAX_VALUE)?.let{(u,v)->
                nodes=net.path(prev,u)+net.path(prevB,v).reversed()}
        }
        val path=nodes?:return null
        val line=ArrayList<Coordinate>(path.size+3);line.add(from)
        // Both on the same piece of track: straight along it.
        if(path.size==1&&start.second>=0&&start.second==end.second){line.add(start.first);line.add(end.first)
            return simplify(line.filterIndexed{i,p->i==0||DocTracks.meters(p,line[i-1])>.05})}
        // The exact points beside [from] and [to] on the pieces next to the end
        // nodes: replacing the node where the path runs over that piece anyway.
        if(path.size>=2&&path[1]==start.second)line.add(start.first)else{line.add(start.first);line.add(net.points[a])}
        for(i in 1 until path.size-1)line.add(net.points[path[i]])
        if(path.size>=2){if(path[path.size-2]==end.second)line.add(end.first)else{line.add(net.points[b]);line.add(end.first)}}
        else line.add(end.first)
        return simplify(line.filterIndexed{i,p->i==0||DocTracks.meters(p,line[i-1])>.05})
    }
    /** The best ford (u, v): a straight step of at most [FORD_MAX_METERS] that
     * crosses [water], from a point reached from the start ([fromA]) to one that
     * reaches the end ([toB]), cheaper (at [FORD_FACTOR] its width plus
     * [FORD_EXTRA_METERS]) than [limit], the way without one. Null if none. */
    private fun ford(net:WalkOptions.Network,fromA:DoubleArray,toB:DoubleArray,water:List<List<Coordinate>>,limit:Double):Pair<Int,Int>?{
        if(net.points.isEmpty())return null
        val lat0=net.points[0].latitude;val kx=111_320.0*cos(Math.toRadians(lat0));val ky=111_320.0
        fun x(c:Coordinate)=c.longitude*kx
        fun y(c:Coordinate)=c.latitude*ky
        val cell=FORD_MAX_METERS
        fun key(cx:Long,cy:Long)=(cx shl 32) or (cy and 0xffffffffL)
        val ends=HashMap<Long,MutableList<Int>>()
        for(v in net.points.indices)if(toB[v]<Double.MAX_VALUE)ends.getOrPut(key(floor(x(net.points[v])/cell).toLong(),floor(y(net.points[v])/cell).toLong())){ArrayList()}.add(v)
        // Water segments by cell, for the crossing test.
        val wcell=60.0;val waterCells=HashMap<Long,MutableList<DoubleArray>>()
        for(w in water)for(i in 1 until w.size){val ax=x(w[i-1]);val ay=y(w[i-1]);val bx=x(w[i]);val by=y(w[i])
            for(cx in floor(min(ax,bx)/wcell).toLong()..floor(max(ax,bx)/wcell).toLong())for(cy in floor(min(ay,by)/wcell).toLong()..floor(max(ay,by)/wcell).toLong())
                waterCells.getOrPut(key(cx,cy)){ArrayList()}.add(doubleArrayOf(ax,ay,bx,by))}
        if(waterCells.isEmpty())return null
        fun crosses(px:Double,py:Double,qx:Double,qy:Double):Boolean{
            for(cx in floor(min(px,qx)/wcell).toLong()..floor(max(px,qx)/wcell).toLong())for(cy in floor(min(py,qy)/wcell).toLong()..floor(max(py,qy)/wcell).toLong())
                for(s in waterCells[key(cx,cy)]?:continue)if(intersects(px,py,qx,qy,s[0],s[1],s[2],s[3]))return true
            return false
        }
        var best=limit;var pick:Pair<Int,Int>?=null
        for(u in net.points.indices){val du=fromA[u];if(du==Double.MAX_VALUE||du+FORD_EXTRA_METERS>=best)continue
            val ux=x(net.points[u]);val uy=y(net.points[u]);val cx=floor(ux/cell).toLong();val cy=floor(uy/cell).toLong()
            for(dx in -1L..1L)for(dy in -1L..1L)for(v in ends[key(cx+dx,cy+dy)]?:continue){
                val vx=x(net.points[v]);val vy=y(net.points[v]);val d=hypot(vx-ux,vy-uy);if(d>FORD_MAX_METERS||d<1)continue
                val c=du+FORD_FACTOR*d+FORD_EXTRA_METERS+toB[v]
                if(c<best&&crosses(ux,uy,vx,vy)){best=c;pick=u to v}}}
        return pick
    }
    /** Whether segments p-q and a-b cross. */
    private fun intersects(px:Double,py:Double,qx:Double,qy:Double,ax:Double,ay:Double,bx:Double,by:Double):Boolean{
        fun side(x1:Double,y1:Double,x2:Double,y2:Double,x3:Double,y3:Double)=(x2-x1)*(y3-y1)-(y2-y1)*(x3-x1)
        val d1=side(ax,ay,bx,by,px,py);val d2=side(ax,ay,bx,by,qx,qy);val d3=side(px,py,qx,qy,ax,ay);val d4=side(px,py,qx,qy,bx,by)
        return (d1>0)!=(d2>0)&&(d3>0)!=(d4>0)
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
    /** Track is split this close to either tap (see [split]). */
    const val SPLIT_NEAR_METERS=600.0
    /** [line] with points added so no piece is longer than [SPLIT_METERS]
     * (only pieces with an end where [near] holds). */
    fun split(line:List<Coordinate>,near:(Coordinate)->Boolean={true}):List<Coordinate>{
        val out=ArrayList<Coordinate>(line.size);out.add(line[0])
        for(i in 1 until line.size){val a=line[i-1];val b=line[i];val len=DocTracks.meters(a,b)
            val n=if(near(a)||near(b)||len>SPLIT_NEAR_METERS)ceil(len/SPLIT_METERS).toInt().coerceIn(1,500)else 1
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
