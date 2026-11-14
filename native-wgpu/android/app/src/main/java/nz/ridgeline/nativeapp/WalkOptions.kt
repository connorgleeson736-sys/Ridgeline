package nz.ridgeline.nativeapp

import kotlin.math.*

/** Where a DOC walk can start and end, followed over the map's own tracks
 * (LINZ, whatever each piece is called). DOC often describes a walk as
 * alternatives ("Tiki Trail to Ben Lomond Summit", "Tiki Trail to Ben Lomond
 * Saddle", "Skyline Gondola top complex to Ben Lomond Saddle"); its walk line
 * can stop short (the Ben Lomond line ends at the saddle), and the map
 * changes a track's name part way (Tiki Track, Ben Lomond Walkway, Ben Lomond
 * Track). Each leg's ends are found by name (the gazetteer's places and peaks,
 * the map's tracks, peaks and gondolas), the leg is the shortest way between
 * them over the map's tracks, and it is kept only within [MATCH] of DOC's own
 * distance for it. Pure Kotlin, unit tested on real data. */
internal object WalkOptions {
    /** The map around a walk: tracks (name, line), aerial lifts and named peaks. */
    /** [box]: the area read (longitude, latitude bounds), if not the whole map. */
    class Area(val tracks:List<Pair<String,List<Coordinate>>>,val lifts:List<List<Coordinate>> = emptyList(),val peaks:List<Pair<String,Coordinate>> = emptyList(),val box:DoubleArray?=null,val roads:List<Pair<String,List<Coordinate>>> = emptyList()){
        /** Within [m] metres of the edge of the area read (where the map's tracks are cut off). */
        fun atEdge(c:Coordinate,m:Double):Boolean{val b=box?:return false;val dy=m/111_320.0;val dx=dy/max(.2,cos(Math.toRadians(c.latitude)))
            return c.longitude<b[0]+dx||c.longitude>b[2]-dx||c.latitude<b[1]+dy||c.latitude>b[3]-dy}
    }
    /** One way to walk it: from start [start] to end [end] (indexes into the
     * choice's names) along [line], DOC's [leg] when DOC describes it, and the
     * map's named tracks it follows. */
    /** [time]: DOC's time for this way (compiled walks); [stops]: places named on it and where they are along [line]. */
    class Option(val start:Int,val end:Int,val line:List<Coordinate>,val leg:DocPages.Leg?,val pieces:List<Pair<String,List<Coordinate>>>,
                 val time:String="",val stops:List<Pair<String,Double>> = emptyList())
    class Choice(val starts:List<String>,val ends:List<String>,val options:List<Option>){
        fun option(s:Int,e:Int)=options.firstOrNull{it.start==s&&it.end==e}
    }
    /** Adds one zoom-14 map tile's tracks (clipped to it), aerial lifts and named peaks to the lists. */
    fun read(id:GpuUploadQueue.Id,features:List<VectorTile.Feature>,tracks:MutableList<Pair<String,List<Coordinate>>>,lifts:MutableList<List<Coordinate>>,peaks:MutableList<Pair<String,Coordinate>>,roads:MutableList<Pair<String,List<Coordinate>>>?=null){
        fun c(p:VectorTile.Point)=Coordinate(p.x*360-180,CameraMath.latitude(p.y))
        for(f in features)when{
            roads!=null&&f.layer=="streets"&&f.properties["kind"]!="track"&&f.properties["name"] is String->for(path in f.paths)for(part in BoundaryIndex.clip(path,id.bounds))if(part.size>=2)roads.add((f.properties["name"] as String) to part.map(::c))
            f.layer=="streets"&&f.properties["kind"]=="track"->for(path in f.paths)for(part in BoundaryIndex.clip(path,id.bounds))if(part.size>=2)tracks.add((f.properties["name"] as? String).orEmpty() to part.map(::c))
            f.layer=="aerialways"->for(path in f.paths)if(path.size>=2)lifts.add(path.map(::c))
            f.layer=="place_labels"&&f.properties["natural"]=="peak"->{val n=(f.properties["name"] as? String)?:(f.properties["label"] as? String);val p=f.paths.firstOrNull()?.firstOrNull();if(!n.isNullOrBlank()&&p!=null)peaks.add(n to c(p))}
        }
    }
    /** Map layers [read] uses. */
    val LAYERS=setOf("streets","aerialways","place_labels")
    /** A route must be within this share of DOC's distance for the leg. */
    const val MATCH=.25
    /** Track points this close are one point. */
    const val SNAP_METERS=2.0
    /** A track's dead end this close to another track joins it. */
    const val JOIN_METERS=15.0
    /** A fallback line's end this close to track far round by the network is joined to it (see Network). */
    const val CROSSING_METERS=400.0
    /** A track's dead end this close to another track joins it, at a cost (see Network). */
    const val JOIN_FAR_METERS=80.0
    /** Separate parts of the map's tracks are joined across gaps this short. */
    const val BRIDGE_METERS=200.0
    /** A place found by name this close to a track is reached by it. */
    const val NEAR_METERS=400.0
    /** Walk ends DOC names only by the walk ("Ben Lomond Saddle") are the ends of DOC's line this close to a track. */
    const val LINE_END_METERS=150.0

