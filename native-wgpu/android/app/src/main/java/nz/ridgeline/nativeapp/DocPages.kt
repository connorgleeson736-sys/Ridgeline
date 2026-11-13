package nz.ridgeline.nativeapp

/** The text of a DOC track page (doc.govt.nz, CC BY 4.0): its sections
 * ("Description", "Getting there", "Know before you go") as headings,
 * paragraphs and bullets. Only DOC's text blocks are read; photos, videos
 * and hut tiles are left out (some gallery photos are "DOC use only").
 * Pure Kotlin, so it is unit tested on real pages. */
internal object DocPages {
    const val SITE="https://www.doc.govt.nz/"
    fun isUrl(url:String)=url.startsWith(SITE)

    sealed class Block{abstract val text:String}
    data class Heading(override val text:String):Block()
    data class Paragraph(override val text:String):Block()
    data class Bullet(override val text:String):Block()
    data class Section(val title:String,val blocks:List<Block>)

    private val item=Regex("""<doc-accordion-item\b[^>]*\bheader-text="([^"]*)"[^>]*>(.*?)</doc-accordion-item>""",RegexOption.DOT_MATCHES_ALL)
    private val blockStart=Regex("""<div class="block\s+""")
    private val element=Regex("""<(h[2-6]|p|li)\b[^>]*>(.*?)</\1>""",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))
    private val tag=Regex("<[^>]+>")
    private val space=Regex("[ \\t\\r\\f\\u00a0]+")

    private val introduction=Regex("""<doc-introduction-text\b[^>]*>(.*?)</doc-introduction-text>""",RegexOption.DOT_MATCHES_ALL)
    /** The page's lead paragraph (huts have no other introduction in DOC's data). */
    fun intro(html:String)=introduction.find(html)?.let{text(it.groupValues[1])}.orEmpty()
    /** One leg of a walk as DOC describes it ("Young Hut to Siberia Hut",
     * "Time: 6 to 8 hr", "Distance: 12 km") with its own text. [stops]: its
     * places in order ("Lewis Pass - Cannibal Gorge Hut - Ada Pass Hut"). */
    data class Leg(val from:String,val to:String,val title:String,val time:String,val km:Double?,val blocks:List<Block>,val stops:List<String> = listOf(from,to))
    private val legTo=Regex("\\s+to\\s+",RegexOption.IGNORE_CASE)
    private val legDash=Regex("\\s+[-–—]\\s+")
    private val legTime=Regex("Time:\\s*([^\\n]+?)\\s*(?=Distance:|\\n|$)",RegexOption.IGNORE_CASE)
    private val legDistance=Regex("Distance:\\s*([^\\n]+)",RegexOption.IGNORE_CASE)
    private val km=Regex("([0-9]+(?:[.,][0-9]+)?)\\s*km",RegexOption.IGNORE_CASE)
    private val notLeg=Regex("^(side trip|campsites?|walking options|places to stay|understand|highlights|what to expect|description|getting there|from )",RegexOption.IGNORE_CASE)
    /** The legs in DOC's description section ("Description", "Track
     * description"), in the order DOC describes (and recommends walking)
     * them: headings "A to B" or "A - B - C" followed by a time or distance.
     * Side trips and other headings are not legs. */
    fun legs(sections:List<Section>):List<Leg>{
        val d=sections.firstOrNull{it.title.contains("description",true)}?:return emptyList()
        val out=ArrayList<Leg>();var i=0;val b=d.blocks
        while(i<b.size){
            val h=b[i];i++
            if(h !is Heading||notLeg.containsMatchIn(h.text))continue
            val stops=(if(legTo.containsMatchIn(h.text))h.text.split(legTo)else h.text.split(legDash)).map{it.trim()}.filter{it.isNotEmpty()}
            if(stops.size<2)continue
            val body=ArrayList<Block>();while(i<b.size&&b[i] !is Heading){body.add(b[i]);i++}
            val facts=body.take(3).joinToString("\n"){it.text}
            val time=legTime.find(facts)?.groupValues?.get(1)?.trim().orEmpty()
            // "Distance: 7 km to Cannibal Gorge Hut, 10 km to Ada Pass Hut": the last is to the leg's end.
            val distance=legDistance.find(facts)?.groupValues?.get(1)?.let{km.findAll(it).lastOrNull()}?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()
            if(time.isEmpty()&&distance==null)continue
            out.add(Leg(stops.first(),stops.last(),h.text,time,distance,body.filterNot{(it.text.startsWith("Time:",true)||it.text.startsWith("Distance:",true))&&it.text.length<160},stops))
        }
        return out
    }
    fun parse(html:String):List<Section>{
        val out=ArrayList<Section>()
        for(m in item.findAll(html)){
            val title=text(m.groupValues[1]);val blocks=ArrayList<Block>()
            // DOC's text blocks; a section written without them (huts' "About
            // this hut") is read whole: only headings, paragraphs and list items.
            // Each text block runs to the next DOC block: blocks can hold nested
            // boxes (a warning <div> inside the Gillespie Pass description).
            val body=m.groupValues[2]
            val texts=body.split(blockStart).filter{it.startsWith("textblock")}.ifEmpty{listOf(body)}
            for(b in texts)for(e in element.findAll(b)){
                val value=text(e.groupValues[2]).takeIf{it.isNotBlank()}?:continue
                blocks.add(when(e.groupValues[1].lowercase()){"p"->Paragraph(value);"li"->Bullet(value);else->Heading(value)})
            }
            if(title.isNotBlank()&&blocks.isNotEmpty())out.add(Section(title,blocks))
        }
        return out
    }
    /** Inner HTML to plain text: line breaks kept, tags dropped, entities decoded. */
    fun text(html:String):String{
        val broken=html.replace(Regex("<br\\s*/?>",RegexOption.IGNORE_CASE),"\n")
        return decode(tag.replace(broken,"")).lines().joinToString("\n"){space.replace(it," ").trim()}.trim()
    }
    private val named=mapOf("amp" to "&","lt" to "<","gt" to ">","quot" to "\"","apos" to "'","nbsp" to "\u00a0","ndash" to "–","mdash" to "—",
        "rsquo" to "’","lsquo" to "‘","rdquo" to "”","ldquo" to "“","hellip" to "…","deg" to "°","times" to "×","frac12" to "½","copy" to "©",
        "amacr" to "ā","emacr" to "ē","imacr" to "ī","omacr" to "ō","umacr" to "ū","Amacr" to "Ā","Emacr" to "Ē","Imacr" to "Ī","Omacr" to "Ō","Umacr" to "Ū",
        "eacute" to "é","egrave" to "è","aacute" to "á","oacute" to "ó","uuml" to "ü","ouml" to "ö","auml" to "ä","rarr" to "→","larr" to "←","middot" to "·")
    private val entity=Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    fun decode(s:String)=entity.replace(s){m->val v=m.groupValues[1]
        when{
            v.startsWith("#x")||v.startsWith("#X")->v.substring(2).toIntOrNull(16)?.takeIf{Character.isValidCodePoint(it)}?.let{String(Character.toChars(it))}
            v.startsWith("#")->v.substring(1).toIntOrNull()?.takeIf{Character.isValidCodePoint(it)}?.let{String(Character.toChars(it))}
            else->named[v]
        }?:m.value}
}
