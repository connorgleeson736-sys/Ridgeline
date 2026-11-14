package nz.ridgeline.nativeapp
import org.junit.Test
import java.io.File
/** Survey (STARTS_PAGES=folder of DOC pages, STARTS_SURVEY=output): the starts
 * DOC's descriptions name (DocPages.starts), and sentences about starting that gave none. */
class StartsSurveyTest{
    @Test fun survey(){
        val dir=File(System.getenv("STARTS_PAGES")?:return);val out=StringBuilder();val missed=StringBuilder()
        for(f in dir.listFiles{x->x.name.endsWith(".html")}!!.sortedBy{it.name}){
            val html=f.readText();val o=DocPages.overview(html);val sections=DocPages.parse(html)
            val s=DocPages.starts(sections)
            if(s.isNotEmpty())out.append("${o.title}: ${s.map{it.place+(it.track?.let{t->" (on $t)"}?:"")}}\n")
            else sections.firstOrNull{it.title.contains("description",true)}?.blocks?.filterIsInstance<DocPages.Paragraph>()?.flatMap{it.text.split(Regex("(?<=[.!?])\\s+"))}
                ?.filter{Regex("\\b(start|starts|starting|begin|begins|beginning|started)\\b",RegexOption.IGNORE_CASE).containsMatchIn(it)&&Regex("\\bor\\b").containsMatchIn(it)}
                ?.forEach{missed.append("${o.title} || $it\n")}
        }
        File(System.getenv("STARTS_SURVEY")!!).writeText(out.toString()+"\n--- missed\n"+missed)
    }
}
