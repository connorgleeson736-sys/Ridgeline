package nz.ridgeline.nativeapp

import kotlin.math.*

/** A DOC walk followed the way DOC's description tells it ("Taking the loop
 * clockwise, cross the bridge … to Elfin Bay … reaching picturesque Lake Rere
 * … join the Greenstone Track. To finish this loop continue on Greenstone
 * Track back to the start"): the map's tracks and the places (huts, lakes,
 * bays, peaks, saddles…) the description names, in the order it names them,
 * are followed over the map's tracks from the walk's start; a loop comes back
 * to its start without retracing itself, "return the same way" is out and
 * back. Side trips, alternatives, options and views are not followed. The
 * route is used only when it is within [WalkOptions.MATCH] of the length DOC
 * states and covers [COVERED] of DOC's line. Pure Kotlin, unit tested on real data. */
internal object WalkDirections {
    /** Share of DOC's line the route must cover. */
    const val COVERED=.8
    /** A named place this close to a track is reached by it. */
    const val NEAR_METERS=400.0
    /** DOC's stated length is a loose check only: within this share. */
    const val LOOSE=.5
    /** A route not borne out by DOC's stated length is at most this times DOC's line. */
    const val LONGER=1.6
    /** A step between two named places at most this times their distance apart (plus 1.5 km). */
    const val DETOUR=3.0
    private val HOURS=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(?:[-–]|to)?\\s*(?:([0-9]+(?:\\.[0-9]+)?)\\s*)?(h|hr|hrs|hour|hours|min|mins|minutes)\\b",RegexOption.IGNORE_CASE)
    /** [pieces]: the map's named tracks it follows. */
    class Result(val line:List<Coordinate>,val waypoints:List<String>,val loop:Boolean,val pieces:List<Pair<String,List<Coordinate>>> = emptyList())
    /** Why the last [route] was not used (surveys). */
    val notes=StringBuilder()
    private val SKIP_SENTENCE=Regex("\\b(side ?trips?|alternatively|alternative|optional|options?|can also|for a (?:longer|shorter)|if you (?:have|want|wish|prefer|are|would)|you (?:can|could|may|might)|extend (?:your|the)|detour|extension|instead)\\b",RegexOption.IGNORE_CASE)
    private val SKIP_HEADING=Regex("\\b(side ?trips?|options?|alternative|extension|continue on|mountain bik|biking|other (?:walks|tracks)|nearby|history|wildlife|nature|plants|birds)\\b",RegexOption.IGNORE_CASE)
    private val OUT_AND_BACK=Regex("\\b(return (?:the same way|via the same|by the same|along the same|back the same)|retrace|same way back|back the way you came)\\b",RegexOption.IGNORE_CASE)
    private val LOOP=Regex("\\b(loop|circuit|round trip)\\b",RegexOption.IGNORE_CASE)
    /** A mention after one of these is something seen, not walked to. */
    private val SEEN=Regex("\\b(views?|view of|overlook(?:s|ing)?|looking|towards?|across to|see|seen|glimpses?|vistas?|panorama|named after|beyond|behind)\\W+(?:\\w+\\W+){0,4}$",RegexOption.IGNORE_CASE)
    /** Gazetteer kinds that are a point a track goes to. */
    private val KINDS=setOf("hut","camp","lake","peak","pass","waterfall","coast","place","walk")