    /** The map's tracks as a graph: points joined along each track; tracks
     * meet where they share a point or where one ends within [JOIN_METERS] of another. */
    /** [cost]: per track, how much each metre of it counts for routing (1:
     * the map's tracks; more for a fallback line such as DOC's own, used only
     * where the map has no track). Distances measured on lines stay in metres. */
    /** [gap]: per track, the longest gap from its dead ends that is bridged to
     * another part of the network ([BRIDGE_METERS] for the map's tracks; more
     * for DOC's own line, which crosses tidal inlets and fords the map has no track over). */
    class Network(val tracks:List<Pair<String,List<Coordinate>>>,val cost:(Int)->Double={1.0},val gap:(Int)->Double={BRIDGE_METERS}){
        val points=ArrayList<Coordinate>()
        private val dead=ArrayList<Int>()
        /** Each track piece's two end points (point, track index). */
        private val pieceEnds=ArrayList<Pair<Int,Int>>()
        /** Per point: (neighbour, metres, track index). */
        val edges=ArrayList<MutableList<Triple<Int,Double,Int>>>()
        private val lat0=tracks.firstOrNull{it.second.isNotEmpty()}?.second?.first()?.latitude?:0.0
        private val kx=111_320.0*cos(Math.toRadians(lat0));private val ky=110_574.0
        private val cells=HashMap<Long,MutableList<Int>>()
        private fun cell(x:Double,y:Double)=(floor(x/CELL).toLong() shl 32) or (floor(y/CELL).toLong() and 0xffffffffL)
        private fun x(c:Coordinate)=c.longitude*kx;private fun y(c:Coordinate)=c.latitude*ky
        private fun node(c:Coordinate):Int{
            val px=x(c);val py=y(c)
            for(dx in -1..1)for(dy in -1..1)for(i in cells[cell(px+dx*CELL,py+dy*CELL)]?:continue)
                if(hypot(x(points[i])-px,y(points[i])-py)<=SNAP_METERS)return i
            points.add(c);edges.add(ArrayList());cells.getOrPut(cell(px,py)){ArrayList()}.add(points.size-1);return points.size-1
        }
        private fun link(a:Int,b:Int,m:Double,track:Int){if(a==b)return;edges[a].add(Triple(b,m,track));edges[b].add(Triple(a,m,track))}
        init{
            val segments=ArrayList<IntArray>()
            for((ti,t) in tracks.withIndex()){var prev=-1;var first=-1
                for(c in t.second){val v=node(c);if(first<0)first=v;if(prev>=0&&prev!=v){link(prev,v,DocTracks.meters(points[prev],c)*cost(ti),ti);segments.add(intArrayOf(prev,v,ti))};prev=v}
                if(first>=0){pieceEnds.add(first to ti);pieceEnds.add(prev to ti)}}
            // A track ending at another without sharing a point (a T junction):
            // joined to the nearest point of that track's segment.
            val bySeg=HashMap<Long,MutableList<Int>>()
            for((si,s) in segments.withIndex()){val a=points[s[0]];val b=points[s[1]]
                for(cx in floor(min(x(a),x(b))/CELL).toInt()..floor(max(x(a),x(b))/CELL).toInt())for(cy in floor(min(y(a),y(b))/CELL).toInt()..floor(max(y(a),y(b))/CELL).toInt())
                    bySeg.getOrPut((cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)){ArrayList()}.add(si)}
            // The tracks' own dead ends, before any joins (gaps are bridged from these below).
            dead.addAll(points.indices.filter{edges[it].size==1})
            for(v in dead.toList()){if(edges[v].size!=1)continue
                val own=edges[v][0].third;val px=x(points[v]);val py=y(points[v])
                // Not back onto the track it ends (a stub left where a tile edge cuts a
                // track, the Tiki Track's at the gondola top): points within two steps.
                val close=HashSet<Int>();close.add(v);for(a in edges[v]){close.add(a.first);for(b in edges[a.first])close.add(b.first)}
                var best=-1;var bestD=JOIN_FAR_METERS;var bestT=0.0
                val reachCells=ceil(JOIN_FAR_METERS/CELL).toInt()
                for(dx in -reachCells..reachCells)for(dy in -reachCells..reachCells)for(si in bySeg[cell(px+dx*CELL,py+dy*CELL)]?:continue){val s=segments[si]
                    if(s[2]==own||s[0] in close||s[1] in close)continue
                    val ax=x(points[s[0]]);val ay=y(points[s[0]]);val bx=x(points[s[1]]);val by=y(points[s[1]])
                    val l=(bx-ax)*(bx-ax)+(by-ay)*(by-ay);val t=if(l==0.0)0.0 else(((px-ax)*(bx-ax)+(py-ay)*(by-ay))/l).coerceIn(0.0,1.0)
                    val d=hypot(ax+(bx-ax)*t-px,ay+(by-ay)*t-py);if(d<bestD){bestD=d;best=si;bestT=t}}
                // A join over [JOIN_METERS] (a track stopping short of the one it meets: the
                // Rere Lake Walk 50 m from the Greenstone Track) counts three times its length,
                // so routes use it only to make a connection, never as a short cut.
                if(best>=0){val s=segments[best];val len=DocTracks.meters(points[s[0]],points[s[1]]);val j=if(bestD>JOIN_METERS)bestD*3 else bestD
                    edges[v].add(Triple(s[0],j+len*bestT,s[2]));edges[s[0]].add(Triple(v,j+len*bestT,s[2]))
                    edges[v].add(Triple(s[1],j+len*(1-bestT),s[2]));edges[s[1]].add(Triple(v,j+len*(1-bestT),s[2]))}
            }
        }
        init{
            // Separate parts of the network (the Tiki Track ends at the gondola's
            // top building; the Ben Lomond Walkway starts 100 m on) are joined
            // across the shortest gaps from their dead ends, nearest first.
            val root=IntArray(points.size){it}
            fun find(v:Int):Int{var r=v;while(root[r]!=r)r=root[r];var c=v;while(root[c]!=r){val n=root[c];root[c]=r;c=n};return r}
            for(v in points.indices)for(e in edges[v])root[find(v)]=find(e.first)
            val gaps=ArrayList<Triple<Int,Int,Double>>()
            for(v in dead){
                val limit=edges[v].maxOfOrNull{if(it.third>=0)gap(it.third) else BRIDGE_METERS}?:BRIDGE_METERS
                val px=x(points[v]);val py=y(points[v]);val reach=ceil(limit/CELL).toInt()
                var best=-1;var bestD=limit
                for(dx in -reach..reach)for(dy in -reach..reach)for(i in cells[cell(px+dx*CELL,py+dy*CELL)]?:continue){
                    if(find(i)==find(v))continue;val d=hypot(x(points[i])-px,y(points[i])-py);if(d<bestD){bestD=d;best=i}}
                if(best>=0)gaps.add(Triple(v,best,bestD))}
            for((a,b,d) in gaps.sortedBy{it.third})if(find(a)!=find(b)){root[find(a)]=find(b);edges[a].add(Triple(b,d,-1));edges[b].add(Triple(a,d,-1))}
            // A crossing the map lacks (the stock bridge at the start of the Lake Rere Walk):
            // where a fallback line (DOC's own, bridged further than the map's tracks) ends
            // within [CROSSING_METERS] of track that is far round by the network, the two
            // are joined at three times the distance.
            for((v,t) in pieceEnds){if(gap(t)<=BRIDGE_METERS)continue
                val near=points.indices.filter{it!=v&&DocTracks.meters(points[it],points[v])<=CROSSING_METERS&&edges[it].none{e->e.third==t}}
                if(near.isEmpty())continue
                val (dist,_)=from(v)
                val far=near.filter{dist[it]>5*DocTracks.meters(points[it],points[v])+1000}.minByOrNull{DocTracks.meters(points[it],points[v])}?:continue
                val d=DocTracks.meters(points[far],points[v])*3;edges[v].add(Triple(far,d,-1));edges[far].add(Triple(v,d,-1))}
        }
        fun nearest(c:Coordinate,within:Double):Int?{
            var best=-1;var bestD=within;for(i in points.indices){val d=DocTracks.meters(points[i],c);if(d<bestD){bestD=d;best=i}}
            return best.takeIf{it>=0}
        }
        /** Points within [within] metres of [line]. */
        fun near(line:List<Coordinate>,within:Double):Set<Int>{
            val out=HashSet<Int>();val reach=ceil(within/CELL).toInt()
            for(c in DocTracks.resample(line,max(5.0,within/2),100_000)){val px=x(c);val py=y(c)
                for(dx in -reach..reach)for(dy in -reach..reach)for(i in cells[cell(px+dx*CELL,py+dy*CELL)]?:continue)
                    if(hypot(x(points[i])-px,y(points[i])-py)<=within)out.add(i)}
            return out
        }
        /** Metres and previous points from [source] (Dijkstra). */
        /** [avoid]: points not to pass through; [walked]: steps already taken
         * (key [step]), costing [again] times as much (a loop does not retrace itself). */
        /** [prefer]: tracks (indexes) to keep to ("continue on the Greenstone Track"), counted at [preferred] of their length. */
        fun from(source:Int,avoid:Set<Int> = emptySet(),walked:Set<Long> = emptySet(),again:Double=1.0,prefer:Set<Int> = emptySet(),preferred:Double=.6):Pair<DoubleArray,IntArray>{
            val dist=DoubleArray(points.size){Double.MAX_VALUE};val prev=IntArray(points.size){-1};dist[source]=0.0
            val queue=java.util.PriorityQueue<Pair<Double,Int>>(compareBy{it.first});queue.add(0.0 to source)
            while(queue.isNotEmpty()){val (d,u)=queue.poll();if(d>dist[u])continue
                for((v,w1,t) in edges[u]){if(v in avoid)continue;val w0=if(t in prefer)w1*preferred else w1;val w=if(walked.isNotEmpty()&&step(u,v) in walked)w0*again else w0
                    if(d+w<dist[v]){dist[v]=d+w;prev[v]=u;queue.add(dist[v] to v)}}}
            return dist to prev
        }
        fun step(a:Int,b:Int)=(min(a,b).toLong() shl 32) or max(a,b).toLong()
        fun path(prev:IntArray,to:Int):List<Int>{val out=ArrayList<Int>();var v=to;while(v>=0){out.add(v);v=prev[v]};return out.reversed()}
        /** The named tracks a path of points follows. */
        fun trackNames(path:List<Int>):List<Pair<String,List<Coordinate>>>{
            val used=LinkedHashSet<Int>()
            for(i in 1 until path.size)edges[path[i-1]].firstOrNull{it.first==path[i]&&it.third>=0}?.let{used.add(it.third)}
            return used.map{tracks[it]}
        }
        private companion object{const val CELL=25.0}
    }

