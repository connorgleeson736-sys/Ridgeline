package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
import java.net.URL
/** Survey (HUTS_SURVEY=output, HUTS_PAGES=DOC pages, HUTS_CACHE=DOC lines and hut
 * answers; network): per walk, the DOC huts near its line and those its page
 * does not name (left out by DocTracks.named). */
class HutsSurveyTest{
    private fun bytes(url:String)=runCatching{(URL(url).openConnection() as java.net.HttpURLConnection).run{connectTimeout=20000;readTimeout=30000;setRequestProperty("Accept-Encoding","identity");inputStream.use{it.readBytes()}}}.getOrNull()
    private fun cached(cache:File,name:String,url:String):ByteArray?{val f=File(cache,name);if(f.exists())return f.readBytes();val b=bytes(url)?:return null
        val t=File(cache,"$name.part${Thread.currentThread().id}");t.writeBytes(b);t.renameTo(f);return b}
    @Test fun survey(){
        val out=File(System.getenv("HUTS_SURVEY")?:return);val pages=File(System.getenv("HUTS_PAGES")!!);val cache=File(System.getenv("HUTS_CACHE")!!)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(10)
        val rows=pages.listFiles{x->x.name.endsWith(".html")}!!.sortedBy{it.name}.map{f->pool.submit<String?>{
            val html=f.readText();val o=DocPages.overview(html);val g=f.name.removeSuffix(".html")
            val guid="${g.substring(0,8)}-${g.substring(8,12)}-${g.substring(12,16)}-${g.substring(16,20)}-${g.substring(20)}"
            val line=DocTracks.parseLines(String(cached(cache,"$g.json",DocTracks.lineUrl(guid))?:return@submit null,Charsets.UTF_8))
            val main=WalkRoutes.walkLine(null,line,o.distance);val b=DocTracks.bounds(main)?:return@submit null
            val huts=DocTracks.parseHuts(String(cached(cache,"huts-$g.json",DocTracks.hutsUrl(b))?:return@submit null,Charsets.UTF_8))
            val stops=DocTracks.stops(huts,main);if(stops.isEmpty())return@submit null
            val text=DocPages.parse(html).joinToString(" "){s->DocPages.words(s.blocks).joinToString(" "){it.text}}
            val kept=DocTracks.named(stops,text);val dropped=stops-kept.toSet()
            if(dropped.isEmpty())null else "${o.title}: kept ${kept.map{it.hut.name}}; left out ${dropped.map{it.hut.name}}"}}.mapNotNull{it.get()}
        pool.shutdown()
        out.writeText("${rows.size} walks with huts left out\n"+rows.joinToString("\n"))
    }
}
