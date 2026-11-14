package nz.ridgeline.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** The offline walk compiler (tools/walks): a DOC walk's reading (what its
 * page says: start, end, the places and tracks in order, shape, way round,
 * huts; READING.md) followed over the map's tracks around DOC's line, and
 * checked. A walk is verified only when every check passes; each failure is a
 * flag with its reason, and the app then shows DOC's own line instead.
 *
 * Checks on the main way: every place it names that can be placed is
 * reached, in order, with no long way round between consecutive places; it
 * covers [COVERED] of DOC's line; it fits DOC's time at a walking pace; each
 * hut DOC links as on the walk is on it; a loop closes. DOC's stated length is
 * a loose bound only ([LOOSE]): DOC's figures can be well out. */
internal object WalkCompiler {
    /** All the ways the page describes, together, cover this much of DOC's line… */
    const val COVERED=.75
    /** …and the main way alone this much (DOC's line also draws other starts). */
    const val MAIN_COVERED=.5
    const val LOOSE=.5
    /** A ford the page names is crossed where the map has no track, up to this far. */
    const val FORD_METERS=1000.0
    /** Roads the page says to walk count this many times their length (never a short cut). */
    const val ROAD_COST=3.0
    private val EXIT_WORDS=mapOf("fly" to "fly out","boat" to "boat","track" to "track out","road" to "road out","other" to "exit")
    const val DETOUR=3.0
    /** A place this close to a track is reached by it (by kind). */
    private fun reach(kind:String)=when(kind){"lake","coast"->1000.0;"place"->1500.0;else->500.0}
    /** Gazetteer kinds that are a point a walk can start, end or pass at. */
    private val POINT_KINDS=setOf("hut","camp","peak","pass","waterfall","coast","lake","place")
    private fun distanceKm(a:PlaceSearch.Place,b:PlaceSearch.Place)=DocTracks.meters(Coordinate(a.longitude,a.latitude),Coordinate(b.longitude,b.latitude))/1000
    private val KIND_WORDS=linkedMapOf(
        Regex("\\b(village|town|township|settlement)\\b",RegexOption.IGNORE_CASE) to setOf("place"),
        Regex("\\b(hut|biv|bivvy|bivouac|lodge|shelter)\\b",RegexOption.IGNORE_CASE) to setOf("hut"),
        Regex("\\b(campsite|campground|camp)\\b",RegexOption.IGNORE_CASE) to setOf("camp"),
        Regex("\\b(lake|tarns?|lagoon)\\b",RegexOption.IGNORE_CASE) to setOf("lake"),
        Regex("\\b(saddle|pass|col)\\b",RegexOption.IGNORE_CASE) to setOf("pass"),
        Regex("\\b(falls|waterfall)\\b",RegexOption.IGNORE_CASE) to setOf("waterfall"),
        Regex("\\b(bay|beach|point|cove|inlet|wharf|landing|head)\\b",RegexOption.IGNORE_CASE) to setOf("coast","place"),
        Regex("\\b(summit|peak|mt|mount|hill|trig|knob|dome)\\b",RegexOption.IGNORE_CASE) to setOf("peak"))
    /** Kinds that name an area, not a point a track goes to. */
    private val AREA=setOf("river","ridge","valley","area","glacier")
    /** Generic words for an unnamed trailhead ("the car park", "road end"). */
    private val TRAILHEAD=Regex("\\b(car ?park|carpark|road ?end|roadend|trailhead|parking|start of the track)\\b",RegexOption.IGNORE_CASE)

    class Input(val packet:JSONObject,val reading:JSONObject,val doc:List<List<Coordinate>>,val area:WalkOptions.Area,
                val places:PlaceSearch,val walkLine:(String)->List<List<Coordinate>>,val huts:List<WalkRecords.Hut> = emptyList())
    /** [stops]: the places named on the way and where they are along its line (metres). */
    class Way(val label:String,val from:String,val to:String,val shape:String,val main:Boolean,val line:List<Coordinate>,val time:String,val distance:String,val stops:List<Pair<String,Double>> = emptyList())
    /** [attempted]: lines of ways that failed a check (for the review page only). */
    class Result(val verified:Boolean,val flags:List<String>,val notes:List<String>,val ways:List<Way>,val huts:List<WalkRecords.Hut>,val kind:String,val attempted:List<Way> = emptyList())

    /** [alt]: where else the place is reached when none of [nodes] can be from the way
     * (Kerin Forks: its nearest point is on the hut's piece across the Wilkin). */
    private class Target(val name:String,val nodes:Set<Int>,val track:Set<Int> = emptySet(),val point:Coordinate?=null,val road:Boolean=false,val alt:Set<Int> = emptySet())
    private fun roadKey(s:String)=s.uppercase().replace(Regex("\\bRD\\b"),"ROAD").replace(Regex("[^A-Z0-9]"),"")

