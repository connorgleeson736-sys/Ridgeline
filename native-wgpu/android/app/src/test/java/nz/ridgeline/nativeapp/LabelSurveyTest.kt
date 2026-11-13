package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
import java.net.URL
import kotlin.math.*
/** Survey (LABEL_SURVEY=output file; LABEL_WALKS=walk names separated by |;
 * network): for each walk, the app's own labelling (DocWalks.mark) of every
 * map track along DOC's line, using DOC's walk data for the zoom-10 data
 * tiles there, and the summary line (WalkRoutes.walkLine) against DOC's line. */
class LabelSurveyTest{
    private fun bytes(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("Accept-Encoding","identity");inputStream.use{it.readBytes()}}}.getOrNull()
    private fun get(url:String)=bytes(url)?.let{String(it,Charsets.UTF_8)}
    @Test fun survey(){
        val out=File(System.getenv("LABEL_SURVEY")?:return);val names=System.getenv("LABEL_WALKS")!!.split("|")
        val key=org.json.JSONObject(File("src/main/assets/map-defaults.json").readText()).getString("linzApiKey")
        val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val list=DocWalks.parseList(get(DocWalks.listUrl())!!)
        val report=StringBuilder();val mains=HashMap<String,List<List<Coordinate>>>()
        for(walkName in names){
            val target=list.values.firstOrNull{it.name==walkName}
            // Walks missing from DOC's list are found by name in the gazetteer's DOC walks (link) or given as guid.
            val guid=target?.guid?:walkName.takeIf{it.length==36}?:places.locate(walkName,Coordinate(172.5,-41.5),2000.0)?.link?.let(DocTracks::guidOf)
            if(guid==null){report.append("== $walkName: not found\n");continue}
            val line=DocTracks.parseLines(get(DocTracks.lineUrl(guid))!!)
            val html=get("https://www.doc.govt.nz/link/${guid.replace("-","")}.aspx").orEmpty()
            val o=DocPages.overview(html);val legs=DocPages.legs(DocPages.parse(html))
            val route=WalkRoutes.route(line,legs){n,c->places.locate(n,c,40.0)?.let{Coordinate(it.longitude,it.latitude)}}
            val main=WalkRoutes.walkLine(route,line,o.distance);val (ext,byLegs)=WalkRoutes.extent(route,line,o.distance)
            report.append("== ${o.title} [${o.time} · ${o.difficulty} · ${o.distance}] DOC line ${"%.1f".format(line.sumOf{DocTracks.length(it)}/1000)} km; legs ${legs.size}${route?.let{" (route %.1f km)".format(it.meters/1000)}?:""}; walk ${"%.1f".format(ext.sumOf{DocTracks.length(it)}/1000)} km ${if(byLegs)"(DOC's legs)" else if(ext.size>1)"(DOC's whole line)" else "(DOC's line, end to end)"}; summary line ${"%.1f".format(DocTracks.length(main)/1000)} km; intro ${o.intro.isNotBlank()}; photo ${o.image.isNotBlank()}\n")
            // DOC walk data for the data tiles the walk crosses, with names for unlisted walks from their pages.
            val dataTiles=LinkedHashSet<Triple<Int,Int,Int>>();val z14=LinkedHashSet<Triple<Int,Int,Int>>()
            for(p in line)for(i in 1 until p.size){val a=p[i-1];val b=p[i];val steps=max(1,(DocTracks.meters(a,b)/300).toInt())
                for(k in 0..steps){val t=k.toDouble()/steps;val lon=a.longitude+(b.longitude-a.longitude)*t;val lat=a.latitude+(b.latitude-a.latitude)*t
                    val x=(lon+180)/360;val y=CameraMath.mercatorY(lat)
                    dataTiles.add(Triple(10,floor(x*1024).toInt(),floor(y*1024).toInt()));z14.add(Triple(14,floor(x*16384).toInt(),floor(y*16384).toInt()))}}
            val indexes=HashMap<Triple<Int,Int,Int>,DocWalks.Index>();val infos=HashMap(list)
            for(d in dataTiles){val walkLines=DocTracks.parseWalkLines(get(DocWalks.linesUrl(d.first,d.second,d.third))!!)
                for(w in walkLines)if(w.guid !in infos){val ov=DocPages.overview(get("https://www.doc.govt.nz/link/${w.guid.replace("-","")}.aspx").orEmpty());if(ov.title.isNotBlank())infos[w.guid]=DocWalks.Info(w.guid,ov.title,ov.difficulty,ov.time,"")}
                // As MapLayers.markIndex: large walks by the walk itself.
                val lines=walkLines.flatMap{w->
                    if(infos[w.guid]?.large!=true)return@flatMap DocWalks.lines(listOf(w))
                    val full=mains.getOrPut(w.guid){
                        val l=DocTracks.parseLines(get(DocTracks.lineUrl(w.guid)).orEmpty())
                        val h=get("https://www.doc.govt.nz/link/${w.guid.replace("-","")}.aspx").orEmpty()
                        val r=WalkRoutes.route(l,DocPages.legs(DocPages.parse(h))){n,c->places.locate(n,c,40.0)?.let{Coordinate(it.longitude,it.latitude)}}
                        WalkRoutes.extent(r,l,DocPages.overview(h).distance).first}
                    DocWalks.lines(listOf(w),true)+(if(full.isEmpty())emptyList()else DocWalks.lines(listOf(DocTracks.WalkLine(w.guid,w.meters,full))))}
                indexes[d]=DocWalks.Index(lines)}
            // The map's tracks along the walk, as the app marks them.
            val walkIndex=DocWalks.Index(DocWalks.lines(listOf(DocTracks.WalkLine(guid,0.0,line))))
            val rows=HashMap<String,DoubleArray>();val decisions=HashMap<String,MutableMap<String,Double>>()
            for((z,x,y) in z14){var b=bytes("https://basemaps.linz.govt.nz/v1/tiles/topographic-v2/3857/$z/$x/$y.pbf?api=$key")?:continue
                if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
                val id=GpuUploadQueue.Id(z,x,y);val index=indexes[Triple(10,x shr 4,y shr 4)]
                for(f in DocWalks.mark(VectorTile.decode(b,z,x,y,setOf("streets")),index,infos)){
                    if(f.properties["kind"]!="track")continue
                    val path=f.paths.maxByOrNull{it.size}?:continue;val part=BoundaryIndex.clip(path,id.bounds).maxByOrNull{it.size}?:continue
                    if(part.size<2)continue
                    val r=DocWalks.worldMeters(50.0,part[0].y);val h=HashSet<Int>()
                    if(part.count{p->h.clear();walkIndex.near(p.x,p.y,r,h);h.isNotEmpty()}<part.size*.7)continue
                    var len=0.0;for(i in 1 until part.size)len+=DocTracks.meters(Coordinate(part[i-1].x*360-180,CameraMath.latitude(part[i-1].y)),Coordinate(part[i].x*360-180,CameraMath.latitude(part[i].y)))
                    val own=(f.properties["name"] as? String)?:"(unnamed)"
                    val walk=f.properties[DocWalks.WALK] as? String;val link=(f.properties[DocWalks.WALK_ID] as? String)?.let{infos[it]?.name}
                    val decision=if(walk!=null)"named '$walk' below z15" else if(link!=null)"own name, opens '$link'" else "own name, no DOC walk"
                    rows.getOrPut(own){DoubleArray(1)}[0]+=len
                    decisions.getOrPut(own){HashMap()}.merge(decision,len,Double::plus)
                }}
            for((own,d) in decisions.entries.sortedByDescending{rows[it.key]!![0]})
                report.append("   ${own} (${(rows[own]!![0]/1000).let{"%.1f".format(it)}} km on DOC's line): "+d.entries.sortedByDescending{it.value}.joinToString("; "){"${it.key} ${(it.value/1000).let{v->"%.1f".format(v)}} km"}+"\n")
        }
        out.writeText(report.toString())
    }
}
