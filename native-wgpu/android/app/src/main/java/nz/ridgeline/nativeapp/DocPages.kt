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
    /** A DOC page's own summary: name, introduction, photo, distance ("53.5 km
     * one way"), time ("4 days"), difficulty and dog access, as DOC shows them
     * above the page. Every DOC walk has a page (the Milford Track is not in
     * DOC's walk list, but its page has all of this). */
    data class Overview(val title:String,val intro:String,val image:String,val distance:String,val time:String,val difficulty:String,val dogs:String)
    private fun meta(html:String,property:String)=Regex("""<meta\s+property="$property"\s+content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty()
    private fun category(html:String,name:String)=Regex("""category-name="$name"[\s\S]*?<template #description>([\s\S]*?)</template>""").find(html)?.groupValues?.get(1)
    fun overview(html:String):Overview{
        val difficulty=category(html,"Difficulty")
        val time=difficulty?.let{Regex("""<span>([\s\S]*?)</span>""").find(it)?.groupValues?.get(1)}?.let(::text).orEmpty()
        val level=difficulty?.let{Regex("""<a\b[^>]*>([\s\S]*?)</a>""").find(it)?.groupValues?.get(1)}?.let(::text)?.lines()?.firstOrNull{it.isNotBlank()}?.trim().orEmpty()
        return Overview(meta(html,"og:title"),intro(html).ifBlank{meta(html,"og:description")},meta(html,"og:image"),
            category(html,"DistanceType")?.let(::text).orEmpty(),time,level,category(html,"DogsAllowed")?.let(::text)?.substringBefore(" Other pets")?.trim().orEmpty())
    }
    /** One leg of a walk as DOC describes it ("Young Hut to Siberia Hut",
     * "Time: 6 to 8 hr", "Distance: 12 km") with its own text. [stops]: its
     * places in order ("Lewis Pass - Cannibal Gorge Hut - Ada Pass Hut"). */
    data class Leg(val from:String,val to:String,val title:String,val time:String,val km:Double?,val blocks:List<Block>,val stops:List<String> = listOf(from,to))
    private val legTo=Regex("\\s+to\\s+",RegexOption.IGNORE_CASE)
    private val legDash=Regex("\\s+[-–—]\\s+")
    private val dayPrefix=Regex("^(day|stage|section|part|leg)\\s*[0-9]+\\s*[:–—-]?\\s*",RegexOption.IGNORE_CASE)
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
            // "Day 1 – Glade Wharf to Clinton Hut" (Milford Track): the day is not a place.
            val heading=h.text.replace(dayPrefix,"")
            val stops=(if(legTo.containsMatchIn(heading))heading.split(legTo)else heading.split(legDash)).map{it.trim()}.filter{it.isNotEmpty()}
            if(stops.size<2)continue
            val body=ArrayList<Block>();while(i<b.size&&b[i] !is Heading){body.add(b[i]);i++}
            val facts=body.take(3).joinToString("\n"){it.text}
            val time=legTime.find(facts)?.groupValues?.get(1)?.trim().orEmpty()
            // "Distance: 7 km to Cannibal Gorge Hut, 10 km to Ada Pass Hut": the last is to the leg's end.
            // Or in the time line: "Time: 2 to 3 hr, 9 km" (Greenstone and Caples).
            val distance=(legDistance.find(facts)?.groupValues?.get(1)?:time).let{km.findAll(it).lastOrNull()}?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()
            if(time.isEmpty()&&distance==null)continue
            out.add(Leg(stops.first(),stops.last(),heading,time,distance,body.filterNot{(it.text.startsWith("Time:",true)||it.text.startsWith("Distance:",true))&&it.text.length<160},stops))
        }
        return out
    }
    /** A place DOC says the walk can start from, and the track it starts on, if named. */
    data class Start(val place:String,val track:String?)
    private val startSentence=Regex("^(?:(?:you|visitors|walkers|trampers) can |it can |this (?:track|walk) can )?(?:start|starting|begin|beginning)\\b|\\bcan be (?:started|begun|accessed)\\b|\\b(?:start|starting|begin|beginning) (?:at|from|on|outside)\\b",RegexOption.IGNORE_CASE)
    private val startVerb=Regex("^.*?\\b(?:start(?:ed|ing)?|begin(?:ning|s)?|begun|accessed)\\b",RegexOption.IGNORE_CASE)
    private val onTrack=Regex("\\bon the ((?:[A-ZĀĒĪŌŪ][\\p{L}'’/-]*\\s+)+?(?:Track|Walk|Walkway|Trail|Route|Circuit))\\b")
    private val lead=Regex("^(?:(?:either|at|from|outside|in|near|by|the|this|track|walk|trip|route|trail)\\s+)+",RegexOption.IGNORE_CASE)
    private val tail=Regex("\\s*(?:,|\\(|\\s[-–—]\\s|\\b(?:to|along|in|on|at the|near|via|and|where|which|then|following|before|after|for)\\b).*$",RegexOption.IGNORE_CASE)
    /** Places DOC's description says the walk starts from, when it gives a
     * choice ("Start on the Kea Point Track either outside the … Visitor
     * Centre or at White Horse Hill car park"; "Starting at either Stirling
     * Point or Gunpit road"; "Start at the Punchbowl, Bealey Chasm or Temple
     * Basin car parks"). Empty unless two or more are named. */
    fun starts(sections:List<Section>):List<Start>{
        val d=sections.firstOrNull{it.title.contains("description",true)}?:return emptyList()
        for(b in d.blocks){if(b !is Paragraph)continue
            for(sentence in b.text.split(Regex("(?<=[.!?])\\s+"))){
                if(!startSentence.containsMatchIn(sentence)||!Regex("\\bor\\b").containsMatchIn(sentence))continue
                if(Regex("\\beither (?:end|direction|way)\\b|\\bboth ends\\b",RegexOption.IGNORE_CASE).containsMatchIn(sentence))continue
                val track=onTrack.find(sentence)?.groupValues?.get(1)?.trim()
                var rest=sentence.replace(startVerb,"").let{r->if(track!=null)r.replaceFirst(onTrack,"")else r}.trim().trimEnd('.')
                // "…, B or C, at the end of …": before the last "or" items are separated
                // by commas; after it, the item ends at the first comma.
                val i=Regex("\\bor\\b").findAll(rest).lastOrNull()?.range?.first?:continue
                val items=rest.substring(0,i).split(Regex(",\\s*|\\s+or\\s+"))+rest.substring(i+2)
                // "the trip to Mt McKerrow from Wainuiomata", "15 min from the Forest Hill
                // North Picnic Area": the place is what comes after the last "from".
                val places=items.map{it.trim().let{x->Regex("\\bfrom\\s+",RegexOption.IGNORE_CASE).findAll(x).lastOrNull()?.let{m->x.substring(m.range.last+1)}?:x}
                        .replace(lead,"").replace(tail,"").trim()}.filter{p->p.isNotEmpty()&&p.length<60&&(p[0].isUpperCase()||p[0].isDigit())}
                if(places.size>=2)return places.distinct().map{Start(it,track)}
            }
        }
        return emptyList()
    }
    private val anticlockwise=Regex("\\b(?:anti-?\\s?clockwise|counter-?\\s?clockwise)\\b",RegexOption.IGNORE_CASE)
    private val clockwiseWord=Regex("(?<![-\\w])clockwise\\b",RegexOption.IGNORE_CASE)
    /** The way DOC's description goes round a loop ("Taking the loop clockwise"):
     * true clockwise, false anticlockwise, null when it does not say (or names
     * both without saying which it describes). */
    fun clockwise(sections:List<Section>):Boolean?{
        val d=sections.firstOrNull{it.title.contains("description",true)}?:return null
        val sentences=d.blocks.flatMap{it.text.split(Regex("(?<=[.!?])\\s+"))}
        fun way(t:String):Boolean?{val anti=anticlockwise.containsMatchIn(t);val cw=clockwiseWord.containsMatchIn(anticlockwise.replace(t,""))
            return if(anti&&!cw)false else if(cw&&!anti)true else null}
        val all=sentences.joinToString(" ")
        way(all)?.let{return it}
        // Both named ("either clockwise or anticlockwise"): the one DOC describes or recommends.
        // (the words after "described", "recommended"…).
        val said=Regex("\\b(described|recommended|recommend|best|taking|take the)\\b",RegexOption.IGNORE_CASE)
        return sentences.firstNotNullOfOrNull{t->said.find(t)?.let{m->way(t.substring(m.range.first))}}
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
                when(e.groupValues[1].lowercase()){
                    // A paragraph of "<br>&bull; …" lines is a list (the Liverpool Track's hazards).
                    "p"->if(value.lines().any{it.trimStart().startsWith("•")})for(line in value.lines().filter{it.isNotBlank()}){
                            val t=line.trim();blocks.add(if(t.startsWith("•"))Bullet(t.removePrefix("•").trim())else Paragraph(t))}
                        else blocks.add(Paragraph(value))
                    "li"->blocks.add(Bullet(value))
                    else->blocks.add(Heading(value))
                }
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
    /** Every HTML 4 named character reference (252: "&bull;", "&eacute;", "&rarr;"…;
     * DOC writes dot points as "<br>&bull; …"), as name:hex code point; and
     * "&apos;" and the macron vowels ("&amacr;"…, HTML5). Generated from the HTML 4 entity table. */
    private const val NAMED="AElig:c6,Aacute:c1,Acirc:c2,Agrave:c0,Alpha:391,Aring:c5,Atilde:c3,Auml:c4,Beta:392,Ccedil:c7,Chi:3a7,Dagger:2021,Delta:394,ETH:d0,Eacute:c9,Ecirc:ca,Egrave:c8,Epsilon:395,Eta:397,Euml:cb,Gamma:393,Iacute:cd,Icirc:ce,Igrave:cc,Iota:399,Iuml:cf,Kappa:39a,Lambda:39b,Mu:39c,Ntilde:d1,Nu:39d,OElig:152,Oacute:d3,Ocirc:d4,Ograve:d2,Omega:3a9,Omicron:39f,Oslash:d8,Otilde:d5,Ouml:d6,Phi:3a6,Pi:3a0,Prime:2033,Psi:3a8,Rho:3a1,Scaron:160,Sigma:3a3,THORN:de,Tau:3a4,Theta:398,Uacute:da,Ucirc:db,Ugrave:d9,Upsilon:3a5,Uuml:dc,Xi:39e,Yacute:dd,Yuml:178,Zeta:396,aacute:e1,acirc:e2,acute:b4,aelig:e6,agrave:e0,alefsym:2135,alpha:3b1,amp:26,and:2227,ang:2220,aring:e5,asymp:2248,atilde:e3,auml:e4,bdquo:201e,beta:3b2,brvbar:a6,bull:2022,cap:2229,ccedil:e7,cedil:b8,cent:a2,chi:3c7,circ:2c6,clubs:2663,cong:2245,copy:a9,crarr:21b5,cup:222a,curren:a4,dArr:21d3,dagger:2020,darr:2193,deg:b0,delta:3b4,diams:2666,divide:f7,eacute:e9,ecirc:ea,egrave:e8,empty:2205,emsp:2003,ensp:2002,epsilon:3b5,equiv:2261,eta:3b7,eth:f0,euml:eb,euro:20ac,exist:2203,fnof:192,forall:2200,frac12:bd,frac14:bc,frac34:be,frasl:2044,gamma:3b3,ge:2265,gt:3e,hArr:21d4,harr:2194,hearts:2665,hellip:2026,iacute:ed,icirc:ee,iexcl:a1,igrave:ec,image:2111,infin:221e,int:222b,iota:3b9,iquest:bf,isin:2208,iuml:ef,kappa:3ba,lArr:21d0,lambda:3bb,lang:2329,laquo:ab,larr:2190,lceil:2308,ldquo:201c,le:2264,lfloor:230a,lowast:2217,loz:25ca,lrm:200e,lsaquo:2039,lsquo:2018,lt:3c,macr:af,mdash:2014,micro:b5,middot:b7,minus:2212,mu:3bc,nabla:2207,nbsp:a0,ndash:2013,ne:2260,ni:220b,not:ac,notin:2209,nsub:2284,ntilde:f1,nu:3bd,oacute:f3,ocirc:f4,oelig:153,ograve:f2,oline:203e,omega:3c9,omicron:3bf,oplus:2295,or:2228,ordf:aa,ordm:ba,oslash:f8,otilde:f5,otimes:2297,ouml:f6,para:b6,part:2202,permil:2030,perp:22a5,phi:3c6,pi:3c0,piv:3d6,plusmn:b1,pound:a3,prime:2032,prod:220f,prop:221d,psi:3c8,quot:22,rArr:21d2,radic:221a,rang:232a,raquo:bb,rarr:2192,rceil:2309,rdquo:201d,real:211c,reg:ae,rfloor:230b,rho:3c1,rlm:200f,rsaquo:203a,rsquo:2019,sbquo:201a,scaron:161,sdot:22c5,sect:a7,shy:ad,sigma:3c3,sigmaf:3c2,sim:223c,spades:2660,sub:2282,sube:2286,sum:2211,sup:2283,sup1:b9,sup2:b2,sup3:b3,supe:2287,szlig:df,tau:3c4,there4:2234,theta:3b8,thetasym:3d1,thinsp:2009,thorn:fe,tilde:2dc,times:d7,trade:2122,uArr:21d1,uacute:fa,uarr:2191,ucirc:fb,ugrave:f9,uml:a8,upsih:3d2,upsilon:3c5,uuml:fc,weierp:2118,xi:3be,yacute:fd,yen:a5,yuml:ff,zeta:3b6,zwj:200d,zwnj:200c,apos:27,amacr:101,emacr:113,imacr:12b,omacr:14d,umacr:16b,Amacr:100,Emacr:112,Imacr:12a,Omacr:14c,Umacr:16a"
    private val named:Map<String,String> by lazy{NAMED.split(',').associate{e->val (n,c)=e.split(':');n to String(Character.toChars(c.toInt(16)))}}
    private val entity=Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    fun decode(s:String)=entity.replace(s){m->val v=m.groupValues[1]
        when{
            v.startsWith("#x")||v.startsWith("#X")->v.substring(2).toIntOrNull(16)?.takeIf{Character.isValidCodePoint(it)}?.let{String(Character.toChars(it))}
            v.startsWith("#")->v.substring(1).toIntOrNull()?.takeIf{Character.isValidCodePoint(it)}?.let{String(Character.toChars(it))}
            else->named[v]
        }?:m.value}
}
