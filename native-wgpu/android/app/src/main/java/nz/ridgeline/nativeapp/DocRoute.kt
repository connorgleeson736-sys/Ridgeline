package nz.ridgeline.nativeapp

import kotlin.math.*

/** A walk's route from DOC's line pieces (as DOC draws it: its own line, not
 * rerouted). DOC's pieces are not one line in order: a piece can end part way
 * along another (the Kepler Track's Luxmore piece ends at Iris Burn Hut, half
 * way along the valley piece, which starts 1 km on at Iris Burn Falls), loops
 * are left open at the car park, and side trips are pieces of their own.
 * Joining them greedily from where the walk was tapped jumped between piece
 * ends (1 km from the hut to the falls) and started at a side trip's end.
 *
 * Here the pieces become a network: split where one ends on another, ends
 * that touch merged, separate parts joined across their shortest gaps, and a
 * loop's two open ends (close together, far apart along the line) closed.
 * Dead-end branches are side trips; what remains is the loop, or for a walk
 * from one end to another its longest path. It starts at the place nearest
 * where DOC's line starts. Pure Kotlin, unit tested on DOC's data. */
internal object DocRoute {
    /** A piece's end this close to another piece's middle meets it there. */
    const val JOIN_METERS=60.0
    /** Piece ends this close are one point. */
    const val MERGE_METERS=30.0
    /** Separate parts of DOC's line are joined across gaps up to this. */
    const val GAP_METERS=3000.0
    /** A loop's two open ends: this close together… */
    const val LOOP_GAP_METERS=1500.0
    /** …and at least this many times further apart along the line (plus 1 km). */
    const val LOOP_FACTOR=5.0
    /** Without DOC's distance, a loop is the walk when it is at least this share of all of DOC's line. */
    const val LOOP_SHARE=.6
    /** Dead ends off a loop tried as its access track (see [route]). */
    const val ACCESS_CANDIDATES=6
    /** A route within this share of DOC's stated distance fits it. */
    const val TARGET_SHARE=.1

    private class Edge(val u:Int,val v:Int,val points:List<Coordinate>){val length=DocTracks.length(points)}

