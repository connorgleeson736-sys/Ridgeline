package nz.ridgeline.nativeapp

import kotlin.math.*

/** A DOC walk as DOC describes it: its legs ("Makarora to Young Hut", "Young
 * Hut to Siberia Hut"…) followed in order over DOC's walk line. DOC's line can
 * include side trips and extensions (the Gillespie Pass Circuit's line runs
 * on up the Wilkin to Top Forks Hut); following the legs gives the walk
 * itself, in DOC's direction, with each leg's span for its time, distance and
 * text. Leg ends are placed with the bundled gazetteer; walk ends that are
 * localities ("Makarora") take the line end that best fits the leg's
 * distance. Pure Kotlin, unit tested on real DOC data. */
internal object WalkRoutes {
    class Section(val title:String,val time:String,val km:Double?,val from:Double,val to:Double,val blocks:List<DocPages.Block>)
    class Route(val points:List<Coordinate>,val sections:List<Section>){val meters get()=DocTracks.length(points)}

    /** A leg's title as its two ends: "Lewis Pass - Cannibal Gorge Hut - Ada
     * Pass Hut" → "Lewis Pass to Ada Pass Hut". */
    fun title(heading:String):String{
        val stops=(if(Regex("\\s+to\\s+",RegexOption.IGNORE_CASE).containsMatchIn(heading))heading.split(Regex("\\s+to\\s+",RegexOption.IGNORE_CASE))else heading.split(Regex("\\s+[-–—]\\s+"))).map{it.trim()}.filter{it.isNotEmpty()}
        return if(stops.size<2)heading else plain(stops.first())+" to "+plain(stops.last())
    }
    /** "Kerin Forks to Makarora – SH6" ends at "Makarora". */
    fun plain(name:String)=name.replace(Regex("\\s+via\\s+.*$",RegexOption.IGNORE_CASE),"").replace(Regex("\\s+[–—-]\\s+.*$"),"").replace(Regex("\\s*\\(.*?\\)"),"").trim()
    /** "Kerin Forks Hut" and "Kerin Forks" are one leg end. */
    private fun same(a:String,b:String)=DocTracks.hutKey(PlaceSearch.normalize(plain(a)))==DocTracks.hutKey(PlaceSearch.normalize(plain(b)))
    /** The legs walked, in order: from the first leg, each next leg starts
     * where the last ended. Where DOC describes alternatives from one place
     * (a summary leg "Siberia Hut to Makarora" and its parts, the coastal
     * and inland endings of the Abel Tasman), a leg to somewhere not yet
     * visited is preferred, then the shorter (finer) one. */
    fun walked(legs:List<DocPages.Leg>):List<DocPages.Leg>{
        if(legs.isEmpty())return emptyList()
        val out=arrayListOf(legs[0]);val visited=HashSet(legs[0].stops.map(::key));var index=0
        while(out.size<=legs.size){
            val end=out.last().to
            val next=legs.withIndex().filter{(i,l)->i>index&&same(l.from,end)}
            if(next.isEmpty())break
            val pick=next.filter{key(it.value.to) !in visited}.ifEmpty{next}.minWith(compareBy({it.value.km?:hours(it.value.time)*4.0},{it.index}))
            out.add(pick.value);visited.addAll(pick.value.stops.map(::key));index=pick.index
        }
        return out
    }
    private fun key(name:String)=DocTracks.hutKey(PlaceSearch.normalize(plain(name)))
    /** "3 hr 30 min", "9–10 hours one way" → hours (the first figure), for comparing legs without distances. */
    fun hours(time:String)=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–]\\s*[0-9.]+\\s*)?(h|hr|hrs|hour|hours)\\b",RegexOption.IGNORE_CASE).find(time)?.groupValues?.get(1)?.toDoubleOrNull()
        ?:Regex("([0-9]+)\\s*min",RegexOption.IGNORE_CASE).find(time)?.groupValues?.get(1)?.toDoubleOrNull()?.div(60)?:Double.MAX_VALUE/8

    private class Graph(paths:List<List<Coordinate>>){
        val points=ArrayList<Coordinate>();val edges=ArrayList<MutableList<Pair<Int,Double>>>()
        /** Ends of DOC's pieces: where a walk can start or end (an end joined to
         * a short stub, as at the Heaphy's Kohaihai end, is still an end). */
        val ends=ArrayList<Int>()
        /** [cost]: a gap between DOC's pieces counts more than track, so it is crossed only where needed. */
        fun link(a:Int,b:Int,cost:Double=1.0,extra:Double=0.0){if(a==b)return;val d=DocTracks.meters(points[a],points[b])*cost+extra;edges[a].add(b to d);edges[b].add(a to d)}
        init{
            val pathOf=ArrayList<Int>()
            for((pi,p) in paths.withIndex()){val start=points.size
                for(c in p){points.add(c);edges.add(ArrayList());pathOf.add(pi)}
                for(i in start+1 until points.size)link(i-1,i)
                if(p.isNotEmpty()){ends.add(start);ends.add(points.size-1)}
            }
            // Pieces of DOC's line meet at junctions: each end joins the nearest point
            // of another piece. Further gaps (a road section between two pieces)
            // are bridged too, at a cost that keeps the route on track elsewhere.
            for(e in ends){var best=-1;var bestD=GAP_METERS
                for(i in points.indices)if(pathOf[i]!=pathOf[e]){val d=DocTracks.meters(points[e],points[i]);if(d<bestD){bestD=d;best=i}}
                if(best>=0)if(bestD<=JOIN_METERS)link(e,best)else link(e,best,3.0,500.0)}
        }
        val deadEnds get()=ends.distinct()
        fun nearest(c:Coordinate,within:Double)=points.indices.minByOrNull{DocTracks.meters(points[it],c)}?.takeIf{DocTracks.meters(points[it],c)<=within}
        /** Distances and previous points from [source] (Dijkstra). */
        fun from(source:Int):Pair<DoubleArray,IntArray>{
            val dist=DoubleArray(points.size){Double.MAX_VALUE};val prev=IntArray(points.size){-1};dist[source]=0.0
            val queue=java.util.PriorityQueue<Pair<Double,Int>>(compareBy{it.first});queue.add(0.0 to source)
            while(queue.isNotEmpty()){val (d,u)=queue.poll();if(d>dist[u])continue
                for((v,w) in edges[u])if(d+w<dist[v]){dist[v]=d+w;prev[v]=u;queue.add(dist[v] to v)}}
            return dist to prev
        }
        fun path(prev:IntArray,to:Int):List<Int>{val out=ArrayList<Int>();var v=to;while(v>=0){out.add(v);v=prev[v]};return out.reversed()}
    }
    /** Ends of DOC line pieces join another piece this close (Kerin Forks Hut sits 400 m across the Wilkin from the main line). */
    const val JOIN_METERS=600.0
    /** DOC's legs give the profile's route only when they cover this share of
     * DOC's line (DOC describes only the Rees side of the Rees-Dart while the
     * Dart is closed by a slip: the walk is still DOC's whole line). */
    const val ROUTE_SHARE=.6
    /** The walk's main line when DOC's legs do not give one: the longest of the
     * shortest paths between ends of DOC's line (its pieces joined as for
     * routes), so side trips are left out where the line has them. */
    fun mainLine(paths:List<List<Coordinate>>):List<Coordinate>{
        val g=Graph(paths.filter{it.size>=2});if(g.points.size<2)return emptyList()
        val ends=g.deadEnds.ifEmpty{listOf(0)}
        var best:List<Int>?=null;var bestD=-1.0
        for(a in ends){val (dist,prev)=g.from(a)
            for(b in ends)if(b!=a&&dist[b]<Double.MAX_VALUE&&dist[b]>bestD){bestD=dist[b];best=g.path(prev,b)}}
        return (best?:listOf(0)).map{g.points[it]}.takeIf{it.size>=2}?:paths.maxByOrNull{DocTracks.length(it)}.orEmpty()
    }
    /** The length DOC gives the walk on its page ("53.5 km one way", "80 km
     * loop", "16 km return via same track": half, the line is walked twice), in metres. */
    fun stated(distance:String):Double?{
        val km=Regex("([0-9]+(?:[.,][0-9]+)?)\\s*km",RegexOption.IGNORE_CASE).find(distance)?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()?:return null
        return km*1000/(if(Regex("\\breturn\\b",RegexOption.IGNORE_CASE).containsMatchIn(distance)&&!Regex("\\bloop\\b",RegexOption.IGNORE_CASE).containsMatchIn(distance))2 else 1)
    }
    /** The walk itself, of DOC's line: DOC's legs (when they cover most of the
     * line), the line's longest end-to-end path (no side trips) or the whole
     * line (pieces DOC's legs and paths do not join up) — the one nearest the
     * length DOC states on the walk's page; without one, the legs or path.
     * Second: whether it is DOC's legs (DOC's direction). */
    fun extent(route:Route?,paths:List<List<Coordinate>>,distance:String=""):Pair<List<List<Coordinate>>,Boolean>{
        val main=mainLine(paths);val whole=paths.filter{it.size>=2}
        val legsOk=route!=null&&route.meters>=ROUTE_SHARE*max(1.0,DocTracks.length(main))
        val target=stated(distance)
        if(target==null)return if(legsOk)listOf(route!!.points) to true else listOf(main) to false
        val options=listOfNotNull(route?.let{listOf(it.points) to true},listOf(main) to false,whole to false).filter{(p,_)->p.isNotEmpty()&&p.sumOf{l->DocTracks.length(l)}>0}
        fun error(o:Pair<List<List<Coordinate>>,Boolean>)=abs(o.first.sumOf{DocTracks.length(it)}-target)/target
        // DOC's legs give the direction: kept when about as near as the others.
        val best=options.minByOrNull(::error)?:return listOf(main) to false
        val legs=options.firstOrNull{it.second}
        return if(legs!=null&&error(legs)<=error(best)+.1)legs else best
    }
    /** [extent] as one line for a summary (pieces joined end to end). */
    fun walkLine(route:Route?,paths:List<List<Coordinate>>,distance:String=""):List<Coordinate>{
        val (lines,_)=extent(route,paths,distance)
        return if(lines.size==1)lines[0]else DocTracks.chain(lines,lines.first().first(),3000.0)
    }
    /** A leg end this close to DOC's line is on it (huts can sit across a river). */
    const val SNAP_METERS=2500.0
    /** A route must be within this share of DOC's own leg distances. */
    const val MATCH=.25
    /** Larger gaps between DOC's pieces are bridged only at a cost. */
    const val GAP_METERS=4000.0
    /** Walk ends that are localities are looked for this far away. */
    const val END_METERS=12000.0

    /** The walk over [paths] (DOC's line) following [legs]; [locate] places a
     * name near a point (null if unknown). Null when the legs cannot be followed. */
    /** Why the last [route] was not used (tests and surveys). */
    @Volatile var lastFailure="";private set
    private fun fail(reason:String):Route?{lastFailure=reason;return null}
    fun route(paths:List<List<Coordinate>>,legs:List<DocPages.Leg>,locate:(String,Coordinate)->Coordinate?):Route?{
        val walked=walked(legs);if(walked.isEmpty()||paths.isEmpty())return fail("no legs or line")
        // Legs that do not chain (DOC names a place two ways) give part of the walk: not used.
        if(walked.size==1&&legs.size>=3)return fail("legs do not chain")
        val g=Graph(paths.filter{it.size>=2});if(g.points.size<2)return fail("no line")
        val all=g.points;val center=Coordinate((all.minOf{it.longitude}+all.maxOf{it.longitude})/2,(all.minOf{it.latitude}+all.maxOf{it.latitude})/2)
        val names=walked[0].stops.take(1)+walked.flatMap{it.stops.drop(1)}
        val places=names.map{locate(plain(it),center)}
        // Middle leg ends: the nearest point of the line (unknown ones are skipped).
        val stops=arrayOfNulls<Int>(names.size)
        for(i in 1 until names.size-1)stops[i]=places[i]?.let{g.nearest(it,SNAP_METERS)}
        fun end(i:Int,neighbour:Int?,km:Double?):Int?{
            val place=places[i]
            // Neither placed nor measured: not guessed (the route starts or ends at the next known place).
            if(place==null&&km==null)return null
            val near=place?.let{g.nearest(it,SNAP_METERS)}
            val candidates=(g.deadEnds.filter{place==null||DocTracks.meters(g.points[it],place)<=END_METERS}+listOfNotNull(near)).distinct()
            if(candidates.isEmpty())return near
            val reach=neighbour?.let{g.from(it).first}
            return candidates.minByOrNull{c->
                val off=place?.let{DocTracks.meters(g.points[c],it)}?:0.0
                if(reach!=null&&km!=null&&reach[c]<Double.MAX_VALUE)abs(reach[c]-km*1000)+.2*off else off}
        }
        val firstNext=(1 until names.size).firstNotNullOfOrNull{stops.getOrNull(it)}
        stops[0]=end(0,firstNext,walked.first().km)
        val lastPrev=(names.size-2 downTo 0).firstNotNullOfOrNull{stops.getOrNull(it)}
        stops[names.size-1]=end(names.size-1,lastPrev,walked.last().km)
        val order=stops.indices.filter{stops[it]!=null}
        if(order.size<2)return fail("fewer than two places on the line")
        val route=ArrayList<Int>();val along=HashMap<Int,Double>()
        var walkedM=0.0
        for(k in 1 until order.size){
            val a=stops[order[k-1]]!!;val b=stops[order[k]]!!
            val (dist,prev)=g.from(a);if(dist[b]==Double.MAX_VALUE)return fail("no way between two places")
            val piece=g.path(prev,b)
            if(k==1){route.addAll(piece);along[order[0]]=0.0}else route.addAll(piece.drop(1))
            walkedM+=dist[b];along[order[k]]=walkedM
        }
        // A walk does not go back the way it came: a route that retraces much
        // of itself has a wrong end ("Heaphy Hut via Lewis Shelter" unplaced).
        if(route.size-route.toSet().size>route.size*.1)return fail("retraces itself (${route.size-route.toSet().size} of ${route.size} points; places ${order.map{k->names[k]+" "+g.points[stops[k]!!].let{c->"%.3f,%.3f".format(c.longitude,c.latitude)}}})")
        val points=route.map{g.points[it]}
        // Sections: every leg DOC describes (summary legs too) between its ends' places on the route.
        val sections=ArrayList<Section>();var cursor=0
        for(l in legs){
            val i=(cursor until names.size).firstOrNull{same(names[it],l.from)&&it in along}?:(0 until names.size).firstOrNull{same(names[it],l.from)&&it in along}?:continue
            val j=(i+1 until names.size).firstOrNull{same(names[it],l.to)&&it in along}?:continue
            sections.add(Section(l.title,l.time,l.km,along[i]!!,along[j]!!,l.blocks));cursor=i
        }
        // DOC's distances check the result: a route far from them (wrong ends,
        // a leg end placed wrongly) is not used, and neither is a leg span
        // far from its own distance.
        val km=walked.map{it.km}
        val meters=DocTracks.length(points)
        if(meters<200)return fail("too short")
        if(km.all{it!=null}&&abs(meters/1000-km.sumOf{it!!})>MATCH*km.sumOf{it!!})return fail("total far from DOC's distances")
        val measured=sections.filter{it.km!=null}
        val fit=measured.filter{s->abs((s.to-s.from)/1000-s.km!!)<=max(1.5,.5*s.km)}
        if(measured.size>=2&&fit.size*3<measured.size*2)return fail("legs far from DOC's distances")
        return Route(points,sections.filter{s->s.km==null||s in fit})
    }
}
