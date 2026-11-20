package nz.ridgeline.nativeapp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URL
import kotlin.math.*

/** Offline tool (WALKS_DATA=fetch.py's folder, WALKS_TILES=map tile cache,
 * WALKS_PACKETS=output folder; network for tiles not cached): one reading
 * packet per DOC walk for the walk compiler's reading step. A packet holds
 * the page's text as the app shows it (DocPages; photos left out), the huts,
 * campsites and walks the page links (identified exactly by DOC's page
 * paths), and the names the text mentions that the map (LINZ tracks, peaks)
 * or the gazetteer has near the walk, each with an id the reading refers to. */
class WalkPacketsTest{
    private val key by lazy{JSONObject(File("src/main/assets/map-defaults.json").readText()).getString("linzApiKey")}
    private fun bytes(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("Accept-Encoding","identity");inputStream.use{it.readBytes()}}}.getOrNull()
    private fun tile(cache:File,x:Int,y:Int):ByteArray?{
        val f=File(cache,"t14-$x-$y.pbf")
        if(!f.exists()){val b=bytes("https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/14/$x/$y.pbf?api=$key")?:return null
            val t=File(cache,"t14-$x-$y.pbf.part${Thread.currentThread().id}");t.writeBytes(b);t.renameTo(f)}
        var b=f.readBytes();if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes();return b
    }
    /** Zoom-14 tiles within [meters] of [points]. */
    fun corridor(points:List<Coordinate>,meters:Double):Set<Pair<Int,Int>>{
        val out=HashSet<Pair<Int,Int>>();val n=16384.0
        for(p in DocTracks.resample(points,200.0,20000).ifEmpty{points}){
            val dy=meters/111_320.0;val dx=dy/max(.2,cos(Math.toRadians(p.latitude)))
            for(x in floor((p.longitude-dx+180)/360*n).toInt()..floor((p.longitude+dx+180)/360*n).toInt())
                for(y in floor(CameraMath.mercatorY(p.latitude+dy)*n).toInt()..floor(CameraMath.mercatorY(p.latitude-dy)*n).toInt())out.add(x to y)}
        return out
    }
    private val dem=java.util.concurrent.ConcurrentHashMap<Pair<Int,Int>,FloatArray>()
    /** Ground height (m) from LINZ's elevation tiles (zoom 14; -10000 + rgb x 0.1, as the app reads them). */
    fun height(cache:File,c:Coordinate):Double?{
        val n=16384.0;val fx=(c.longitude+180)/360*n;val fy=CameraMath.mercatorY(c.latitude)*n;val x=fx.toInt();val y=fy.toInt()
        val px=dem.getOrPut(x to y){
            val f=File(cache,"e14-$x-$y.png")
            if(!f.exists()){val b=bytes("https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/14/$x/$y.png?pipeline=terrain-rgb&api=$key")?:return null
                val t=File(cache,"e14-$x-$y.png.part${Thread.currentThread().id}");t.writeBytes(b);t.renameTo(f)}
            val img=png(f.readBytes())?:return null
            FloatArray(256*256){i->val argb=img[i];if((argb ushr 24)==0)Float.NaN else (-10000+(argb and 0xffffff)*.1).toFloat()}}
        val v=px[((fy-y)*256).toInt().coerceIn(0,255)*256+((fx-x)*256).toInt().coerceIn(0,255)];return if(v.isNaN())null else v.toDouble()
    }
    /** A 256 x 256, 8-bit RGB or RGBA, non-interlaced PNG as ARGB pixels (LINZ's elevation tiles). */
    fun png(b:ByteArray):IntArray?{
        var i=8;var w=0;var h=0;var type=0;val idat=java.io.ByteArrayOutputStream()
        while(i+8<=b.size){val len=java.nio.ByteBuffer.wrap(b,i,4).int;val t=String(b,i+4,4,Charsets.ISO_8859_1)
            if(t=="IHDR"){w=java.nio.ByteBuffer.wrap(b,i+8,4).int;h=java.nio.ByteBuffer.wrap(b,i+12,4).int;if(b[i+16].toInt()!=8||b[i+20].toInt()!=0)return null;type=b[i+17].toInt()}
            if(t=="IDAT")idat.write(b,i+8,len);if(t=="IEND")break;i+=12+len}
        val bpp=when(type){2->3;6->4;else->return null};if(w!=256||h!=256)return null
        val raw=java.util.zip.InflaterInputStream(idat.toByteArray().inputStream()).readBytes();val stride=w*bpp
        val out=IntArray(w*h);val prev=IntArray(stride);val cur=IntArray(stride)
        for(y in 0 until h){val f=raw[y*(stride+1)].toInt()
            for(x in 0 until stride){val r=raw[y*(stride+1)+1+x].toInt() and 0xff;val a=if(x>=bpp)cur[x-bpp] else 0;val up=prev[x];val c=if(x>=bpp)prev[x-bpp] else 0
                cur[x]=(r+when(f){1->a;2->up;3->(a+up)/2;4->{val pp=a+up-c;val pa=abs(pp-a);val pb=abs(pp-up);val pc=abs(pp-c);if(pa<=pb&&pa<=pc)a else if(pb<=pc)up else c};else->0}) and 0xff}
            for(x in 0 until w){val o=x*bpp;out[y*w+x]=((if(bpp==4)cur[o+3] else 255) shl 24) or (cur[o] shl 16) or (cur[o+1] shl 8) or cur[o+2]}
            cur.copyInto(prev)}
        return out
    }
    private val generic=Regex("\\b(track|tracks|walk|walks|walkway|trail|route|routes|circuit|loop)\\b")
    private fun core(name:String)=generic.replace(PlaceSearch.normalize(name),"").replace(Regex("\\s+")," ").trim()
    private val anchor=Regex("""<a\b[^>]*?\bhref\s*=\s*"([^"]+)"[^>]*>(.*?)</a>""",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private fun pathOf(href:String)=runCatching{java.net.URI(href.trim().replace(" ","%20")).let{u->if(u.host!=null&&!u.host.endsWith("doc.govt.nz"))null else u.path}}.getOrNull()?.trimEnd('/')?.lowercase()

    @Test fun packets(){
        val data=File(System.getenv("WALKS_DATA")?:return);val tiles=File(System.getenv("WALKS_TILES")!!).apply{mkdirs()};val out=File(System.getenv("WALKS_PACKETS")!!).apply{mkdirs()}
        val only=System.getenv("WALKS_ONLY").orEmpty().split("|").filter{it.isNotBlank()}.toSet()
        val gazetteer=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val walks=JSONArray(File(data,"walks.json").readText()).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
        fun table(name:String)=JSONArray(File(data,name).readText()).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
        val huts=table("huts.json");val camps=table("campsites.json")
        val byPath=HashMap<String,Pair<String,JSONObject>>()
        for(h in huts)h.optString("path").takeIf{it.isNotBlank()&&it!="null"}?.let{byPath[it.trimEnd('/').lowercase()]="hut" to h}
        for(c in camps)c.optString("path").takeIf{it.isNotBlank()&&it!="null"}?.let{byPath[it.trimEnd('/').lowercase()]="campsite" to c}
        for(w in walks)w.optString("path").takeIf{it.isNotBlank()&&it!="null"}?.let{byPath[it.trimEnd('/').lowercase()]="walk" to w}
        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        val jobs=walks.filter{it.optBoolean("page")&&(only.isEmpty()||it.getString("guid") in only||it.optString("name") in only)}.map{w->pool.submit<String>{
            val guid=w.getString("guid");val g=guid.replace("-","")
            val html=File(data,"pages/$g.html").readText();val o=DocPages.overview(html);val sections=DocPages.parse(html)
            val line=File(data,"lines/$g.json").takeIf{it.exists()}?.let{DocTracks.parseLines(it.readText())}.orEmpty()
            val point=w.optJSONArray("point")?.let{Coordinate(it.getDouble(0),it.getDouble(1))}
            val around=line.flatten().ifEmpty{listOfNotNull(point)}
            // The page's text, section by section, as the app shows it (photos left out).
            val text=JSONArray()
            for(s in sections){val b=StringBuilder()
                for(k in DocPages.words(s.blocks))when(k){is DocPages.Heading->b.append("\n### ").append(k.text).append('\n');is DocPages.Bullet->b.append("- ").append(k.text).append('\n');else->b.append(k.text).append('\n')}
                text.put(JSONObject().put("title",s.title).put("text",b.toString().trim()))}
            val all=(0 until text.length()).joinToString("\n"){text.getJSONObject(it).getString("text")}
            val normal=" "+PlaceSearch.normalize(all)+" "
            // Huts, campsites and walks the page links, by DOC's own page paths.
            val links=JSONArray();val seen=HashSet<String>()
            for(m in anchor.findAll(html)){val p=pathOf(m.groupValues[1])?:continue;val (kind,obj)=byPath[p]?:continue
                val id=when(kind){"walk"->obj.getString("guid");else->obj.getString("staticLink")};if(kind=="walk"&&id==guid||!seen.add(id))continue
                val name=if(kind=="walk")obj.optString("name").ifBlank{DocPages.text(m.groupValues[2])}else obj.getString("name")
                val pt=obj.optJSONArray("point")
                links.put(JSONObject().put("id","L${links.length()+1}").put("kind",kind).put("name",name).put("anchor",DocPages.text(m.groupValues[2])).put("ref",id)
                    .put("point",pt?.let{JSONArray().put(it.getDouble(0)).put(it.getDouble(1))}?:JSONObject.NULL))}
            // Names near the walk that the text mentions: map tracks and peaks, gazetteer places.
            val cands=JSONArray();val features=JSONArray()
            if(around.isNotEmpty()){
                val tracks=HashSet<String>();val peaks=HashMap<String,Coordinate>();val labels=HashMap<String,Pair<String,Coordinate>>()
                val pieces=ArrayList<Pair<String,List<Coordinate>>>();val roads=ArrayList<Pair<String,List<Coordinate>>>();val lifts=ArrayList<Pair<String,List<Coordinate>>>()
                fun c(p:VectorTile.Point)=Coordinate(p.x*360-180,CameraMath.latitude(p.y))
                for((x,y) in corridor(around,3000.0)){val b=tile(tiles,x,y)?:continue;val id=GpuUploadQueue.Id(14,x,y)
                    for(f in VectorTile.decode(b,14,x,y,setOf("streets","place_labels","aerialways"))){val n=(f.properties["name"] as? String)
                        if(f.layer=="aerialways"){for(path in f.paths)if(path.size>=2)lifts.add((n?:(f.properties["kind"] as? String)?.replace('_',' ')?:"aerial lift") to path.map(::c));continue}
                        if(f.layer=="streets"){for(path in f.paths)for(part in BoundaryIndex.clip(path,id.bounds))if(part.size>=2){
                            if(f.properties["kind"]=="track")pieces.add(n.orEmpty() to part.map(::c)) else if(n!=null)roads.add(n to part.map(::c))}
                            if(n!=null&&f.properties["kind"]=="track")tracks.add(n);continue}
                        if(n==null)continue
                        val at=f.paths.firstOrNull()?.firstOrNull()?.let(::c)?:continue
                        if(f.properties["natural"]=="peak")peaks.putIfAbsent(n,at)
                        else labels.putIfAbsent(n,((f.properties["natural"]?:f.properties["place"]?:"label") as String) to at)}}
                val lon0=around.minOf{it.longitude}-.08;val lon1=around.maxOf{it.longitude}+.08;val lat0=around.minOf{it.latitude}-.06;val lat1=around.maxOf{it.latitude}+.06
                val places=gazetteer.within(doubleArrayOf(lon0,lat0,lon1,lat1)).filter{p->around.minOf{DocTracks.meters(it,Coordinate(p.longitude,p.latitude))}<6000}
                fun mentioned(n:String):Boolean{val full=PlaceSearch.normalize(n);val c=core(n)
                    return full.length>=3&&normal.contains(" $full ")||c.length>=4&&c!=full&&normal.contains(" $c ")}
                for(t in tracks.sorted())if(mentioned(t))cands.put(JSONObject().put("id","T${cands.length()+1}").put("kind","map track").put("name",t))
                for((n,c) in peaks.toSortedMap())if(mentioned(n))cands.put(JSONObject().put("id","T${cands.length()+1}").put("kind","map peak").put("name",n).put("point",JSONArray().put(c.longitude).put(c.latitude)))
                for(p in places.sortedBy{it.name})if(p.kind!="walk"&&mentioned(p.name))cands.put(JSONObject().put("id","T${cands.length()+1}").put("kind",p.kind).put("name",p.name).put("point",JSONArray().put(p.longitude).put(p.latitude)))
            // Map features a walk can start or end at that have no gazetteer name:
            // where the area's tracks meet a named road (the tracks the text names, or
            // near DOC's line ends), the lower and upper ends of aerial lifts, other map labels.
            run{
                val ends=line.filter{it.size>=2}.flatMap{listOf(it.first(),it.last())}.ifEmpty{around}
                val namedTracks=(0 until cands.length()).map{cands.getJSONObject(it)}.filter{it.getString("kind")=="map track"}.map{it.getString("name")}.toSet()
                val seenEnds=ArrayList<Coordinate>()
                for((tn,pts) in pieces)for(e in listOf(pts.first(),pts.last())){
                    if(!(tn in namedTracks||ends.any{DocTracks.meters(it,e)<400}))continue
                    val road=roads.minByOrNull{DocTracks.distanceTo(it.second,e)}?.takeIf{DocTracks.distanceTo(it.second,e)<60}?:continue
                    if(seenEnds.any{DocTracks.meters(it,e)<80})continue;seenEnds.add(e)
                    features.put(JSONObject().put("id","F${features.length()+1}").put("kind","road end").put("name","where ${tn.ifBlank{"an unnamed track"}} meets ${road.first}").put("point",JSONArray().put(e.longitude).put(e.latitude)))}
                // A lift's line is split at tile edges: its ends are the two points of all
                // its pieces furthest apart (lifts are straight).
                for((ln,group) in lifts.groupBy{it.first}){val all=group.flatMap{it.second};if(all.size<2)continue
                    val p0=all.maxByOrNull{DocTracks.meters(it,all[0])}!!;val p1=all.maxByOrNull{DocTracks.meters(it,p0)}!!
                    val pts=listOf(p0,p1)
                    if(ends.none{e->DocTracks.distanceTo(pts,e)<5000})continue
                    val ha=height(tiles,pts.first());val hb=height(tiles,pts.last())
                    if(ha==null||hb==null||abs(ha-hb)<20)continue
                    val (lo,hi)=if(ha<hb)pts.first() to pts.last() else pts.last() to pts.first()
                    for((which,pt) in listOf("lower" to lo,"upper" to hi))features.put(JSONObject().put("id","F${features.length()+1}").put("kind","lift end").put("name","$which end of $ln (${height(tiles,pt)!!.roundToInt()} m)").put("point",JSONArray().put(pt.longitude).put(pt.latitude)))}
                for((n,kp) in labels.toSortedMap())if(mentioned(n))features.put(JSONObject().put("id","F${features.length()+1}").put("kind","map label ${kp.first}").put("name",n).put("point",JSONArray().put(kp.second.longitude).put(kp.second.latitude)))
            }
            }
            val packet=JSONObject().put("guid",guid).put("name",o.title.ifBlank{w.optString("name")}).put("listedName",w.optString("name"))
                .put("difficulty",o.difficulty.ifBlank{w.optString("difficulty")}).put("time",o.time.ifBlank{w.optString("time")}).put("distance",o.distance)
                .put("hasDocLine",line.isNotEmpty()).put("docLineKm",line.sumOf{DocTracks.length(it)}/1000)
                .put("sections",text).put("links",links).put("names",cands).put("features",features)
            File(out,"$g.json").writeText(packet.toString(1))
            "$g ${all.length} ${links.length()} ${cands.length()}"
        }}
        val rows=jobs.map{it.get()};pool.shutdown()
        File(out,"_summary.txt").writeText(rows.joinToString("\n"))
    }
}