    /** The walk's route over [pieces] (DOC's line), or empty. [target]: DOC's
     * stated distance in metres (WalkRoutes.stated), when it has one. */
    fun route(pieces:List<List<Coordinate>>,target:Double?=null):List<Coordinate>{
        val parts=pieces.filter{it.size>=2&&DocTracks.length(it)>0}
        if(parts.isEmpty())return emptyList()
        if(parts.size==1)return parts[0]
        val start=parts[0].first()
        val nodes=ArrayList<Coordinate>()
        fun node(c:Coordinate):Int{for(i in nodes.indices)if(DocTracks.meters(nodes[i],c)<=MERGE_METERS)return i;nodes.add(c);return nodes.size-1}
        val edges=ArrayList<Edge>()
        fun connect(a:Int,b:Int){if(a!=b)edges.add(Edge(a,b,listOf(nodes[a],nodes[b])))}
        // Where a piece's end meets another piece part way along: split that piece there.
        val cuts=parts.map{ArrayList<Double>()};val links=ArrayList<Pair<Coordinate,Coordinate>>()
        for((j,q) in parts.withIndex())for(e in listOf(q.first(),q.last()))for((i,p) in parts.withIndex()){if(i==j)continue
            val (d,along,at)=nearest(p,e);val len=DocTracks.length(p)
            if(d<=JOIN_METERS&&along>JOIN_METERS&&along<len-JOIN_METERS){cuts[i].add(along);if(d>MERGE_METERS)links.add(e to at)}}
        for((i,p) in parts.withIndex())for(piece in split(p,cuts[i]))edges.add(Edge(node(piece.first()),node(piece.last()),piece))
        for((e,at) in links)connect(node(e),node(at))
        // Separate parts: joined across their shortest gaps; beyond [GAP_METERS], the longest part only.
        while(true){val comp=components(nodes.size,edges);if(comp.toSet().size<=1)break
            var best:Pair<Int,Int>?=null;var bestD=GAP_METERS
            for(a in nodes.indices)for(b in a+1 until nodes.size)if(comp[a]!=comp[b]){val d=DocTracks.meters(nodes[a],nodes[b]);if(d<bestD){bestD=d;best=a to b}}
            if(best==null){val sizes=HashMap<Int,Double>();for(e in edges)sizes[comp[e.u]]=(sizes[comp[e.u]]?:0.0)+e.length
                val keep=sizes.maxByOrNull{it.value}!!.key;edges.retainAll{comp[it.u]==keep};break}
            connect(best.first,best.second)}
        // A loop left open (at its car park): its two ends close together, far apart along the line.
        val ends=nodes.indices.filter{n->degree(n,edges)==1}
        val pairs=ends.flatMap{a->ends.filter{it>a}.map{b->Triple(a,b,DocTracks.meters(nodes[a],nodes[b]))}}.filter{it.third<=LOOP_GAP_METERS}.sortedBy{it.third}
        for((a,b,d) in pairs){if(degree(a,edges)!=1||degree(b,edges)!=1)continue
            if(distances(nodes.size,edges,a)[b]>LOOP_FACTOR*d+1000)connect(a,b)}
        // Dead-end branches are side trips: what is left is the loop.
        val core=ArrayList(edges)
        while(true){val dead=core.filter{e->degree(e.u,core)==1||degree(e.v,core)==1};if(dead.isEmpty())break;core.removeAll(dead.toSet())}
        val loop=cycle(core)
        // More than one loop (alternative ways, a figure of eight): DOC's pieces end to
        // end as it lists them, from where its line starts.
        if(loop==null&&core.isNotEmpty())return DocTracks.chain(parts,start,GAP_METERS)
        val longest=longest(nodes,edges,start)
        if(loop==null)return longest
        val (round,stemmed)=aroundLoop(loop,core,nodes,edges,parts,start)
        // DOC's stated distance decides between, simplest first: the loop, the loop
        // with an access track walked in and out from where DOC's line starts, the
        // longest way from end to end, and the loop from another dead end off it (a
        // car park): the first within [TARGET_SHARE] of it (a side trip's dead end,
        // such as Iris Burn Falls, is not taken as the access when the loop already
        // fits), else the nearest.
        if(target!=null&&target>0){
            val tried=listOfNotNull(round,stemmed,longest)+accessRoutes(loop,core,nodes,edges,parts)
            fun error(l:List<Coordinate>)=abs(DocTracks.length(l)-target)/target
            return tried.firstOrNull{error(it)<=TARGET_SHARE}?:tried.minByOrNull{error(it)}!!
        }
        return if(DocTracks.length(loop)>=LOOP_SHARE*edges.sumOf{it.length})round else longest
    }
    /** For each dead end off [loop] (at most [ACCESS_CANDIDATES], the furthest
     * first): in from it to the loop, round, and back out. */
    private fun accessRoutes(loop:List<Coordinate>,core:List<Edge>,nodes:List<Coordinate>,edges:List<Edge>,parts:List<List<Coordinate>>):List<List<Coordinate>>{
        val onLoop=core.flatMap{listOf(it.u,it.v)}.toSet()
        return nodes.indices.filter{n->n !in onLoop&&degree(n,edges)==1}.mapNotNull{end->
            val (dist,via)=search(nodes.size,edges,end)
            val join=onLoop.filter{dist[it]<Double.MAX_VALUE}.minByOrNull{dist[it]}?:return@mapNotNull null
            dist[join] to walk(nodes,via,end,join)}
            .sortedByDescending{it.first}.take(ACCESS_CANDIDATES).map{(_,stem)->
                aroundLoop(loop,core,nodes,edges,parts,stem.first()).second?:(stem+loop+stem.reversed())}
    }
    /** [loop] from where DOC's line starts, the way DOC's line goes round it; and,
     * when DOC's line starts away from it (a car park at the end of an access track),
     * in along that track, round, and back out (else null). */
    private fun aroundLoop(loop:List<Coordinate>,core:List<Edge>,nodes:List<Coordinate>,edges:List<Edge>,parts:List<List<Coordinate>>,start:Coordinate):Pair<List<Coordinate>,List<Coordinate>?>{
        val first=nodes.indices.filter{n->edges.any{it.u==n||it.v==n}}.minByOrNull{DocTracks.meters(nodes[it],start)}!!
        val onLoop=core.flatMap{listOf(it.u,it.v)}.toSet()
        var stem=emptyList<Coordinate>()
        if(first !in onLoop&&DocTracks.distanceTo(loop,nodes[first])>MERGE_METERS){
            val (dist,via)=search(nodes.size,edges,first)
            val join=onLoop.filter{dist[it]<Double.MAX_VALUE}.minByOrNull{dist[it]}
            if(join!=null)stem=walk(nodes,via,first,join)
        }
        fun rotate(from:Coordinate):List<Coordinate>{
            val at=loop.indices.minByOrNull{DocTracks.meters(loop[it],from)}!!
            val round=loop.subList(at,loop.size)+loop.subList(1,at+1)
            // The way round DOC's line goes: its first piece on the loop early on, not at the end.
            val probe=parts.firstOrNull{p->DocTracks.distanceTo(round,p[p.size/2])<MERGE_METERS}?.let{it[it.size/2]}?:return round
            return if(DocTracks.along(round,probe)>DocTracks.length(round)/2)round.reversed()else round
        }
        val round=rotate(start)
        val stemmed=if(stem.size>=2)rotate(stem.last()).let{r->stem+r.drop(1)+stem.reversed().drop(1)}else null
        return round to stemmed
    }
    /** Distance from [p] to [line], how far along it, and the point there. */
    private fun nearest(line:List<Coordinate>,p:Coordinate):Triple<Double,Double,Coordinate>{
        var best=Double.MAX_VALUE;var along=0.0;var at=line[0];var walked=0.0
        val k=cos(Math.toRadians(p.latitude))*111_320.0;val m=111_320.0
        for(i in 1 until line.size){val a=line[i-1];val b=line[i];val len=DocTracks.meters(a,b)
            val ax=(a.longitude-p.longitude)*k;val ay=(a.latitude-p.latitude)*m;val dx=(b.longitude-a.longitude)*k;val dy=(b.latitude-a.latitude)*m
            val l=dx*dx+dy*dy;val t=if(l==0.0)0.0 else(-(ax*dx+ay*dy)/l).coerceIn(0.0,1.0)
            val d=hypot(ax+dx*t,ay+dy*t)
            if(d<best){best=d;along=walked+len*t;at=Coordinate(a.longitude+(b.longitude-a.longitude)*t,a.latitude+(b.latitude-a.latitude)*t)}
            walked+=len}
        return Triple(best,along,at)
    }
    /** [line] cut at the distances [cuts] along it. */
    private fun split(line:List<Coordinate>,cuts:List<Double>):List<List<Coordinate>>{
        if(cuts.isEmpty())return listOf(line)
        val out=ArrayList<List<Coordinate>>();var current=arrayListOf(line[0]);var walked=0.0;val at=cuts.sorted().distinct().toMutableList()
        for(i in 1 until line.size){val a=line[i-1];val b=line[i];val len=DocTracks.meters(a,b)
            while(at.isNotEmpty()&&at[0]<=walked+len){val t=if(len==0.0)0.0 else((at.removeAt(0)-walked)/len).coerceIn(0.0,1.0)
                val c=Coordinate(a.longitude+(b.longitude-a.longitude)*t,a.latitude+(b.latitude-a.latitude)*t)
                current.add(c);if(current.size>=2)out.add(current);current=arrayListOf(c)}
            current.add(b);walked+=len}
        if(current.size>=2)out.add(current)
        return out.filter{DocTracks.length(it)>0}
    }
    private fun degree(n:Int,edges:List<Edge>)=edges.count{it.u==n}+edges.count{it.v==n}
    private fun components(n:Int,edges:List<Edge>):IntArray{
        val root=IntArray(n){it};fun find(v:Int):Int{var r=v;while(root[r]!=r)r=root[r];return r}
        for(e in edges)root[find(e.u)]=find(e.v)
        val used=edges.flatMap{listOf(it.u,it.v)}.toSet()
        return IntArray(n){if(it in used)find(it)else -1-it}
    }
    /** Metres along [edges] from [from] to every node (Dijkstra), and the edge taken into each. */
    private fun search(n:Int,edges:List<Edge>,from:Int):Pair<DoubleArray,Array<Edge?>>{
        val dist=DoubleArray(n){Double.MAX_VALUE};val via=arrayOfNulls<Edge>(n);dist[from]=0.0
        val queue=java.util.PriorityQueue<Pair<Double,Int>>(compareBy{it.first});queue.add(0.0 to from)
        while(queue.isNotEmpty()){val (d,u)=queue.poll();if(d>dist[u])continue
            for(e in edges){val v=if(e.u==u)e.v else if(e.v==u)e.u else continue;if(d+e.length<dist[v]){dist[v]=d+e.length;via[v]=e;queue.add(dist[v] to v)}}}
        return dist to via
    }
    private fun distances(n:Int,edges:List<Edge>,from:Int)=search(n,edges,from).first
    /** A walk from one end to another: its longest path, from the end nearer [start]. */
    private fun longest(nodes:List<Coordinate>,edges:List<Edge>,start:Coordinate):List<Coordinate>{
        val used=edges.flatMap{listOf(it.u,it.v)}.toSet().toList();if(used.isEmpty())return emptyList()
        fun far(from:Int)=search(nodes.size,edges,from).first.let{d->used.filter{d[it]<Double.MAX_VALUE}.maxByOrNull{d[it]}!!}
        val a=far(used[0]);val b=far(a)
        val (from,to)=if(DocTracks.meters(nodes[a],start)<=DocTracks.meters(nodes[b],start))a to b else b to a
        return walk(nodes,search(nodes.size,edges,from).second,from,to)
    }
    /** The points from [from] to [to] by the edges [via] (from a search from [from]). */
    private fun walk(nodes:List<Coordinate>,via:Array<Edge?>,from:Int,to:Int):List<Coordinate>{
        val steps=ArrayList<List<Coordinate>>();var v=to
        while(v!=from){val e=via[v]?:break;val back=e.v==v;steps.add(if(back)e.points else e.points.reversed());v=if(back)e.u else e.v}
        val out=ArrayList<Coordinate>();for(s in steps.asReversed()){if(out.isEmpty())out.addAll(s)else out.addAll(s.drop(1))}
        return out.ifEmpty{listOf(nodes[from])}
    }
    /** The closed line round [core] when it is one simple loop (every node on two of its edges), else null. */
    private fun cycle(core:List<Edge>):List<Coordinate>?{
        if(core.isEmpty())return null
        val nodes=core.flatMap{listOf(it.u,it.v)}.toSet()
        if(nodes.any{degree(it,core)!=2})return null
        val left=core.toMutableList();val first=left.removeAt(0)
        val out=ArrayList(first.points);var at=first.v
        while(left.isNotEmpty()){val e=left.firstOrNull{it.u==at||it.v==at}?:return null;left.remove(e)
            val pts=if(e.u==at)e.points else e.points.reversed();out.addAll(pts.drop(1));at=if(e.u==at)e.v else e.u}
        return if(at==first.u)out else null
    }
}