    private val LIFT=Regex("\\b(gondola|chairlift|chair lift|cable car|cableway|skyline)\\b",RegexOption.IGNORE_CASE)
    private val BASE=Regex("\\b(base|bottom|lower|terminal)\\b",RegexOption.IGNORE_CASE)
    private val SUMMIT=Regex("\\b(summit|top|trig|trig station)\\b",RegexOption.IGNORE_CASE)
    private val RETURN=Regex("\\breturn\\b",RegexOption.IGNORE_CASE)
    private val ONE_WAY=Regex("\\b(one way|loop)\\b",RegexOption.IGNORE_CASE)
    /** DOC's distance for a leg one way, in metres ("6–8 hr return", 14 km: 7 km). */
    fun oneWay(leg:DocPages.Leg):Double?{val km=leg.km?:return null
        val text=leg.time+" "+leg.title;return km*1000/(if(RETURN.containsMatchIn(text)&&!ONE_WAY.containsMatchIn(text))2 else 1)}
    private fun distanceTo(line:List<List<Coordinate>>,p:Coordinate)=line.minOfOrNull{l->DocTracks.distanceTo(l,p)}?:Double.MAX_VALUE

    /** Where the leg end [name] may be on [net], each way of reading it: a
     * gondola's end (its top: the end nearer DOC's line), a peak ("Ben Lomond
     * Summit": the peak Ben Lomond), the far end of a map track of that name
     * from [toward] ("Tiki Trail": where the Tiki Track starts; "Mystery Lake":
     * the end of the Mystery Lake Track), or a gazetteer place. */
    private fun places(name:String,toward:Coordinate?,doc:List<List<Coordinate>>,centre:Coordinate,net:Network,area:Area,locate:(String,Coordinate)->Coordinate?):List<Int>{
        val clean=WalkRoutes.plain(name);if(clean.isBlank())return emptyList()
        val out=LinkedHashSet<Int>()
        fun on(c:Coordinate?){c?.let{net.nearest(it,NEAR_METERS)}?.let{out.add(it)}}
        if(LIFT.containsMatchIn(clean)&&area.lifts.isNotEmpty()&&doc.isNotEmpty()){
            val ends=area.lifts.flatMap{listOf(it.first(),it.last())}.filter{DocTracks.meters(it,centre)<15_000}
            on(if(BASE.containsMatchIn(clean))ends.maxByOrNull{distanceTo(doc,it)}else ends.minByOrNull{distanceTo(doc,it)})
        }
        val bare=clean.replace(SUMMIT,"").replace(Regex("\\s+")," ").trim()
        val names=listOf(clean,bare).filter{it.isNotBlank()}.distinct()
        for(n in names)on(area.peaks.filter{(p,_)->PlaceSearch.normalize(p)==PlaceSearch.normalize(n)}.minByOrNull{DocTracks.meters(it.second,centre)}?.second)
        val named=area.tracks.filter{it.first.isNotBlank()&&DocWalks.sameName(it.first,clean)}
        if(named.isNotEmpty()){val ref=toward?:centre;on(named.flatMap{listOf(it.second.first(),it.second.last())}.maxByOrNull{DocTracks.meters(it,ref)})}
        for(n in names)on(locate(n,centre))
        return out.toList()
    }
    /** A place a leg goes through ("via Cone Ridge"; "via the Maketawa Track": its middle). */
    private fun through(name:String,doc:List<List<Coordinate>>,centre:Coordinate,net:Network,area:Area,locate:(String,Coordinate)->Coordinate?):Int?{
        val clean=WalkRoutes.plain(name);if(clean.isBlank())return null
        area.tracks.filter{it.first.isNotBlank()&&DocWalks.sameName(it.first,clean)}.maxByOrNull{DocTracks.length(it.second)}
            ?.let{t->return net.nearest(DocTracks.pointAt(t.second,DocTracks.length(t.second)/2),30.0)}
        return locate(clean,centre)?.let{net.nearest(it,NEAR_METERS)}
    }

