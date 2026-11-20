package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
import java.net.URL
import kotlin.math.*
/** Survey (OPTIONS_SURVEY=output file; OPTIONS_WALKS=walk names separated by |, or
 * "all:N" for N DOC walks whose legs are alternatives; network): each walk's
 * start and end options (WalkOptions.choose) over the map's tracks around it. */
class OptionsSurveyTest{
    private fun bytes(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("Accept-Encoding","identity");inputStream.use{it.readBytes()}}}.getOrNull()
    private fun get(url:String)=bytes(url)?.let{String(it,Charsets.UTF_8)}
    @Test fun survey(){
        val out=File(System.getenv("OPTIONS_SURVEY")?:return);val spec=System.getenv("OPTIONS_WALKS")!!
        val key=org.json.JSONObject(File("src/main/assets/map-defaults.json").readText()).getString("linzApiKey")
        val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val list=HashMap(DocWalks.parseList(get(DocWalks.listUrl())!!))
        // "pages": every DOC page in OPTIONS_PAGES (walks not in DOC's walk list too).
        if(spec=="pages"){val known=list.keys.map{it.replace("-","")}.toSet()
            for(f in File(System.getenv("OPTIONS_PAGES")).listFiles{x->x.name.endsWith(".html")}!!){val g=f.name.removeSuffix(".html");if(g in known)continue
                val o=DocPages.overview(f.readText());if(o.title.isBlank())continue
                val guid="${g.substring(0,8)}-${g.substring(8,12)}-${g.substring(12,16)}-${g.substring(16,20)}-${g.substring(20)}"
                list[guid]=DocWalks.Info(guid,o.title,o.difficulty,o.time,"")}}
        val walks=if(spec=="pages")list.values.sortedBy{it.guid} else if(spec.startsWith("all:"))list.values.sortedBy{it.guid}.take(spec.removePrefix("all:").toInt()) else spec.split("|").mapNotNull{n->list.values.firstOrNull{it.name==n}}
        val report=StringBuilder()
        for(w in walks){
            val local=System.getenv("OPTIONS_PAGES")?.let{File(it,w.guid.replace("-","")+".html")}?.takeIf{it.exists()}
            val html=local?.readText()?:get("https://www.doc.govt.nz/link/${w.guid.replace("-","")}.aspx").orEmpty()
            val sections=DocPages.parse(html);val legs=DocPages.legs(sections);val starts=DocPages.starts(sections)
            if(!WalkOptions.alternatives(legs)&&starts.size<2)continue
            val line=DocTracks.parseLines(get(DocTracks.lineUrl(w.guid)).orEmpty())
            if(line.isEmpty())continue
            val all=line.flatten();val lat=all.sumOf{it.latitude}/all.size;val dy=2000/111_320.0;val dx=dy/cos(Math.toRadians(lat))
            val n=16384.0
            val x0=floor((all.minOf{it.longitude}-dx+180)/360*n).toInt();val x1=floor((all.maxOf{it.longitude}+dx+180)/360*n).toInt()
            val y0=floor(CameraMath.mercatorY(all.maxOf{it.latitude}+dy)*n).toInt();val y1=floor(CameraMath.mercatorY(all.minOf{it.latitude}-dy)*n).toInt()
            if((x1-x0+1)*(y1-y0+1)>120){report.append("== ${w.name}: area too large\n");continue}
            val tracks=ArrayList<Pair<String,List<Coordinate>>>();val lifts=ArrayList<List<Coordinate>>();val peaks=ArrayList<Pair<String,Coordinate>>();val roads=ArrayList<Pair<String,List<Coordinate>>>()
            for(x in x0..x1)for(y in y0..y1){var b=bytes("https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/14/$x/$y.pbf?api=$key")?:continue
                if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
                val id=GpuUploadQueue.Id(14,x,y);WalkOptions.read(id,VectorTile.decode(b,14,x,y,WalkOptions.LAYERS),tracks,lifts,peaks,roads)}
            if(System.getenv("OPTIONS_DEBUG")!=null){val net=WalkOptions.Network(tracks);val comp=IntArray(net.points.size){-1};var k=0
                for(v in net.points.indices)if(comp[v]<0){val st=ArrayDeque(listOf(v));comp[v]=k;while(st.isNotEmpty()){val u=st.removeLast();for(e in net.edges[u])if(comp[e.first]<0){comp[e.first]=k;st.add(e.first)}};k++}
                val names=HashMap<Int,MutableSet<String>>();for(v in net.points.indices)for(e in net.edges[v])names.getOrPut(comp[v]){HashSet()}.add(tracks[e.third].first)
                for((c,ns) in names)if(ns.any{it.isNotBlank()})report.append("   comp $c: ${net.points.indices.count{comp[it]==c}} pts $ns\n")
                for(e in line.flatMap{listOf(it.first(),it.last())})report.append("   doc end $e -> ${net.nearest(e,150.0)?.let{"comp ${comp[it]} ${net.points[it]}"}}\n")
                for(pk in peaks)report.append("   peak ${pk.first} -> ${net.nearest(pk.second,400.0)?.let{"comp ${comp[it]} ${DocTracks.meters(net.points[it],pk.second).toInt()} m"}}\n")
                for(tr in tracks)if(tr.first.contains("Lomond")||tr.first.contains("Tiki"))report.append("   piece ${tr.first} ${tr.second.first()} .. ${tr.second.last()} comp ${net.nearest(tr.second.first(),1.0)?.let{comp[it]}}\n")
            }
            System.getenv("OPTIONS_FIXTURE")?.let{dir->val d=File(dir);d.mkdirs()
                fun pts(l:List<Coordinate>)=org.json.JSONArray().apply{for(c in l)put(org.json.JSONArray().put("%.6f".format(c.longitude).toDouble()).put("%.6f".format(c.latitude).toDouble()))}
                val j=org.json.JSONObject()
                j.put("tracks",org.json.JSONArray().apply{for((nm,l) in tracks)put(org.json.JSONObject().put("name",nm).put("points",pts(l)))})
                j.put("lifts",org.json.JSONArray().apply{for(l in lifts)put(pts(l))})
                j.put("roads",org.json.JSONArray().apply{for((nm,l) in roads)put(org.json.JSONObject().put("name",nm).put("points",pts(l)))})
                j.put("peaks",org.json.JSONArray().apply{for((nm,c) in peaks)put(org.json.JSONObject().put("name",nm).put("point",pts(listOf(c)).get(0)))})
                File(d,"area.json").writeText(j.toString());File(d,"page.html").writeText(html);File(d,"line.json").writeText(get(DocTracks.lineUrl(w.guid)).orEmpty())}
            val t=System.nanoTime()
            val loc:(String,Coordinate)->Coordinate?={nm,c->places.locate(nm,c,15.0)?.let{Coordinate(it.longitude,it.latitude)}}
            val ar=WalkOptions.Area(tracks,lifts,peaks,null,roads)
            var choice=WalkOptions.choose(legs,line,ar,loc);val legNotes=WalkOptions.notes.toString()
            if(choice==null&&starts.size>=2){choice=WalkOptions.fromStarts(starts,w.name,w.time,line,ar,loc);WalkOptions.notes.insert(0,legNotes)}
            if(starts.isNotEmpty())report.append("   roads: ${roads.map{it.first}.distinct().take(20)}\n")
            if(starts.isNotEmpty())report.append("   prose starts: ${starts.map{it.place+(it.track?.let{t->" (on $t)"}?:"")}}\n")
            val ms=(System.nanoTime()-t)/1e6
            report.append("== ${w.name} [${DocPages.overview(html).distance}] DOC line ${"%.1f".format(line.sumOf{DocTracks.length(it)}/1000)} km; ${tracks.size} map pieces; ${"%.0f".format(ms)} ms\n")
            for(l in legs)report.append("   leg: ${l.title} | ${l.time} | ${l.km} km -> one way ${WalkOptions.oneWay(l)?.let{"%.1f".format(it/1000)}}\n")
            report.append(WalkOptions.notes.lines().filter{it.isNotBlank()}.joinToString(""){"   note: $it\n"})
            report.append("   lifts ${lifts.size}; peaks ${peaks.map{it.first}.distinct().take(12)}\n")
            if(choice==null){report.append("   no options\n");continue}
            report.append("   starts: ${choice.starts}; ends: ${choice.ends}\n")
            for(o in choice.options){val km=DocTracks.length(o.line)/1000
                val cover=line.flatten().count{p->DocTracks.distanceTo(o.line,p)<60}.toDouble()/max(1,line.flatten().size)
                report.append("   ${choice.starts[o.start]} -> ${choice.ends[o.end]}: ${"%.2f".format(km)} km${o.leg?.let{" (DOC ${WalkOptions.oneWay(it)?.let{v->"%.1f".format(v/1000)}} km)"}?:" (combined)"}; DOC line covered ${"%.0f".format(cover*100)}%; on DOC line ${"%.0f".format(WalkOptions.onLine(o.line,line)*100)}%; tracks ${o.pieces.map{it.first}.filter{it.isNotBlank()}.distinct()}\n")}
        }
        out.writeText(report.toString())
    }
    /** The map's tracks, lifts and peaks within [margin] of [all] (null: over 120 tiles). */
    private fun area(all:List<Coordinate>,margin:Double,key:String):WalkOptions.Area?{
        val lat=all.sumOf{it.latitude}/all.size;val dy=margin/111_320.0;val dx=dy/cos(Math.toRadians(lat));val n=16384.0
        val x0=floor((all.minOf{it.longitude}-dx+180)/360*n).toInt();val x1=floor((all.maxOf{it.longitude}+dx+180)/360*n).toInt()
        val y0=floor(CameraMath.mercatorY(all.maxOf{it.latitude}+dy)*n).toInt();val y1=floor(CameraMath.mercatorY(all.minOf{it.latitude}-dy)*n).toInt()
        if((x1-x0+1)*(y1-y0+1)>120)return null
        val tracks=ArrayList<Pair<String,List<Coordinate>>>();val lifts=ArrayList<List<Coordinate>>();val peaks=ArrayList<Pair<String,Coordinate>>()
        for(x in x0..x1)for(y in y0..y1){var b=bytes("https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/14/$x/$y.pbf?api=$key")?:continue
            if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
            WalkOptions.read(GpuUploadQueue.Id(14,x,y),VectorTile.decode(b,14,x,y,WalkOptions.LAYERS),tracks,lifts,peaks)}
        return WalkOptions.Area(tracks,lifts,peaks,doubleArrayOf(x0/n*360-180,CameraMath.latitude((y1+1)/n),(x1+1)/n*360-180,CameraMath.latitude(y0/n)))
    }
    /** EXTEND_SURVEY=output file, EXTEND_WALKS=all:N: walks whose line (as
     * WalkRoutes.walkLine) is under 75% of DOC's stated length, and the line
     * continued to its tracks' natural ends (WalkOptions.extend). */
    @Test fun extendSurvey(){
        val out=File(System.getenv("EXTEND_SURVEY")?:return);val n=System.getenv("EXTEND_WALKS")!!.removePrefix("all:").toInt()
        val key=org.json.JSONObject(File("src/main/assets/map-defaults.json").readText()).getString("linzApiKey")
        val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val list=DocWalks.parseList(get(DocWalks.listUrl())!!)
        val report=StringBuilder();var short=0;var fixed=0
        for(w in list.values.sortedBy{it.guid}.take(n)){
            val html=System.getenv("OPTIONS_PAGES")?.let{File(it,w.guid.replace("-","")+".html")}?.takeIf{it.exists()}?.readText()?:get("https://www.doc.govt.nz/link/${w.guid.replace("-","")}.aspx").orEmpty();val o=DocPages.overview(html)
            val target=WalkRoutes.stated(o.distance)?:continue
            val sections=DocPages.parse(html);val legs=DocPages.legs(sections);if(WalkOptions.alternatives(legs)||DocPages.starts(sections).size>=2)continue
            val line=DocTracks.parseLines(get(DocTracks.lineUrl(w.guid)).orEmpty());if(line.isEmpty())continue
            val route=WalkRoutes.route(line,legs){nm,c->places.locate(nm,c,40.0)?.let{Coordinate(it.longitude,it.latitude)}}
            val (_,byLegs)=WalkRoutes.extent(route,line,o.distance);if(byLegs)continue
            val main=WalkRoutes.walkLine(route,line,o.distance);val have=DocTracks.length(main)
            if(main.size<2||have>=(1-WalkOptions.MATCH)*target)continue
            short++
            val a=area(main,(target-have).coerceIn(500.0,3000.0),key)
            val ext=a?.let{WalkOptions.extend(main,it,target)}
            if(ext!=null)fixed++
            report.append("${w.name} [${o.distance}]: line ${"%.1f".format(have/1000)} km -> ${ext?.let{"%.1f km".format(DocTracks.length(it)/1000)}?:if(a==null)"area too large" else "unchanged"}\n")
        }
        report.append("short $short, extended $fixed\n")
        out.writeText(report.toString())
    }
}