    fun compile(input:Input):Result{
        val flags=ArrayList<String>();val notes=ArrayList<String>()
        val r=input.reading;val p=input.packet
        val kind=r.optString("kind")
        val waysIn=r.optJSONArray("ways")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}.orEmpty()
        if(waysIn.isEmpty()){flags.add("the page does not describe a way to walk it ($kind)");return Result(false,flags,notes,emptyList(),emptyList(),kind)}
        if(kind=="network")flags.add("a page for several tracks")
        val area=input.area
        // DOC's own line joins the map's tracks as a fallback, counted at twice
        // its length, so routes keep to the map's tracks and use DOC's line only
        // where the map has none (unmarked routes).
        val docPieces=input.doc.filter{it.size>=2}.map{"" to it}
        // Roads only where the page says to walk one ("continue out to the main road via
        // Wilkin Road"), at [ROAD_COST], so a way keeps to tracks elsewhere.
        val roadsOn=waysIn.any{w->w.optJSONArray("via")?.let{a->(0 until a.length()).any{a.getJSONObject(it).optString("how")=="road"}}==true}
        val tracks=area.tracks+docPieces+(if(roadsOn)area.roads else emptyList());val mapCount=area.tracks.size;val docEnd=mapCount+docPieces.size
        val net=WalkOptions.Network(tracks,{if(it>=docEnd)ROAD_COST else if(it>=mapCount)2.0 else 1.0},{if(it in mapCount until docEnd)1500.0 else WalkOptions.BRIDGE_METERS})
        if(net.points.size<2){flags.add("no map tracks here");return Result(false,flags,notes,emptyList(),emptyList(),kind)}
        val docAll=input.doc.flatten()
        val centre=(docAll.ifEmpty{area.tracks.flatMap{it.second}}).let{l->Coordinate(l.sumOf{it.longitude}/l.size,l.sumOf{it.latitude}/l.size)}
        val lineEnds=input.doc.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.mapNotNull{net.nearest(it,150.0)}.distinct()
        fun road(v:Int)=area.roads.minOfOrNull{DocTracks.distanceTo(it.second,net.points[v])}?:Double.MAX_VALUE
        val links=p.getJSONArray("links").let{a->(0 until a.length()).associate{a.getJSONObject(it).let{l->l.getString("id") to l}}}
        val features=(p.optJSONArray("features")?:JSONArray()).let{a->(0 until a.length()).associate{a.getJSONObject(it).let{l->l.getString("id") to l}}}
        val names=p.getJSONArray("names").let{a->(0 until a.length()).associate{a.getJSONObject(it).let{l->l.getString("id") to l}}}
        fun pt(a:JSONArray?)=a?.let{Coordinate(it.getDouble(0),it.getDouble(1))}
        fun trackNodes(idx:Set<Int>)=net.points.indices.filter{v->net.edges[v].any{it.third in idx}}.toSet()
        fun nearNode(c:Coordinate,within:Double)=net.nearest(c,within)
        // Points along DOC's own line: a place the way passes (Elfin Bay, whose gazetteer
        // point is out on the bay) is where DOC's line goes by it, not a spur to the shore.
        val onDoc=if(docAll.isEmpty())emptySet() else input.doc.filter{it.size>=2}.flatMap{net.near(it,60.0)}.toSet()
        fun passNode(c:Coordinate,within:Double)=onDoc.filter{DocTracks.meters(net.points[it],c)<=within}.minByOrNull{DocTracks.meters(net.points[it],c)}?:nearNode(c,within)
        /** Where else a place on the way or an end can be reached: any point within [within]
         * of it; used only when its nearest point cannot be reached from the way at all (Kerin
         * Forks Hut, on its own piece across the Wilkin, passed 450 m away on the track down
         * the valley). The way then takes the one it reaches first. */
        fun around(c:Coordinate,within:Double,@Suppress("UNUSED_PARAMETER") pass:Boolean):Set<Int> =
            net.points.indices.filter{DocTracks.meters(net.points[it],c)<=within}.toSet()
        val roadIdx=(docEnd until tracks.size).toSet();val roadNodes=if(roadsOn)trackNodes(roadIdx) else emptySet()
        /** A locality where the page walks roads: the nearest point of a road in it. */
        fun roadNear(c:Coordinate)=roadNodes.filter{DocTracks.meters(net.points[it],c)<=1500}.minByOrNull{DocTracks.meters(net.points[it],c)}

