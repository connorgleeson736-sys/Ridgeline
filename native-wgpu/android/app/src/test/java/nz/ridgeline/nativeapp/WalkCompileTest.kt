package nz.ridgeline.nativeapp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Offline tool (WALKS_DATA, WALKS_PACKETS, WALKS_READINGS, WALKS_TILES,
 * WALKS_ASSET=output walks.json.gz, WALKS_REPORT=output report JSON;
 * WALKS_ONLY=guids without dashes, | separated, optional): compiles every
 * walk that has a valid reading (WalkCompiler) into the app's walk records. */
class WalkCompileTest{
    @Test fun compile(){
        val data=File(System.getenv("WALKS_DATA")?:return);val packets=File(System.getenv("WALKS_PACKETS")!!);val readings=File(System.getenv("WALKS_READINGS")!!)
        val tiles=File(System.getenv("WALKS_TILES")!!);val only=System.getenv("WALKS_ONLY").orEmpty().split("|").filter{it.isNotBlank()}.toSet()
        val gazetteer=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val walks=JSONArray(File(data,"walks.json").readText()).let{a->(0 until a.length()).associate{a.getJSONObject(it).let{w->w.getString("guid").replace("-","") to w}}}
        val tool=WalkPacketsTest()
        val hutTable=JSONArray(File(data,"huts.json").readText()).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
            .mapNotNull{h->h.optJSONArray("point")?.let{p->WalkRecords.Hut(h.getString("name"),Coordinate(p.getDouble(0),p.getDouble(1)),h.getString("staticLink"))}}
        fun line(g:String)=File(data,"lines/$g.json").takeIf{it.exists()}?.let{DocTracks.parseLines(it.readText())}.orEmpty()
        val list=readings.listFiles{f->f.name.endsWith(".json")&&!f.name.startsWith("_")}!!.map{it.name.removeSuffix(".json")}.filter{only.isEmpty()||it in only}.sorted()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(6)
        val results=list.map{g->pool.submit<Pair<String,WalkCompiler.Result?>>{
            val packet=JSONObject(File(packets,"$g.json").readText());val reading=JSONObject(File(readings,"$g.json").readText())
            val doc=line(g)
            val extra=ArrayList<Coordinate>()
            for(k in listOf("links","names"))packet.getJSONArray(k).let{a->for(i in 0 until a.length())a.getJSONObject(i).optJSONArray("point")?.let{extra.add(Coordinate(it.getDouble(0),it.getDouble(1)))}}
            val base=doc.flatten().ifEmpty{walks[g]?.optJSONArray("point")?.let{listOf(Coordinate(it.getDouble(0),it.getDouble(1)))}.orEmpty()}
            if(base.isEmpty())return@submit g to null
            val near=extra.filter{e->base.any{DocTracks.meters(it,e)<8000}}
            val trackList=ArrayList<Pair<String,List<Coordinate>>>();val lifts=ArrayList<List<Coordinate>>();val peaks=ArrayList<Pair<String,Coordinate>>();val roads=ArrayList<Pair<String,List<Coordinate>>>()
            for((x,y) in tool.corridor(base,3000.0)+tool.corridor(near,1500.0)){
                val f=File(tiles,"t14-$x-$y.pbf");if(!f.exists())continue
                var b=f.readBytes();if(b.size>2&&b[0]==0x1f.toByte()&&b[1]==0x8b.toByte())b=java.util.zip.GZIPInputStream(b.inputStream()).readBytes()
                WalkOptions.read(GpuUploadQueue.Id(14,x,y),VectorTile.decode(b,14,x,y,WalkOptions.LAYERS),trackList,lifts,peaks,roads)}
            g to runCatching{WalkCompiler.compile(WalkCompiler.Input(packet,reading,doc,WalkOptions.Area(trackList,lifts,peaks,null,roads),gazetteer,{guid->line(guid.replace("-",""))},hutTable))}
                .getOrElse{WalkCompiler.Result(false,listOf("compiler error: $it"),emptyList(),emptyList(),emptyList(),"")}
        }}.map{it.get()}
        pool.shutdown()
        val records=JSONObject();val report=JSONArray()
        for((g,res) in results){if(res==null)continue
            val packet=JSONObject(File(packets,"$g.json").readText());val guid=packet.getString("guid")
            val rec=JSONObject().put("status",if(res.verified)"verified" else "flagged").put("kind",res.kind).put("flags",JSONArray(res.flags))
                .put("ways",JSONArray().apply{for(w in res.ways)put(JSONObject().put("label",w.label).put("from",w.from).put("to",w.to).put("shape",w.shape).put("main",w.main)
                    .put("time",w.time).put("distance",w.distance).put("line",WalkRecords.encode(DocTracks.simplify(w.line,4000)))
                    .put("stops",JSONArray().apply{for((n,at) in w.stops)put(JSONObject().put("name",n).put("at",Math.round(at)))}))})
                .put("huts",JSONArray().apply{for(h in res.huts)put(JSONObject().put("name",h.name).put("lon",h.point.longitude).put("lat",h.point.latitude).put("link",h.link))})
            records.put(guid,rec)
            report.put(JSONObject().put("guid",guid).put("name",packet.optString("name")).put("status",rec.getString("status")).put("flags",JSONArray(res.flags)).put("notes",JSONArray(res.notes))
                .put("ways",JSONArray().apply{for(w in res.ways)put(JSONObject().put("label",w.label).put("shape",w.shape).put("main",w.main).put("km",DocTracks.length(w.line)/1000))})
                .put("huts",JSONArray(res.huts.map{it.name})).put("docLineKm",packet.optDouble("docLineKm"))
                .put("attempted",JSONArray().apply{for(w in res.attempted)put(JSONObject().put("label",w.label).put("main",w.main).put("km",DocTracks.length(w.line)/1000).put("line",WalkRecords.encode(DocTracks.simplify(w.line,1500))))}))
        }
        val root=JSONObject().put("v",1).put("walks",records)
        System.getenv("WALKS_ASSET")?.let{p->File(p).parentFile?.mkdirs();java.util.zip.GZIPOutputStream(File(p).outputStream()).use{it.write(root.toString().toByteArray())}}
        File(System.getenv("WALKS_REPORT")!!).writeText(report.toString(1))
    }
}
