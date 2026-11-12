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
    private val textBlock=Regex("""<div class="block textblock[^"]*">(.*?)</div>""",RegexOption.DOT_MATCHES_ALL)
    private val element=Regex("""<(h[2-6]|p|li)\b[^>]*>(.*?)</\1>""",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))
    private val tag=Regex("<[^>]+>")
    private val space=Regex("[ \\t\\r\\f\\u00a0]+")

    fun parse(html:String):List<Section>{
        val out=ArrayList<Section>()
        for(m in item.findAll(html)){
            val title=text(m.groupValues[1]);val blocks=ArrayList<Block>()
            for(b in textBlock.findAll(m.groupValues[2]))for(e in element.findAll(b.groupValues[1])){
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