        /** Where a place of the reading is on the map, or null (with why). */
        fun target(o:JSONObject?,role:String):Pair<Target?,String>{
            if(o==null)return null to "not given"
            val name=o.optString("name");val ref=if(o.isNull("ref"))null else o.optString("ref")
            if(o.optString("how")=="road"){
                val idx=roadIdx.filter{roadKey(tracks[it].first)==roadKey(name)}.toSet()
                return if(idx.isEmpty())null to "road $name is not on the map" else Target(name,trackNodes(idx),idx,road=true) to ""}
            if(ref!=null&&ref.startsWith("L")){val l=links[ref]?:return null to "unknown link $ref"
                when(l.getString("kind")){
                    "walk"->{val line=input.walkLine(l.getString("ref"));val nodes=line.flatMap{seg->net.near(seg,40.0)}.toSet()
                        return if(nodes.isEmpty())null to "linked walk ${l.getString("name")} is off the map here" else Target(name,nodes) to ""}
                    else->{val c=pt(l.optJSONArray("point"))?:return null to "no point for ${l.getString("name")}"
                        val v=nearNode(c,600.0)?:return null to "${l.getString("name")} is over 600 m from any track"
                        return Target(name,setOf(v),point=c,alt=if(role=="start")emptySet() else around(c,600.0,false)) to ""}
                }
            }
            if(ref!=null&&ref.startsWith("F")){val f=features[ref]?:return null to "unknown feature $ref"
                val c=pt(f.optJSONArray("point"))?:return null to "no point"
                val v=nearNode(c,150.0)?:return null to "${f.getString("name")} is off the map's tracks"
                return Target(name,setOf(v),point=c) to ""}
            if(ref!=null&&ref.startsWith("T")){val n=names[ref]?:return null to "unknown name $ref";val k=n.getString("kind")
                if(k=="map track"){val idx=area.tracks.indices.filter{area.tracks[it].first==n.getString("name")}.toSet()
                    val nodes=trackNodes(idx);return if(nodes.isEmpty())null to "map track ${n.getString("name")} not found" else Target(name,nodes,idx) to ""}
                if(k in AREA)return null to "an area ($k), not a point"
                val c=pt(n.optJSONArray("point"))?:return null to "no point"
                if(k=="place"){
                    // A locality: the end of DOC's line nearest it, when clearly nearest.
                    // A locality the way passes ("Kerin Forks"): a point when a track is within 1 km of it.
                    if(role=="via")return passNode(c,1000.0)?.let{Target(name,setOf(it),point=c,alt=around(c,1000.0,true)) to ""}?:(null to "a locality over 1 km from any track")
                    roadNear(c)?.let{return Target(name,setOf(it),point=c) to ""}
                    val e=lineEnds.sortedBy{DocTracks.meters(net.points[it],c)}
                    val f=e.firstOrNull()?.takeIf{DocTracks.meters(net.points[it],c)<8000&&(e.size<2||DocTracks.meters(net.points[e[1]],c)>DocTracks.meters(net.points[it],c)+1500)}
                    return if(f!=null)Target(name,setOf(f),point=c) to "" else (nearNode(c,reach(k))?.let{Target(name,setOf(it),point=c) to ""}?:(null to "locality $name not near DOC's line ends"))
                }
                val v=(if(role=="via")passNode(c,reach(k)) else nearNode(c,reach(k)))?:return null to "$name ($k) is over ${reach(k).toInt()} m from any track"
                return Target(name,setOf(v),point=c,alt=if(role=="start")emptySet() else around(c,reach(k),role=="via")) to ""
            }
            // Not one of the packet's names: a gazetteer place of that name near the walk
            // (point kinds only: "Glade Wharf", "Kepler Track carpark").
            run{val q=WalkRoutes.plain(name).ifBlank{name}
                // Only an unambiguous name (Elfin Bay is both a bay and a locality), and a
                // car park or road end is never a peak, lake, pass or waterfall of its name
                // ("White Horse Hill car park" is not the hill).
                val trailhead=TRAILHEAD.containsMatchIn(q)
                // Words in the name say what kind of place it is ("Aoraki/Mount Cook Village": the locality, not the peak).
                val hinted=KIND_WORDS.entries.firstOrNull{it.key.containsMatchIn(q)}?.value
                val all=input.places.locateAll(q,centre,40.0).filter{it.kind in POINT_KINDS&&(!trailhead||it.kind=="camp"||it.kind=="place")&&(hinted==null||it.kind in hinted)}
                // Two entries for one place (Elfin Bay the bay and the locality, 650 m apart): the one nearest the walk.
                val pl=all.singleOrNull()?:all.takeIf{a->a.size>1&&a.all{x->a.all{y->distanceKm(x,y)<1.5}}}?.minByOrNull{p->(docAll.ifEmpty{listOf(centre)}).minOf{DocTracks.meters(it,Coordinate(p.longitude,p.latitude))}}
                if(pl!=null){val c=Coordinate(pl.longitude,pl.latitude)
                    val near=(docAll.ifEmpty{area.tracks.flatMap{it.second}}).minOfOrNull{DocTracks.meters(it,c)}?:Double.MAX_VALUE
                    if(near<3000){
                        if(pl.kind=="place"&&role!="via")roadNear(c)?.let{return Target(name,setOf(it),point=c) to ""}
                        val v=if(role=="via")passNode(c,reach(pl.kind)) else nearNode(c,reach(pl.kind))
                        if(v!=null)return Target(name,setOf(v),point=c,alt=if(role=="start")emptySet() else around(c,reach(pl.kind),role=="via")) to ""}}}
            // A map track of that name, else a trailhead.
            val idx=area.tracks.indices.filter{area.tracks[it].first.isNotBlank()&&DocWalks.sameName(area.tracks[it].first,name)}.toSet()
            if(idx.isNotEmpty())return Target(name,trackNodes(idx),idx) to ""
            if(role!="via"&&TRAILHEAD.containsMatchIn(name+" "+o.optString("quote"))){
                val e=lineEnds.filter{road(it)<300}
                if(e.size==1)return Target(name,setOf(e[0])) to ""
            }
            return null to "$name is not placed"
        }

