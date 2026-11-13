package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
import java.net.URL
/** Debug (WALK_DEBUG=guid|guid, network): DOC's line pieces, legs, route and extents for a walk. */
class WalkDebugTest{
    private fun get(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;inputStream.use{String(it.readBytes(),Charsets.UTF_8)}}}.getOrNull()
    @Test fun dbg(){
        val guids=System.getenv("WALK_DEBUG")?.split("|")?:return
        val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        for(g in guids){
            val line=DocTracks.parseLines(get(DocTracks.lineUrl(g))!!)
            val h=get("https://www.doc.govt.nz/link/${g.replace("-","")}.aspx")!!;val o=DocPages.overview(h);val legs=DocPages.legs(DocPages.parse(h))
            println("W ==== ${o.title} [${o.distance}] pieces ${line.map{"${DocTracks.length(it).toInt()} m ${"%.3f,%.3f".format(it.first().longitude,it.first().latitude)}->${"%.3f,%.3f".format(it.last().longitude,it.last().latitude)}"}}")
            println("W  legs: "+legs.joinToString(" | "){"${it.title} (${it.time}; ${it.km})"})
            println("W  walked: "+WalkRoutes.walked(legs).joinToString(" | "){it.title})
            val c=line.flatten().let{l->Coordinate(l.map{it.longitude}.average(),l.map{it.latitude}.average())}
            for(l in WalkRoutes.walked(legs))for(st in l.stops)println("W   '${WalkRoutes.plain(st)}' -> ${places.locate(WalkRoutes.plain(st),c,40.0)?.let{p->"${p.kind} ${p.name} %.2f km off line".format(line.flatten().minOf{DocTracks.meters(it,Coordinate(p.longitude,p.latitude))}/1000)}}")
            val r=WalkRoutes.route(line,legs){n,cc->places.locate(n,cc,40.0)?.let{Coordinate(it.longitude,it.latitude)}}
            println("W  route ${r?.meters?.toInt()} ${if(r==null)WalkRoutes.lastFailure else ""}; main ${DocTracks.length(WalkRoutes.mainLine(line)).toInt()} m; whole ${line.sumOf{DocTracks.length(it)}.toInt()} m")
        }
    }
}