    /** Why legs were not used in the last [choose] (surveys). */
    val notes=StringBuilder();private fun note(s:String){notes.append(s).append('\n')}
    /** A loop's line is closed when its ends are this close. */
    const val CLOSED_METERS=200.0
    /** [line] turned to go clockwise ([clockwise] true) or anticlockwise, as DOC describes; unchanged when null. */
    fun turn(line:List<Coordinate>,clockwise:Boolean?):List<Coordinate>{
        if(clockwise==null||line.size<3)return line
        val k=cos(Math.toRadians(line[0].latitude));var a=0.0
        for(i in line.indices){val p=line[i];val q=line[(i+1)%line.size];a+=p.longitude*k*q.latitude-q.longitude*k*p.latitude}
        // Positive area: anticlockwise (east to the right, north up).
        return if((a<0)==clockwise)line else line.reversed()
    }
    /** A loop walk ([target]: DOC's loop length, metres) whose [line] does not
     * come back to its start: closed over the map's tracks by the shortest way
     * back that does not retrace it (the Lake Rere Walk returns along the
     * Greenstone Track, another DOC walk), when the whole is within [MATCH] of
     * [target]. It starts at its end nearer a road (the car park) and goes
     * the way DOC describes ([clockwise]). Null when it cannot be closed so. */
    fun closeLoop(line:List<Coordinate>,area:Area,target:Double,clockwise:Boolean?):List<Coordinate>?{
        if(line.size<2||target<=0||area.tracks.isEmpty())return null
        val have=DocTracks.length(line)
        if(DocTracks.meters(line.first(),line.last())<=CLOSED_METERS)return null
        val net=Network(area.tracks)
        val a=net.nearest(line.first(),30.0)?:return null;val b=net.nearest(line.last(),30.0)?:return null
        // Not back along the walk itself (its points away from its two ends).
        val avoid=net.near(line,12.0).filter{DocTracks.meters(net.points[it],line.first())>60&&DocTracks.meters(net.points[it],line.last())>60}.toSet()
        val (dist,prev)=net.from(b,avoid);if(dist[a]==Double.MAX_VALUE)return null
        val back=net.path(prev,a).map{net.points[it]}
        val total=have+DocTracks.length(back)
        if(abs(total-target)>MATCH*target){note("loop ${total.roundToInt()} m");return null}
        var loop=line+back.drop(1)
        // From the end nearer a road: the car park.
        fun road(c:Coordinate)=area.roads.minOfOrNull{DocTracks.distanceTo(it.second,c)}?:Double.MAX_VALUE
        // Starting at the line's far end: back to its start, then along it.
        if(road(line.last())<road(line.first()))loop=back+line.drop(1)
        return turn(loop,clockwise)
    }
    /** [line] (a walk's line that stops short of DOC's stated length
     * [target], in metres) continued at one or both ends over the map's tracks
     * to their natural ends: on along each track (whatever it is called next)
     * until a junction, a dead end or a gap in the map's tracks. Null unless
     * that brings it within [MATCH] of [target]. */
    fun extend(line:List<Coordinate>,area:Area,target:Double):List<Coordinate>?{
        val have=DocTracks.length(line)
        if(line.size<2||target<=0||have>=target||abs(have-target)<=MATCH*target||area.tracks.isEmpty())return null
        val net=Network(area.tracks);val on=net.near(line,12.0)
        fun run(forward:Boolean):List<Coordinate>{
            val l=if(forward)line else line.reversed();val end=l.last()
            var v=net.nearest(end,30.0)?:return emptyList()
            val seen=hashSetOf(v);val out=ArrayList<Coordinate>();var walked=0.0
            while(walked<target){
                val first=out.isEmpty()
                val next=net.edges[v].filter{it.third>=0&&it.first !in seen}.map{it.first}.distinct()
                    .filter{u->!(first&&u in on&&DocTracks.along(l,net.points[u])<have-5)}
                if(next.size!=1)break
                val u=next[0]
                // Back onto the walk itself (a loop): stop.
                if(u in on&&DocTracks.meters(net.points[u],end)>60)break
                walked+=DocTracks.meters(net.points[v],net.points[u]);v=u;seen.add(u);out.add(net.points[u])
            }
            // Cut off by the edge of the area read: not a natural end.
            return if(out.isNotEmpty()&&area.atEdge(out.last(),100.0))emptyList()else out
        }
        val after=run(true);val before=run(false).reversed()
        val candidates=listOf(before+line+after,line+after,before+line).filter{it.size>line.size}
        return candidates.minByOrNull{abs(DocTracks.length(it)-target)}?.takeIf{abs(DocTracks.length(it)-target)<=MATCH*target}
    }
    private val VIA=Regex("\\bvia\\s+(?:the\\s+)?([^,(]+)",RegexOption.IGNORE_CASE)
    /** Share of a route that must lie along DOC's line. */
    const val ON_LINE=.5
    /** Share of DOC's line a walk's only route must cover. */
    const val COVERED=.7
    /** Share of [line] within 60 m of DOC's line [doc]. */
    fun onLine(line:List<Coordinate>,doc:List<List<Coordinate>>):Double{
        val pts=DocTracks.resample(line,20.0,3000);if(pts.isEmpty()||doc.isEmpty())return 0.0
        return pts.count{p->distanceTo(doc,p)<60}.toDouble()/pts.size}
    /** Share of DOC's line [doc] within 60 m of [line]. */
    fun covered(doc:List<List<Coordinate>>,line:List<Coordinate>):Double{
        val pts=doc.flatMap{DocTracks.resample(it,20.0,3000)};if(pts.isEmpty())return 0.0
        return pts.count{p->DocTracks.distanceTo(line,p)<60}.toDouble()/pts.size}
    /** No leg starts where another ends: DOC's legs are ways to walk it, not one route. */
    fun alternatives(legs:List<DocPages.Leg>)=legs.isNotEmpty()&&legs.none{a->legs.any{b->b!==a&&WalkRoutes.same(b.from,a.to)}}
    /** The ways to walk a DOC walk from [legs] that are alternatives (no leg
     * starting where another ends: those are one route, WalkRoutes), over
     * the map's tracks in [area]. [doc]: DOC's line. Null when DOC gives no
     * such legs or none can be followed within [MATCH] of DOC's distance. */
    fun choose(legs:List<DocPages.Leg>,doc:List<List<Coordinate>>,area:Area,locate:(String,Coordinate)->Coordinate?):Choice?{
        notes.setLength(0)
        if(legs.isEmpty()||area.tracks.isEmpty())return null
        if(!alternatives(legs))return null
        val net=Network(area.tracks);if(net.points.size<2)return null
        val all=doc.flatten().ifEmpty{area.tracks.flatMap{it.second}}
        val centre=Coordinate(all.sumOf{it.longitude}/all.size,all.sumOf{it.latitude}/all.size)
        val lineEnds=doc.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.mapNotNull{net.nearest(it,LINE_END_METERS)}.distinct()
        class Found(val from:Int,val to:Int,val path:List<Int>,val meters:Double,val leg:DocPages.Leg)
        val found=ArrayList<Found>()
        for(leg in legs){
            // DOC's distance for the leg is what shows a route is DOC's.
            val km=oneWay(leg)?:run{note("${leg.title}: no distance");null}?:continue
            val via=VIA.find(leg.title)?.groupValues?.get(1)?.let{v->through(v,doc,centre,net,area,locate)}
            // A circuit DOC describes by its stops ("Dawson Falls - Stratford Plateau -
            // Enchanted Track - Waingongoro Track", return): not a way from one end to another.
            if(leg.stops.size>2&&RETURN.containsMatchIn(leg.time+" "+leg.title)){note("${leg.title}: a circuit");continue}
            val named=places(leg.from,null,doc,centre,net,area,locate)
            // An end DOC names only by the walk ("Ben Lomond Saddle"): an end of
            // DOC's line, when the leg's other end is found by name.
            if(named.isEmpty()&&places(leg.to,null,doc,centre,net,area,locate).isEmpty()){note("${leg.title}: ends not found");continue}
            val froms=named.ifEmpty{lineEnds}
            var best:Found?=null;var why="ends not found"
            for(a in froms){
                val namedTos=places(leg.to,net.points[a],doc,centre,net,area,locate)
                val tos=namedTos.ifEmpty{if(named.isEmpty())emptyList()else lineEnds}
                // DOC's distances can be well out: loosely when both ends are found
                // by name; closely when an end is chosen by that distance.
                val tolerance=if(named.isNotEmpty()&&namedTos.isNotEmpty())WalkDirections.LOOSE else MATCH
                val (d1,p1)=net.from(a);val (d2,p2)=via?.let{net.from(it)}?:(d1 to p1)
                for(b in tos){if(b==a)continue
                    // "… to Neill Forks Hut, via Cone Ridge": through that place.
                    val path=if(via!=null&&via!=a&&via!=b){if(d1[via]==Double.MAX_VALUE||d2[b]==Double.MAX_VALUE)null else net.path(p1,via)+net.path(p2,b).drop(1)}
                        else if(d1[b]==Double.MAX_VALUE)null else net.path(p1,b)
                    if(path==null){why="not joined";continue}
                    val meters=DocTracks.length(path.map{net.points[it]})
                    if(abs(meters-km)>tolerance*km){why="${meters.roundToInt()} m";continue}
                    // Mostly along DOC's line (it can go beyond: the Tiki Trail to the
                    // saddle): not some other way between the same places.
                    val share=onLine(path.map{net.points[it]},doc)
                    if(share<ON_LINE){why="${(share*100).roundToInt()}% on DOC's line";continue}
                    if(best==null||abs(meters-km)<abs(best.meters-km))best=Found(a,b,path,meters,leg)
                }
            }
            if(best==null){note("${leg.title}: $why");continue}
            found.add(best)
        }
        if(found.isEmpty())return null
        // One way only: used when it is the walk on DOC's line (perhaps going on
        // beyond it), not a part of it.

        // Starts and ends, by place (two names for one place are one).
        val starts=ArrayList<Pair<Int,String>>();val ends=ArrayList<Pair<Int,String>>()
        fun index(list:MutableList<Pair<Int,String>>,v:Int,name:String):Int{
            list.indexOfFirst{DocTracks.meters(net.points[it.first],net.points[v])<100||it.second.equals(WalkRoutes.plain(name),true)}.takeIf{it>=0}?.let{return it}
            list.add(v to WalkRoutes.plain(name));return list.size-1}
        val options=ArrayList<Option>()
        for(f in found){val s=index(starts,f.from,f.leg.from);val e=index(ends,f.to,f.leg.to)
            if(options.none{it.start==s&&it.end==e})options.add(Option(s,e,f.path.map{net.points[it]},f.leg,net.trackNames(f.path)))}
        if(options.size==1&&covered(doc,options[0].line)<COVERED){note("one way, covering ${(covered(doc,options[0].line)*100).roundToInt()}% of DOC's line");return null}
        // Other pairings of DOC's starts and ends ("Skyline Gondola top" to
        // "Ben Lomond Summit", over the saddle), not much longer than DOC's longest.
        val longest=found.maxOf{it.meters}
        for(s in starts.indices)for(e in ends.indices){if(options.any{it.start==s&&it.end==e})continue
            val a=starts[s].first;val b=ends[e].first;if(a==b)continue
            val (dist,prev)=net.from(a);if(dist[b]==Double.MAX_VALUE||dist[b]<200||dist[b]>1.3*longest)continue
            val path=net.path(prev,b);if(onLine(path.map{net.points[it]},doc)<ON_LINE)continue
            options.add(Option(s,e,path.map{net.points[it]},null,net.trackNames(path)))}
        return Choice(starts.map{it.second},ends.map{it.second},options)
    }