        val outWays=ArrayList<Way>();val reasonsFor=HashMap<String,List<String>>();val attempted=ArrayList<Way>()
        var mainLine:List<Coordinate>?=null;var mainStart=-1;var mainEnd=-1
        for(w in waysIn.sortedByDescending{it.optBoolean("main")}){
            val main=w.optBoolean("main");val label=w.optString("label");val shape=if(w.isNull("shape"))"" else w.optString("shape")
            val why=ArrayList<String>()
            if(shape.isEmpty())why.add("shape not stated")
            val (s,sWhy)=target(w.optJSONObject("start"),"start")
            var start=s
            val viaIn=w.optJSONArray("via")?.let{x->(0 until x.length()).map{x.getJSONObject(it)}}.orEmpty()
            val placedVias=viaIn.filter{it.optString("how")!="ford"}.mapNotNull{target(it,"via").first}
            if(start==null&&main&&placedVias.isNotEmpty()&&lineEnds.size>=2){
                // A start named only in words ("Glade Wharf"): the end of DOC's line nearest
                // (over the map's tracks) the first place the way names, not nearer the last.
                val first=placedVias.first();val last=placedVias.last()
                fun d(e:Int,t:Target):Double{val (dist,_)=net.from(e);return t.nodes.minOf{dist[it]}}
                val pick=lineEnds.filter{e->d(e,first)<Double.MAX_VALUE}.minByOrNull{d(it,first)}
                if(pick!=null&&(shape=="loop"||placedVias.size<2||d(pick,first)<d(pick,last))){start=Target(w.optJSONObject("start")?.optString("name").orEmpty().ifBlank{"start"},setOf(pick))
                    notes.add("$label: start '${w.optJSONObject("start")?.optString("name")}' ($sWhy) taken as DOC's line end nearest ${first.name}")}
            }
            if(start==null){
                // No placed start: the end of DOC's line at a road (the car park), when there is exactly one.
                val e=lineEnds.filter{road(it)<300}
                if(e.size==1){start=Target("start",setOf(e[0]));notes.add("$label: start '${w.optJSONObject("start")?.optString("name")}' ($sWhy) taken as DOC's line end at the road")}
                else{why.add("start: $sWhy");reasonsFor[label]=why;continue}
            }
            var a=start.nodes.minByOrNull{v->lineEnds.minOfOrNull{DocTracks.meters(net.points[it],net.points[v])}?:0.0}!!
            // A start the map's tracks do not join to the walk (Makarora, across the
            // river the Gillespie Pass Circuit fords): the end of DOC's line nearest it.
            run{val next=placedVias.firstOrNull()?.nodes?:lineEnds.toSet()
                val (dist,_)=net.from(a)
                if(next.isNotEmpty()&&next.none{dist[it]<Double.MAX_VALUE}){
                    val from=start.point?:net.points[a]
                    val e=lineEnds.filter{DocTracks.meters(net.points[it],from)<6000}.filter{e->val (d2,_)=net.from(e);next.any{d2[it]<Double.MAX_VALUE}}.minByOrNull{DocTracks.meters(net.points[it],from)}
                    if(e!=null){a=e;notes.add("$label: start ${start.name} is not joined to the walk on the map's tracks; taken as DOC's line end nearest it")}}}
            // Each placed place with the ford (by name) the page crosses on the way to it, if any.
            val vias=ArrayList<Pair<Target,String?>>();var ford:String?=null
            for(v in viaIn){if(v.optString("how")=="ford")ford=v.optString("name")
                val (t,tw)=target(v,"via")
                if(t!=null){vias.add(t to ford);ford=if(v.optString("how")=="ford")v.optString("name") else null}
                else if(v.optString("how")!="ford")notes.add("$label: via ${v.optString("name")} not placed ($tw)")}
            val fordToEnd=ford
            val (e0,eWhy)=if(shape=="loop")(Target(start.name,setOf(a)) to "") else target(w.optJSONObject("end"),"end")
            var end=e0
            if(end==null){
                // No placed end: the end of DOC's line furthest along the map from the start (main way only).
                val (dist,_)=net.from(a)
                val far=lineEnds.filter{it!=a&&dist[it]<Double.MAX_VALUE}.maxByOrNull{dist[it]}
                // Another way's end named only in words ("Ben Lomond Saddle"): an end of DOC's
                // line on the main way, where this way's own DOC distance puts it (within 25%).
                val km=if(w.isNull("distance"))null else WalkRoutes.stated(w.optString("distance"))
                val onMain=if(!main&&km!=null&&mainLine!=null)lineEnds.filter{it!=a&&dist[it]<Double.MAX_VALUE&&DocTracks.distanceTo(mainLine!!,net.points[it])<100&&abs(dist[it]-km)<=.25*km}.minByOrNull{abs(dist[it]-km)} else null
                if(main&&far!=null){end=Target("end",setOf(far));notes.add("$label: end '${w.optJSONObject("end")?.optString("name")}' ($eWhy) taken as DOC's line end furthest from the start")}
                else if(onMain!=null){end=Target("end",setOf(onMain));notes.add("$label: end '${w.optJSONObject("end")?.optString("name")}' ($eWhy) taken as DOC's line end on the main way at DOC's distance")}
                else{why.add("end: $eWhy");reasonsFor[label]=why;continue}
            }
            // Follow the places in order, a track named on the way kept to until the next
            // place. Track already walked counts [again] times its length: tried at 4 (a
            // loop goes round, the Hump Ridge Track) and 1.3 (a loop shares a stretch rather
            // than go far round where the map lacks its own way back, Lake Rere); the one
            // passing every check is kept (of two, the one covering more of DOC's line).
            class Try(val path:List<Int>,val reachedAt:List<Pair<String,Int>>,val why:List<String>,val cover:Double,val legs:List<String>)
            fun attempt(again:Double):Try?{
            val why=ArrayList(why);val legs=ArrayList<String>()
            val walked=HashSet<Long>();val path=arrayListOf(a);var at=a;var prefer=emptySet<Int>()
            val reachedAt=ArrayList<Pair<String,Int>>()
            fun go(t:Target,ford:String?=null):Boolean{
                if(at in t.nodes){if(t.track.isNotEmpty())prefer=t.track;if(t.track.isEmpty())reachedAt.add(t.name to path.size-1);if(t.road)reachedAt.add("${t.name} (road)" to path.size-1);return true}
                // Track already walked counts 1.3 times: a loop takes another way back where
                // there is one, but shares a stretch rather than go far round (Lake Rere).
                val (dist,prev)=net.from(at,emptySet(),walked,again,prefer)
                var v=t.nodes.minByOrNull{dist[it]}?.takeIf{dist[it]<Double.MAX_VALUE}
                    ?:t.alt.minByOrNull{dist[it]}?.takeIf{dist[it]<Double.MAX_VALUE}
                var leg=v?.let{net.path(prev,it)}
                // A ford the page names on this leg: one straight crossing where the map has no
                // track over the river, when it makes the way there cheaper (or possible).
                if(ford!=null){val (back,backPrev)=net.fromAll(t.nodes)
                    // (to the place itself: a ford is not crossed to stop short of it)
                    val hop=net.crossing(dist,back,FORD_METERS,3.0)
                    if(hop!=null&&(v==null||hop.third<dist[v])){
                        val head=net.path(prev,hop.first);val tail=arrayListOf(hop.second);var x=hop.second
                        while(backPrev[x]>=0){x=backPrev[x];tail.add(x)}
                        reachedAt.add("$ford (ford)" to path.size-1+head.size-1);leg=head+tail;v=x}}
                if(v==null||leg==null)return false
                // A long way round: much longer than the shortest way over the map's tracks
                // (the no-retracing rule bending the route), not the straight line (rivers).
                val m=DocTracks.length(leg.map{net.points[it]})
                val (free,freePrev)=net.from(at);val direct=if(free[v]<Double.MAX_VALUE)DocTracks.length(net.path(freePrev,v).map{net.points[it]})else m
                if(m>1.5*direct+1000)why.add("a long way round to ${t.name}: ${(m/1000).fmt()} km for ${(direct/1000).fmt()} km")
                legs.add("${t.name} ${(m/1000).fmt()}")
                for(i in 1 until leg.size)walked.add(net.step(leg[i-1],leg[i]))
                path.addAll(leg.drop(1));at=v;prefer=t.track;if(t.track.isEmpty())reachedAt.add(t.name to path.size-1);if(t.road)reachedAt.add("${t.name} (road)" to path.size-1);return true
            }
            for((t,f) in vias)if(!go(t,f))why.add("${t.name} cannot be reached on the map's tracks")
            if(!go(end,fordToEnd))why.add("${end.name} cannot be reached on the map's tracks")
            val line=path.map{net.points[it]}
            if(line.size<2)return null
            val m=DocTracks.length(line)
            if(shape=="loop"&&DocTracks.meters(line.first(),line.last())>200)why.add("the loop does not close")
            if(shape!="loop"&&DocTracks.meters(line.first(),line.last())<100&&m>500)why.add("starts and ends at the same place but is not a loop")
            // DOC's time for this way (or the walk's), one way, at 0.75 to 6 km/h.
            val time=if(w.isNull("time"))"" else w.optString("time");val distance=if(w.isNull("distance"))"" else w.optString("distance")
            val t=hours(time.ifBlank{if(main)p.optString("time") else ""})
            if(t!=null){val back=if(shape=="out_and_back"&&Regex("\\breturn\\b",RegexOption.IGNORE_CASE).containsMatchIn(time.ifBlank{p.optString("time")}))2.0 else 1.0
                val lo=t.first/back*750;val hi=t.second/back*6000
                if(m<lo||m>hi)why.add("${(m/1000).fmt()} km does not fit DOC's time '${time.ifBlank{p.optString("time")}}'")}
            val d=WalkRoutes.stated(distance.ifBlank{if(main)p.optString("distance") else ""})
            if(d!=null&&abs(m-d)>LOOSE*d)why.add("${(m/1000).fmt()} km against DOC's ${(d/1000).fmt()} km")
            // Two independent signs of a wrong route together: far from DOC's stated length
            // and mostly off DOC's own line (either alone can be DOC's figure or line).
            if(d!=null&&docAll.isNotEmpty()&&abs(m-d)>.25*d){val share=WalkOptions.onLine(line,input.doc)
                if(share<.6)why.add("${(m/1000).fmt()} km against DOC's ${(d/1000).fmt()} km and ${(share*100).roundToInt()}% on DOC's line")}
            val cover=if(docAll.isNotEmpty())WalkOptions.covered(input.doc,line) else 1.0
            if(main&&cover<MAIN_COVERED)why.add("covers ${(cover*100).roundToInt()}% of DOC's line")
            return Try(path,reachedAt,why,cover,legs)
            }
            val tries=listOf(4.0,1.3).mapNotNull{attempt(it)}
            if(tries.isEmpty()){why.add("no route");reasonsFor[label]=why;continue}
            val best=tries.filter{it.why.isEmpty()}.maxByOrNull{it.cover}?:tries.first()
            why.clear();why.addAll(best.why);notes.add("$label: legs (km) ${best.legs.joinToString(", ")}")
            val path=best.path;val reachedAt=best.reachedAt;val line=path.map{net.points[it]}
            val time=if(w.isNull("time"))"" else w.optString("time");val distance=if(w.isNull("distance"))"" else w.optString("distance")
            if(main){mainLine=line;mainStart=a;mainEnd=path.last()}
            if(why.isNotEmpty()){reasonsFor[label]=why;if(main)flags.addAll(why.map{"main way: $it"});attempted.add(Way(label,"","",shape,main,line,"",""));continue}
            val from=w.optJSONObject("start")?.optString("name").orEmpty().ifBlank{start.name}
            val to=if(shape=="loop")from else w.optJSONObject("end")?.optString("name").orEmpty().ifBlank{end.name}
            val along=DoubleArray(line.size);for(i in 1 until line.size)along[i]=along[i-1]+DocTracks.meters(line[i-1],line[i])
            val stops=listOf(from to 0.0)+reachedAt.filter{it.second in 1 until line.size-1}.sortedBy{it.second}.map{it.first to along[it.second]}+listOf(to to along.last())
            outWays.add(Way(label,from,to,shape,main,line,time,distance,stops))
        }
        if(outWays.none{it.main}&&flags.none{it.startsWith("main way")})flags.add("main way: ${reasonsFor.entries.firstOrNull()?.let{"${it.key}: ${it.value.joinToString("; ")}"}?:"not followed"}")
        for((k,v) in reasonsFor)if(waysIn.none{it.optBoolean("main")&&it.optString("label")==k})notes.add("way '$k' left out: ${v.joinToString("; ")}")
        // Other starts DOC names (access points): each to the main way's end.
        val mainWay=outWays.firstOrNull{it.main}
        // Access tracks the page describes, each to the nearest point of the main way: not
        // shown as ways of their own, but part of DOC's line the page accounts for (the
        // Young River Link Track to the Gillespie Pass Circuit).
        val explained=ArrayList<List<Coordinate>>()
        if(mainWay!=null&&mainEnd>=0)for(o in r.optJSONArray("access_points")?.let{x->(0 until x.length()).map{x.getJSONObject(it)}}.orEmpty()){
            val (t,tw)=target(o,"start");if(t==null){notes.add("access point ${o.optString("name")} not placed ($tw)");continue}
            val lk=if(o.isNull("ref"))null else links[o.optString("ref")]
            if(lk?.getString("kind")=="walk")explained.addAll(input.walkLine(lk.getString("ref")).filter{DocTracks.length(it)<=15000})
            else run{val onMain=net.near(mainWay.line,30.0);val (d0,p0)=net.fromAll(t.nodes)
                onMain.filter{d0[it]<Double.MAX_VALUE}.minByOrNull{d0[it]}?.let{v->val l=net.path(p0,v).map{net.points[it]}
                    if(DocTracks.length(l)<=15000)explained.add(l)}}
            val s=t.nodes.minByOrNull{DocTracks.meters(net.points[it],net.points[mainEnd])}!!
            val (dist,prev)=net.from(s);if(dist[mainEnd]==Double.MAX_VALUE){notes.add("access point ${o.optString("name")} not joined");continue}
            val line=net.path(prev,mainEnd).map{net.points[it]};val m=DocTracks.length(line)
            val direct=DocTracks.meters(line.first(),line.last())
            if(m>DETOUR*direct+1500||WalkOptions.covered(listOf(mainWay.line),line)<.2){notes.add("access point ${o.optString("name")}: route does not join the walk directly");continue}
            outWays.add(Way("From ${o.optString("name")}",o.optString("name"),mainWay.to,if(mainWay.shape=="loop")"out_and_back" else mainWay.shape,false,line,"",""))
        }
        // A shorter return walk the page describes to a place on a main return walk short of
        // its end (Skyline Gondola top to Ben Lomond Saddle and back) can also go on to the
        // main way's end (to Ben Lomond Summit), as access points do.
        if(mainWay!=null&&mainWay.shape=="out_and_back")for(w in outWays.toList()){if(w.main||w.shape!="out_and_back"||w.from==mainWay.from)continue
            if(outWays.any{it.from==w.from&&it.to==mainWay.to})continue
            // Where it turns, on the main way's line (within 300 m), short of the main way's end.
            val along=DoubleArray(mainWay.line.size);for(i in 1 until mainWay.line.size)along[i]=along[i-1]+DocTracks.meters(mainWay.line[i-1],mainWay.line[i])
            val cut=mainWay.line.indices.minByOrNull{DocTracks.meters(mainWay.line[it],w.line.last())}!!
            if(DocTracks.meters(w.line.last(),mainWay.line[cut])>300||along.last()-along[cut]<200)continue
            val own=DocTracks.length(w.line)
            outWays.add(Way("${w.from} to ${mainWay.to}",w.from,mainWay.to,w.shape,false,w.line+mainWay.line.subList(cut,mainWay.line.size),"","",
                w.stops+mainWay.stops.filter{it.second>along[cut]}.map{it.first to it.second-along[cut]+own}))
        }
        // Ways out part way (fly out of Siberia Valley, a jet boat from Kerin Forks): the main
        // way to where the page leaves it, at a place named on it, else the exit's own place.
        if(mainWay!=null)for(x in r.optJSONArray("exits")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}.orEmpty()){
            val name=x.optString("name");val word=EXIT_WORDS[x.optString("how")]?:"exit"
            val from=if(x.isNull("from"))"" else x.optString("from")
            val along=DoubleArray(mainWay.line.size);for(i in 1 until mainWay.line.size)along[i]=along[i-1]+DocTracks.meters(mainWay.line[i-1],mainWay.line[i])
            val at=mainWay.stops.firstOrNull{from.isNotBlank()&&WalkRoutes.same(it.first,from)}?.second
                ?:target(x,"via").first?.let{t->val c=t.point?:net.points[t.nodes.first()]
                    val i=mainWay.line.indices.minByOrNull{DocTracks.meters(mainWay.line[it],c)}!!
                    along[i].takeIf{DocTracks.meters(mainWay.line[i],c)<=1000}}
            if(at==null||at<200||at>along.last()-200){notes.add("exit $name not placed on the main way");continue}
            val cut=mainWay.line.indices.first{along[it]>=at}
            // The name often says how already ("fly out of Siberia Valley", "Anchorage water taxi").
            val to=if(Regex("\\b(fly|flight|plane|helicopter|boat|taxi|track|road)\\b",RegexOption.IGNORE_CASE).containsMatchIn(name))name.replaceFirstChar{it.uppercase()} else "$name ($word)"
            outWays.add(Way("To $to",mainWay.from,to,"one_way",false,mainWay.line.subList(0,cut+1),"","",
                mainWay.stops.filter{it.second<at}+listOf(to to along[cut])))
        }
        // DOC's line draws every way the page describes (other starts, access tracks): all of
        // them together cover [COVERED] of it.
        if(mainWay!=null&&docAll.isNotEmpty()){
            val pts=input.doc.flatMap{DocTracks.resample(it,20.0,3000)}
            val lines=outWays.map{it.line}+explained
            val share=if(pts.isEmpty())1.0 else pts.count{q->lines.any{DocTracks.distanceTo(it,q)<60}}.toDouble()/pts.size
            if(share<COVERED)flags.add("main way: the ways the page describes cover ${(share*100).roundToInt()}% of DOC's line")
        }
        // Huts: those DOC links that the page says are on the walk, each on its line.
        val linked=ArrayList<WalkRecords.Hut>()
        for(h in r.optJSONArray("huts")?.let{x->(0 until x.length()).map{x.getJSONObject(it)}}.orEmpty()){
            val ref=if(h.isNull("ref"))null else h.optString("ref")
            val l=ref?.let{links[it]}?.takeIf{it.getString("kind")=="hut"}?:continue
            val c=pt(l.optJSONArray("point"))?:continue
            if(linked.none{it.link==l.getString("ref")})linked.add(WalkRecords.Hut(l.getString("name"),c,l.getString("ref")))
        }
        // Huts the page names without linking (Milford's Clinton Hut): DOC's hut of exactly that name near the walk.
        val along=mainWay?.line?:docAll
        if(along.size>=2)for(h in r.optJSONArray("huts")?.let{x->(0 until x.length()).map{x.getJSONObject(it)}}.orEmpty()){
            if(!h.isNull("ref")&&h.optString("ref").let{links[it]?.getString("kind")=="hut"})continue
            val n=DocTracks.hutName(h.optString("name"))
            val m=input.huts.distinctBy{it.link}.filter{DocTracks.hutName(it.name).equals(n,true)&&DocTracks.distanceTo(along,it.point)<2000}
            val one=m.singleOrNull()?:continue
            if(linked.none{it.link==one.link})linked.add(one)
        }
        if(mainWay!=null)for(h in linked){val near=DocTracks.distanceTo(mainWay.line,h.point)
            if(near>600)flags.add("hut ${h.name} is ${near.roundToInt()} m from the main way")}
        val verified=flags.isEmpty()&&mainWay!=null
        // Flagged walks are shown on DOC's own line, with every hut DOC links as on the walk.
        return Result(verified,flags,notes,if(verified)outWays else emptyList(),linked,kind,attempted+(if(verified)emptyList() else outWays))
    }
    /** For a walk that fails its checks: the way along DOC's own line the app shows,
     * chosen by its page. Each way between two ends of DOC's line (and the app's own
     * choice, [WalkRoutes.walkLine]) is scored by the places the reading names on the
     * main way (start, places on the way, end, then its huts) that it passes: most
     * passed, then most in the reading's order, then nearest DOC's stated length, then
     * the app's own choice. Only places the packet gives a point for (links, map
     * features, names that are a point or a flat; not rivers or ridges, whose labels can
     * be anywhere along them). Empty when no place is placed. Second: why, for the report. */
    fun docFallback(input:Input):Pair<List<Coordinate>,String>{
        val doc=input.doc.filter{it.size>=2};if(doc.isEmpty())return emptyList<Coordinate>() to "no DOC line"
        val p=input.packet;val r=input.reading
        fun byId(k:String)=(p.optJSONArray(k)?:JSONArray()).let{a->(0 until a.length()).associate{a.getJSONObject(it).let{l->l.getString("id") to l}}}
        val ids=byId("links")+byId("features")+byId("names")
        val main=r.optJSONArray("ways")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}.orEmpty().firstOrNull{it.optBoolean("main")}
        val places=ArrayList<JSONObject>()
        main?.optJSONObject("start")?.let(places::add)
        main?.optJSONArray("via")?.let{a->for(i in 0 until a.length())places.add(a.getJSONObject(i))}
        main?.optJSONObject("end")?.let(places::add)
        r.optJSONArray("huts")?.let{a->for(i in 0 until a.length())places.add(a.getJSONObject(i))}
        val points=ArrayList<Pair<Coordinate,Double>>();val seen=HashSet<String>()
        for(o in places){val ref=if(o.isNull("ref"))continue else o.optString("ref");if(!seen.add(ref))continue
            val e=ids[ref]?:continue;val k=e.optString("kind")
            if(k=="map track"||k in setOf("river","ridge","area","glacier"))continue
            val a=e.optJSONArray("point")?:continue
            points.add(Coordinate(a.getDouble(0),a.getDouble(1)) to (if(k in setOf("valley","place","lake","coast"))1000.0 else 600.0))}
        if(points.isEmpty())return emptyList<Coordinate>() to "no placed places"
        val own=WalkRoutes.walkLine(null,doc,p.optString("distance"))
        val candidates=(listOf(own)+WalkRoutes.endPaths(doc)).filter{it.size>=2}
        val stated=WalkRoutes.stated(p.optString("distance"))
        class Score(val line:List<Coordinate>,val passed:Int,val ordered:Int,val off:Double,val own:Boolean)
        fun score(line:List<Coordinate>,loose:Double=1.0):Score{
            val along=DoubleArray(line.size);for(i in 1 until line.size)along[i]=along[i-1]+DocTracks.meters(line[i-1],line[i])
            val at=ArrayList<Double>()
            for((c,within) in points){var best=Double.MAX_VALUE;var where=0.0
                for(i in 1 until line.size){val d=DocTracks.distanceTo(listOf(line[i-1],line[i]),c);if(d<best){best=d;where=along[i]}}
                if(best<=within*loose)at.add(where)}
            // Longest run of passed places in the reading's order, either way along the line.
            fun lis(x:List<Double>):Int{val t=ArrayList<Double>();for(v in x){val i=t.binarySearch(v).let{if(it<0)-it-1 else it};if(i==t.size)t.add(v)else t[i]=v};return t.size}
            val m=along.last()
            return Score(line,at.size,max(lis(at),lis(at.map{-it})),stated?.let{abs(m-it)/it}?:0.0,line===own)
        }
        val ranked=candidates.map{score(it)}.sortedWith(compareByDescending<Score>{it.passed}.thenByDescending{it.ordered}.thenBy{it.off}.thenByDescending{it.own}).first()
        // The app's own choice stays unless it clearly misses a place: another way passes
        // more places than it does even at three times the distance (Croesus's Ces Clark
        // Hut is 780 m off it, on a spur; Gillespie's Dans Flat is 5 km off it).
        val best=if(ranked.own||ranked.passed<=score(own,3.0).passed)score(own) else ranked
        return best.line to "DOC's line ${if(best.own)"as the app chooses it" else "chosen by the page"}: passes ${best.passed} of ${points.size} placed places (${best.ordered} in order), ${(DocTracks.length(best.line)/1000).fmt()} km"
    }
    private fun Double.fmt()="%.1f".format(this)
    /** DOC's time as hours (low, high): "3–4 hr", "1 hr 30 min", "45 min", "4 days", "2-3 days". */
    fun hours(time:String):Pair<Double,Double>?{
        val t=time.lowercase().replace('–','-').replace('—','-')
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?)\\s*days?").find(t)?.let{return it.groupValues[1].toDouble()*4 to it.groupValues[2].toDouble()*10}
        Regex("(\\d+(?:\\.\\d+)?)\\s*days?").find(t)?.let{return it.groupValues[1].toDouble()*4 to it.groupValues[1].toDouble()*10}
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours)\\b").find(t)?.let{return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()}
        val h=Regex("(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours)\\b").find(t)?.groupValues?.get(1)?.toDouble()
        val mins=Regex("(\\d+)\\s*(?:-|to)?\\s*(\\d+)?\\s*(?:min|mins|minutes)\\b").find(t)
        if(h!=null){val m=mins?.groupValues?.get(1)?.toDouble()?:0.0;return h+m/60 to h+m/60}
        if(mins!=null){val lo=mins.groupValues[1].toDouble()/60;val hi=(mins.groupValues[2].toDoubleOrNull()?:mins.groupValues[1].toDouble())/60;return lo to hi}
        return null
    }
}