    /** The sentences DOC's description gives as the way to walk it, in order. */
    fun sentences(sections:List<DocPages.Section>):List<String>{
        val d=sections.firstOrNull{it.title.contains("description",true)}?:return emptyList()
        val out=ArrayList<String>();var skip=false
        for(b in DocPages.words(d.blocks)){
            if(b is DocPages.Heading){skip=SKIP_HEADING.containsMatchIn(b.text);continue}
            if(skip)continue
            for(s in b.text.split(Regex("(?<=[.!?])\\s+")))if(s.isNotBlank()&&!SKIP_SENTENCE.containsMatchIn(s))out.add(s)
        }
        return out
    }
    /** Names (map tracks and [places]) in [sentences], in the order mentioned:
     * the longest name at each place in the text; things seen are left out. */
    fun mentions(sentences:List<String>,names:Collection<String>,tracks:Set<String> = emptySet(),blocked:Collection<String> = emptyList()):List<String>{
        val keys=names.filter{it.isNotBlank()}.associateBy{PlaceSearch.normalize(it)}.filterKeys{it.length>=4}
        // Names that hide shorter ones inside them but are not followed ("the
        // start of the Lake Rere Walk" is not Lake Rere).
        val blocks=blocked.map{PlaceSearch.normalize(it)}.filter{it.length>=4}
        val out=ArrayList<String>()
        for(s in sentences){
            val text=" "+PlaceSearch.normalize(s)+" "
            val found=ArrayList<Triple<Int,Int,String?>>()
            for((k,n) in keys){var i=text.indexOf(" $k ");while(i>=0){found.add(Triple(i,i+k.length+1,n));i=text.indexOf(" $k ",i+1)}}
            for(k in blocks){var i=text.indexOf(" $k ");while(i>=0){found.add(Triple(i,i+k.length+1,null));i=text.indexOf(" $k ",i+1)}}
            var end=-1
            for((a,b,n) in found.sortedWith(compareBy({it.first},{-(it.second-it.first)}))){
                if(a<end)continue;end=b
                if(n==null)continue
                val before=text.substring(0,a+1)
                if(SEEN.containsMatchIn(before))continue
                // A track is walked only when the text says so ("join the Greenstone
                // Track", "continue on Greenstone Track"), not where it is mentioned.
                if(n in tracks&&!WALKED.containsMatchIn(before))continue
                // Each place once (a later "back to …" is the way back, not a new place).
                if(n !in out)out.add(n)
            }
        }
        return out
    }
    private val WALKED=Regex("\\b(follow|follows|following|continue|continues|continuing|take|takes|taking|join|joins|joining|along|via|onto|on to|back on|up|down|walk|walking|climb|climbs|on)\\W+(?:\\w+\\W+){0,3}$",RegexOption.IGNORE_CASE)
    /** The walk as DOC's [sections] describe it over the map's tracks in [area];
     * [walk]: its name; [distance]: DOC's stated length ("14 km loop");
     * [places]: gazetteer places around (kind, name, point); [doc]: DOC's line. */
    fun route(sections:List<DocPages.Section>,walk:String,distance:String,time:String,doc:List<List<Coordinate>>,area:WalkOptions.Area,places:List<Triple<String,String,Coordinate>>,current:List<Coordinate> = emptyList()):Result?{
        notes.setLength(0)
        // DOC's stated length can be well out: only a loose bound ([LOOSE]).
        val target=WalkRoutes.stated(distance)
        if(doc.isEmpty()||area.tracks.isEmpty())return fail("no line or map")
        // A page for several tracks ("Waingongoro Hut tracks") describes several walks, not one way.
        if(DocWalks.collection(walk))return fail("several tracks")
        val text=sentences(sections);if(text.isEmpty())return fail("no description")
        val all=text.joinToString(" ")
        val loop=LOOP.containsMatchIn(distance)||LOOP.containsMatchIn(walk)&&!OUT_AND_BACK.containsMatchIn(all)
        val net=WalkOptions.Network(area.tracks);if(net.points.size<2)return fail("no map")
        // Things the description can name: the map's tracks (not the walk's own
        // name: "the start of the Lake Rere Walk"), peaks and gazetteer places.
        val trackNames=area.tracks.map{it.first}.filter{it.isNotBlank()&&!DocWalks.sameName(it,walk)}.toSet()
        val walkKey=PlaceSearch.normalize(walk)
        val points=HashMap<String,Coordinate>()
        // Places may share the walk's name (Lake Rere on the Lake Rere Walk); DOC's walk entries do not.
        for((kind,n,c) in places)if(kind in KINDS&&!(kind=="walk"&&DocWalks.sameName(n,walk)))points.putIfAbsent(n,c)
        for((n,c) in area.peaks)points.putIfAbsent(n,c)
        // Not the walk's own full name ("the start of the Lake Rere Walk").
        val named=mentions(text,trackNames+points.keys.filter{PlaceSearch.normalize(it)!=walkKey},trackNames,listOf(walk))
        // Each to the map: a track's points, or a place's nearest track point.
        val trackNodes=HashMap<String,Set<Int>>()
        fun nodesOf(n:String)=trackNodes.getOrPut(n){val ids=area.tracks.withIndex().filter{it.value.first==n}.map{it.index}.toSet()
            net.points.indices.filter{v->net.edges[v].any{it.third in ids}}.toSet()}
        val ends=doc.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.mapNotNull{net.nearest(it,150.0)}.distinct()
        if(ends.isEmpty())return fail("DOC's line is off the map's tracks")
        // Only the map's tracks joined to DOC's line count (a place can be
        // nearest some other, separate track).
        val reach=net.from(ends[0]).first;val joined={v:Int->reach[v]<Double.MAX_VALUE}
        val targets=named.mapNotNull{n->if(n in trackNames)(n to nodesOf(n).filter(joined).toSet()).takeIf{it.second.isNotEmpty()}
            else points[n]?.let{c->net.points.indices.filter(joined).minByOrNull{DocTracks.meters(net.points[it],c)}?.takeIf{DocTracks.meters(net.points[it],c)<=NEAR_METERS}}?.let{n to setOf(it)}}
        if(targets.isEmpty()&&!loop)return fail("names nothing on the map ($named)")
        // The start: the end of DOC's line nearer a road (the car park); the
        // end: back to it (a loop), the last place named (out and back), or
        // the far end of DOC's line.
        fun road(v:Int)=area.roads.minOfOrNull{DocTracks.distanceTo(it.second,net.points[v])}?:Double.MAX_VALUE
        val first=targets.firstOrNull()?.second
        val start=ends.minWithOrNull(compareBy({min(road(it),1000.0)},{e->first?.minOf{DocTracks.meters(net.points[it],net.points[e])}?:0.0}))!!
        val walked=HashSet<Long>();val path=arrayListOf(start);var at=start
        var detour=""
        fun go(to:Set<Int>,again:Double):Boolean{
            val (dist,prev)=net.from(at,emptySet(),walked,again)
            val v=to.minByOrNull{dist[it]}?.takeIf{dist[it]<Double.MAX_VALUE}?:return false
            val p=net.path(prev,v)
            // No long way round between two places the description names one after the other.
            val m=DocTracks.length(p.map{net.points[it]});val direct=DocTracks.meters(net.points[at],net.points[v])
            if(m>DETOUR*direct+1500)detour="${m.roundToInt()} m for ${direct.roundToInt()} m"
            for(i in 1 until p.size)walked.add(net.step(p[i-1],p[i]))
            path.addAll(p.drop(1));at=v;return true
        }
        val again=if(OUT_AND_BACK.containsMatchIn(all))1.0 else 4.0
        val reached=ArrayList<String>()
        for((n,to) in targets){if(at in to){reached.add(n);continue};if(go(to,again))reached.add(n)}
        if(detour.isNotEmpty())return fail("a long way round: $detour ($reached)")
        if(loop){if(at!=start&&!go(setOf(start),again))return fail("cannot close the loop")}
        else{
            // On to the far end of DOC's line, unless the route has been there.
            val far=ends.filter{it!=start}.maxByOrNull{DocTracks.meters(net.points[it],net.points[start])}
            // (Out and back too: the far end is where it turns, often named only in
            // the walk's own name: the Gertrude Saddle Route past Black Lake.)
            if(far!=null&&path.none{DocTracks.meters(net.points[it],net.points[far])<50})go(setOf(far),again)
        }
        if(loop&&detour.isNotEmpty())return fail("a long way round: $detour")
        val line=path.map{net.points[it]}
        if(line.size<2)return fail("no route")
        val m=DocTracks.length(line)
        val cover=WalkOptions.covered(doc,line)
        if(cover<COVERED)return fail("covers ${(cover*100).roundToInt()}% of DOC's line ($reached)")
        // DOC's time at a walking pace (0.75 to 6 km/h), and DOC's length loosely.
        val h=HOURS.find(time);val back=if(Regex("\\breturn\\b",RegexOption.IGNORE_CASE).containsMatchIn(time)&&!LOOP.containsMatchIn(time))2.0 else 1.0
        val low=h?.groupValues?.get(1)?.toDoubleOrNull()?.let{if(h.groupValues[3].startsWith("m",true))it/60 else it}?.div(back)
        val high=(h?.groupValues?.get(2)?.toDoubleOrNull()?:h?.groupValues?.get(1)?.toDoubleOrNull())?.let{if(h!!.groupValues[3].startsWith("m",true))it/60 else it}?.div(back)
        if(low!=null&&high!=null&&(m<low*750||m>high*6000))return fail("${m.roundToInt()} m in $time ($reached)")
        if(target!=null&&abs(m-target)>LOOSE*target)return fail("${m.roundToInt()} m, DOC ${target.roundToInt()} m ($reached)")
        // DOC's own line is the other check: mostly along it and not much longer,
        // unless DOC's stated length clearly says the walk is longer (DOC's line
        // for the Tongariro Alpine Crossing is 300 m).
        val agrees=target!=null&&abs(m-target)<=WalkOptions.MATCH*target
        val docM=doc.sumOf{DocTracks.length(it)};val share=WalkOptions.onLine(line,doc)
        if(!agrees&&m>LONGER*docM)return fail("${m.roundToInt()} m for DOC's ${docM.roundToInt()} m line ($reached)")
        if(!agrees&&share<WalkOptions.ON_LINE)return fail("${(share*100).roundToInt()}% on DOC's line ($reached)")
        // Not instead of a line that already fits DOC's length better.
        if(target!=null&&current.size>=2){val before=abs(DocTracks.length(current)-target);if(abs(m-target)>before+.1*target)return fail("further from DOC's length than before ($reached)")}
        if(target==null&&h==null)return fail("no length or time to check")
        return Result(WalkOptions.turn(line,if(loop)DocPages.clockwise(sections)else null),reached,loop,net.trackNames(path))
    }
    private fun fail(why:String):Result?{notes.append(why);return null}
}