    private fun roadKey(name:String)=PlaceSearch.normalize(name).split(' ').joinToString(" "){when(it){"rd"->"road";"st"->"street";"saint"->"street";"hwy"->"highway";else->it}}.takeIf{Regex("\\b(road|street|highway|drive|lane|avenue)$").containsMatchIn(it)}?:"\u0000"
    private val PLACE_TAIL=Regex("\\s+(?:visitor centre|information centre|i-site|national park|conservation park|car ?parks?|road ?end|picnic area|campground|campsite|shelter|end|road)$",RegexOption.IGNORE_CASE)
    private val HOURS=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–]|to)?\\s*(?:([0-9]+(?:\\.[0-9]+)?)\\s*)?(?:h|hr|hrs|hour|hours)\\b",RegexOption.IGNORE_CASE)
    private val WALK_WORDS=Regex("\\b(track|tracks|walk|walks|walking|walkway|route|routes|trail|loop|circuit)\\b",RegexOption.IGNORE_CASE)
    /** Each of DOC's [starts] (DocPages.starts: "outside the … Visitor Centre",
     * "at White Horse Hill car park", on the Kea Point Track) followed over the
     * map's tracks to the far end of DOC's line (the walk's destination, named
     * after the walk: "Sealy Tarns"). A start counts when it is found by name
     * (on its named track, where DOC names one), within 5 km of DOC's line, its
     * route covers [COVERED] of DOC's line and fits DOC's time for the walk
     * [time] at 0.75 to 6 km/h. Null unless two or more starts count. */
    fun fromStarts(starts:List<DocPages.Start>,walk:String,time:String,doc:List<List<Coordinate>>,area:Area,locate:(String,Coordinate)->Coordinate?):Choice?{
        if(starts.size<2||doc.isEmpty()||area.tracks.isEmpty())return null
        notes.setLength(0)
        val net=Network(area.tracks);if(net.points.size<2)return null
        val all=doc.flatten();val centre=Coordinate(all.sumOf{it.longitude}/all.size,all.sumOf{it.latitude}/all.size)
        val lineEnds=doc.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.mapNotNull{net.nearest(it,LINE_END_METERS)}.distinct()
        if(lineEnds.isEmpty())return null
        // DOC's time one way: "3 - 4 hr return" is 1.5 to 2 hr each way.
        val h=HOURS.find(time);val back=if(RETURN.containsMatchIn(time)&&!ONE_WAY.containsMatchIn(time))2.0 else 1.0
        val low=h?.groupValues?.get(1)?.toDoubleOrNull()?.div(back);val high=(h?.groupValues?.get(2)?.toDoubleOrNull()?:h?.groupValues?.get(1)?.toDoubleOrNull())?.div(back)
        val names=ArrayList<String>();val options=ArrayList<Option>()
        for(st in starts){
            val variants=generateSequence(st.place.trim()){v->v.replace(PLACE_TAIL,"").trim().takeIf{it!=v&&it.isNotBlank()}}.toList()
            val point=variants.firstNotNullOfOrNull{v->locate(v,centre)?:area.peaks.firstOrNull{(p,_)->PlaceSearch.normalize(p)==PlaceSearch.normalize(v)}?.second}
            val on=st.track?.let{t->area.tracks.withIndex().filter{(_,tr)->tr.first.isNotBlank()&&DocWalks.sameName(tr.first,t)}.map{it.index}.toSet()}.orEmpty()
            // A road ("Glen Lyon Road"): where the walk's tracks meet it, nearest DOC's line.
            val road=if(point!=null)null else variants.firstNotNullOfOrNull{v->val k=roadKey(v)
                area.roads.filter{roadKey(it.first)==k}.takeIf{it.isNotEmpty()}}?.let{rs->
                net.points.indices.filter{i->rs.any{r->DocTracks.distanceTo(r.second,net.points[i])<40}}.minByOrNull{distanceTo(doc,net.points[it])}?.takeIf{distanceTo(doc,net.points[it])<2000}}
            val a=if(point==null)road?:variants.firstNotNullOfOrNull{v->places(v,null,doc,centre,net,area,locate).firstOrNull()}
                else if(on.isNotEmpty())net.points.indices.filter{v->net.edges[v].any{it.third in on}}.minByOrNull{DocTracks.meters(net.points[it],point)}?.takeIf{DocTracks.meters(net.points[it],point)<=1000}
                else lineEnds.minByOrNull{DocTracks.meters(net.points[it],point)}?.takeIf{DocTracks.meters(net.points[it],point)<=NEAR_METERS}
                    ?:net.nearest(point,NEAR_METERS)
                    // A town ("the Woodville end"): the end of DOC's line nearest it, when clearly nearest.
                    ?:lineEnds.sortedBy{DocTracks.meters(net.points[it],point)}.let{e->e.firstOrNull()?.takeIf{f->DocTracks.meters(net.points[f],point)<10_000&&(e.size<2||DocTracks.meters(net.points[e[1]],point)>DocTracks.meters(net.points[f],point)+2000)}}
            if(a==null){note("${st.place}: not found");continue}
            if(distanceTo(doc,net.points[a])>5000){note("${st.place}: far from DOC's line");continue}
            val (dist,prev)=net.from(a)
            val b=lineEnds.filter{it!=a&&dist[it]<Double.MAX_VALUE}.maxByOrNull{dist[it]}
            if(b==null){note("${st.place}: not joined");continue}
            val line=net.path(prev,b).map{net.points[it]};val m=DocTracks.length(line)
            if(covered(doc,line)<COVERED){note("${st.place}: covers ${(covered(doc,line)*100).roundToInt()}% of DOC's line");continue}
            if(low!=null&&high!=null&&(m<low*750||m>high*6000)){note("${st.place}: ${m.roundToInt()} m in $time");continue}
            if(options.any{DocTracks.meters(it.line.first(),line.first())<100})continue
            names.add(WalkRoutes.plain(st.place));options.add(Option(names.size-1,0,line,null,net.trackNames(net.path(prev,b))))
        }
        if(options.size<2)return null
        // The end, by the walk's name when that is a place there ("Sealy Tarns");
        // otherwise unnamed (the Arthur's Pass Walking Track from either car park).
        val end=walk.replace(WALK_WORDS,"").replace(Regex("\\s+")," ").trim()
        val far=options[0].line.last()
        val named=end.isNotEmpty()&&(locate(end,far)?.let{DocTracks.meters(it,far)<1000}==true||area.peaks.any{(p,c)->PlaceSearch.normalize(p)==PlaceSearch.normalize(end)&&DocTracks.meters(c,far)<1000})
        return Choice(names,listOf(if(named)end else ""),options)
    }
}
