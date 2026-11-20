package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
import java.net.URL
import kotlin.math.*
/** Survey (DIRECTIONS_SURVEY=output, DIRECTIONS_PAGES=folder of DOC pages,
 * DIRECTIONS_CACHE=folder for DOC lines and map tiles, DIRECTIONS_WALKS=names
 * separated by | or empty for all; network): each walk followed the way its
 * description tells it (WalkDirections.route) against its line as before. */
class DirectionsSurveyTest{
    private fun bytes(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("Accept-Encoding","identity");inputStream.use{it.readBytes()}}}.getOrNull()
    private fun cached(cache:File,name:String,url:String):ByteArray?{val f=File(cache,name);if(f.exists())return f.readBytes();val b=bytes(url)?:return null
        val t=File(cache,"$name.part${Thread.currentThread().id}");t.writeBytes(b);t.renameTo(f);return b}
    @Test fun survey(){
        val out=File(System.getenv("DIRECTIONS_SURVEY")?:return);val pages=File(System.getenv("DIRECTIONS_PAGES")!!)
        val cache=File(System.getenv("DIRECTIONS_CACHE")!!).apply{mkdirs()};val only=System.getenv("DIRECTIONS_WALKS").orEmpty().split("|").filter{it.isNotBlank()}.toSet()
        val key=org.json.JSONObject(File("src/main/assets/map-defaults.json").readText()).getString("linzApiKey")
        val gazetteer=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val report=StringBuilder();var n=0;var used=0;var better=0;var worse=0
        for(f in pages.listFiles{x->x.name.endsWith(".html")}!!.sortedBy{it.name}){
            val html=f.readText();val o=DocPages.overview(html);if(o.title.isBlank())continue
            if(only.isNotEmpty()&&o.title !in only)continue
            val target=WalkRoutes.stated(o.distance)
            val g=f.name.removeSuffix(".html");val guid="${g.substring(0,8)}-${g.substring(8,12)}-${g.substring(12,16)}-${g.substring(16,20)}-${g.substring(20)}"
            val sections=DocPages.parse(html);val legs=DocPages.legs(sections)
            if(WalkOptions.alternatives(legs)||DocPages.starts(sections).size>=2)continue
            val line=DocTracks.parseLines(String(cached(cache,"$g.json",DocTracks.lineUrl(guid))?:continue,Charsets.UTF_8));if(line.isEmpty())continue
            val route=WalkRoutes.route(line,legs){nm,c->gazetteer.locate(nm,c,40.0)?.let{Coordinate(it.longitude,it.latitude)}}
            val (_,byLegs)=WalkRoutes.extent(route,line,o.distance);if(byLegs)continue
            val main=WalkRoutes.walkLine(route,line,o.distance);val before=DocTracks.length(main)
            val all=line.flatten();val lat=all.sumOf{it.latitude}/all.size;val dy=2000/111_320.0;val dx=dy/cos(Math.toRadians(lat));val z=16384.0
            val x0=floor((all.minOf{it.longitude}-dx+180)/360*z).toInt();val x1=floor((all.maxOf{it.longitude}+dx+180)/360*z).toInt()
            val y0=floor(CameraMath.mercatorY(all.maxOf{it.latitude}+dy)*z).toInt();val y1=floor(CameraMath.mercatorY(all.minOf{it.latitude}-dy)*z).toInt()
            if((x1-x0+1)*(y1-y0+1)>120)continue
            n++
            val tracks=ArrayList<Pair<String,List<Coordinate>>>();val lifts=ArrayList<List<Coordinate>>();val peaks=ArrayList<Pair<String,Coordinate>>();val roads=ArrayList<Pair<String,List<Coordinate>>>()
            // Tiles fetched 12 at a time (cached for later runs).
            val pool=java.util.concurrent.Executors.newFixedThreadPool(12)
            (x0..x1).flatMap{x->(y0..y1).map{y->x to y}}.map{(x,y)->pool.submit{cached(cache,"t14-$x-$y.pbf","https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/14/$x/$y.pbf?api=$key")}}.forEach{it.get()}
            pool.shutdown()
            for(x in x0..x1)for(y in y0..y1){var b=cached(cache,"t14-$x-$y.pbf","https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/14/$x/$y.pbf?api=$key")?:continue
                if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
                WalkOptions.read(GpuUploadQueue.Id(14,x,y),VectorTile.decode(b,14,x,y,WalkOptions.LAYERS),tracks,lifts,peaks,roads)}
            val box=doubleArrayOf(x0/z*360-180,CameraMath.latitude((y1+1)/z),(x1+1)/z*360-180,CameraMath.latitude(y0/z))
            val places=gazetteer.within(box).map{Triple(it.kind,it.name,Coordinate(it.longitude,it.latitude))}
            System.getenv("DIRECTIONS_FIXTURE")?.let{dir->val d=File(dir,g).apply{mkdirs()}
                fun pts(l:List<Coordinate>)=org.json.JSONArray().apply{for(c in l)put(org.json.JSONArray().put("%.6f".format(c.longitude).toDouble()).put("%.6f".format(c.latitude).toDouble()))}
                val j=org.json.JSONObject()
                j.put("tracks",org.json.JSONArray().apply{for((nm,l) in tracks)put(org.json.JSONObject().put("name",nm).put("points",pts(l)))})
                j.put("roads",org.json.JSONArray().apply{for((nm,l) in roads)put(org.json.JSONObject().put("name",nm).put("points",pts(l)))})
                j.put("lifts",org.json.JSONArray());j.put("peaks",org.json.JSONArray().apply{for((nm,c) in peaks)put(org.json.JSONObject().put("name",nm).put("point",pts(listOf(c)).get(0)))})
                j.put("places",org.json.JSONArray().apply{for(p in gazetteer.within(box))put(org.json.JSONObject().put("kind",p.kind).put("name",p.name).put("point",pts(listOf(Coordinate(p.longitude,p.latitude))).get(0)))})
                j.put("box",org.json.JSONArray().apply{for(v in box)put(v)})
                File(d,"area.json").writeText(j.toString());File(d,"page.html").writeText(html);File(d,"line.json").writeText(String(cached(cache,"$g.json",DocTracks.lineUrl(guid))!!,Charsets.UTF_8))}
            val r=WalkDirections.route(sections,o.title,o.distance,o.time,line,WalkOptions.Area(tracks,lifts,peaks,box,roads),places,main)
            val eBefore=target?.let{abs(before-it)/it}?:0.0
            if(r==null){report.append("-- ${o.title} [${o.distance}] line ${"%.1f".format(before/1000)} km: ${WalkDirections.notes}\n");continue}
            used++;val after=DocTracks.length(r.line);val eAfter=target?.let{abs(after-it)/it}?:0.0
            if(eAfter<eBefore-.05)better++ else if(eAfter>eBefore+.05)worse++
            report.append("== ${o.title} [${o.distance}] line ${"%.1f".format(before/1000)} km -> ${"%.1f".format(after/1000)} km${if(r.loop)" loop" else ""}; via ${r.waypoints}; ends ${"%.0f".format(DocTracks.meters(r.line.first(),r.line.last()))} m apart\n")
        }
        report.insert(0,"walks $n, followed $used (nearer DOC's length $better, further $worse)\n")
        out.writeText(report.toString())
    }
}
