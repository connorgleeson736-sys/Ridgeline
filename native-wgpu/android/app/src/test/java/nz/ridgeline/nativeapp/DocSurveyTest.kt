package nz.ridgeline.nativeapp

import org.junit.Test
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/** Survey (DOC_SURVEY=output file, network): every large DOC walk and a
 * sample of others, live from DOC. For each: page sections, legs, and the
 * route followed over DOC's line against DOC's own leg distances. */
class DocSurveyTest {
    private fun get(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("User-Agent","Ridgeline-Native-Survey");inputStream.use{String(it.readBytes(),Charsets.UTF_8)}}}.getOrNull()
    @Test fun survey(){
        val out=File(System.getenv("DOC_SURVEY")?:return)
        val infos=DocWalks.parseList(get(DocWalks.listUrl())!!).values.toList()
        val sample=infos.filter{it.large}+infos.filter{!it.large}.shuffled(java.util.Random(7)).take(60)
        val places=File("src/main/assets/search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}
        val pool=Executors.newFixedThreadPool(8)
        val rows=sample.map{info->pool.submit<String>{
            val html=get(info.page)
            if(html==null)return@submit "${info.name}\tlarge=${info.large}\tPAGE-FAIL"
            val sections=DocPages.parse(html);val legs=DocPages.legs(sections)
            val intro=DocPages.intro(html).isNotBlank()
            val line=get(DocTracks.lineUrl(info.guid))?.let{DocTracks.parseLines(it)}.orEmpty()
            val docKm=DocTracks.length(line.flatten())/1000
            val route=if(legs.isEmpty()||line.isEmpty())null else runCatching{WalkRoutes.route(line,legs){n,c->places.locate(n,c,40.0)?.let{Coordinate(it.longitude,it.latitude)}}}.getOrNull()
            val legKm=WalkRoutes.walked(legs).mapNotNull{it.km}.sum()
            val routeKm=route?.meters?.div(1000)
            val legCheck=route?.sections?.filter{it.km!=null}?.joinToString(";"){s->"${"%.1f".format((s.to-s.from)/1000)}/${s.km}"}.orEmpty()
            val o=DocPages.overview(html);val (ext,byLegs)=WalkRoutes.extent(route,line,o.distance)
            val extKm=ext.sumOf{DocTracks.length(it)}/1000;val stated=WalkRoutes.stated(o.distance)?.div(1000)
            listOf(info.name,"large=${info.large}","time=${info.time}","sections=${sections.map{it.title}}","intro=$intro","legs=${legs.size}","walked=${WalkRoutes.walked(legs).size}",
                "lineKm=${"%.1f".format(docKm)}","legKm=${"%.1f".format(legKm)}","routeKm=${routeKm?.let{"%.1f".format(it)}}","routeSections=${route?.sections?.size}","legSpans=$legCheck","stated=${stated?.let{"%.1f".format(it)}}","extentKm=${"%.1f".format(extKm)}","byLegs=$byLegs","distanceText=${o.distance}","why=${if(route==null)WalkRoutes.lastFailure else ""}","rawEntities=${sections.flatMap{it.blocks}.count{Regex("&[A-Za-z][A-Za-z0-9]*;|&#[0-9]+;").containsMatchIn(it.text)}}","wholeKm=${"%.1f".format(line.sumOf{DocTracks.length(it)}/1000)}","mainKm=${"%.1f".format(DocTracks.length(WalkRoutes.mainLine(line))/1000)}").joinToString("\t")
        }}.map{it.get()}
        pool.shutdown()
        out.writeText(rows.joinToString("\n"))
    }
}
