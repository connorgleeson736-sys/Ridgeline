package nz.ridgeline.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Te Araroa, the trail the length of the country, as the Te Araroa Trust publishes it
 * (CC BY 4.0, teararoa.org.nz), bundled in assets/te-araroa (the website's
 * data/te-araroa.json, from its tools/te-araroa.mjs): the trail in pieces
 * (southbound kilometres, land status, section), its sections and the options beside
 * them (bypasses, water and ferry legs), the huts, campsites and holiday parks on it,
 * the usual food stops (walkers' resupply guides) and the shops and cafés at them
 * (OpenStreetMap, ODbL). Pure Kotlin: unit tested (TeAraroaTest). */
internal class TeAraroa private constructor(
    val name:String,val total:Double,val pieces:List<Piece>,val sections:List<Section>,
    val places:List<Place>,val stops:List<Stop>,val shops:List<Shop>){
    class Piece(val index:Int,val name:String,val from:Double,val to:Double,val land:String,val section:String,val line:List<Coordinate>){
        /** West, south, east, north. */
        val box=doubleArrayOf(line.minOfOrNull{it.longitude}?:0.0,line.minOfOrNull{it.latitude}?:0.0,line.maxOfOrNull{it.longitude}?:0.0,line.maxOfOrNull{it.latitude}?:0.0)
    }
    class Section(val slug:String,val n:Int,val name:String,val region:String,val island:String,val length:Double,val time:String,
        val grade:Int,val from:Double,val to:Double,val alternative:Boolean,val parts:List<List<Coordinate>>)
    class Place(val name:String,val type:String,val category:String,val facilities:String,val booking:Boolean,val pass:Boolean,val page:String,
        val website:String,val phone:String,val address:String,val point:Coordinate?,val km:Double,val off:Double,val section:String,
        val approx:Boolean,val town:String,val fromNotes:Boolean){
        val hut get()=type.equals("Hut",true)
    }
    class Stop(val name:String,val point:Coordinate,val km:Double,val off:Double,val level:String,val note:String,val bounce:Boolean,val via:Boolean,val shops:List<String>,val kind:String){
        /** On the trail (within 3 km, or on its way, as Picton by ferry); further off it is a hitch. */
        val onTrail get()=off<=3000||via
    }
    class Shop(val name:String,val kind:String,val point:Coordinate,val stop:Int,val address:String,val hours:String,val phone:String,val website:String)
    class Carry(val from:Stop,val to:Stop,val km:Double,val days:Pair<Double,Double>,val hitch:List<Stop>)

    val bySlug=sections.associateBy{it.slug}
    val mains=sections.filter{!it.alternative}
    val options=sections.filter{it.alternative}

    /** A section as one line, southbound: the trail's pieces in order, or an
     * option's longest part (an option's parts are never joined). */
    fun line(s:Section):List<Coordinate>{
        if(s.alternative)return s.parts.maxByOrNull{it.size}.orEmpty()
        val out=ArrayList<Coordinate>()
        for(p in pieces)if(p.section==s.slug)for(q in p.line)if(out.lastOrNull()!=q)out.add(q)
        return out
    }
    /** Everything to draw for a section: its line, or all of an option's parts. */
    fun lines(s:Section)=if(s.alternative)s.parts.filter{it.size>=2}else listOf(line(s)).filter{it.size>=2}

    /** The point on the main trail at a (southbound) km, or null in a gap (a ferry). */
    fun pointAt(k:Double):Coordinate?{
        val p=pieces.firstOrNull{k>=it.from&&k<=it.to}?:return null
        if(p.line.isEmpty())return null;if(p.line.size<2)return p.line[0]
        val t=(k-p.from)/max(1e-9,p.to-p.from)*(p.line.size-1);val i=min(p.line.size-2,floor(t).toInt());val f=t-i
        return Coordinate(p.line[i].longitude+(p.line[i+1].longitude-p.line[i].longitude)*f,p.line[i].latitude+(p.line[i+1].latitude-p.line[i].latitude)*f)
    }
    /** The section and southbound km of the main trail nearest a point within [reach] metres. */
    fun nearest(p:Coordinate,reach:Double):Pair<Piece,Double>?{
        val tol=reach/111_320.0;val k=cos(Math.toRadians(p.latitude))
        var best:Piece?=null;var bd=tol;var at=0.0
        for(piece in pieces){val b=piece.box
            if(p.longitude<b[0]-tol/k||p.longitude>b[2]+tol/k||p.latitude<b[1]-tol||p.latitude>b[3]+tol)continue
            val l=piece.line;var run=0.0;var total=0.0
            for(i in 1 until l.size)total+=hypot((l[i].longitude-l[i-1].longitude)*k,l[i].latitude-l[i-1].latitude)
            for(i in 1 until l.size){val a=l[i-1];val c=l[i];val dx=(c.longitude-a.longitude)*k;val dy=c.latitude-a.latitude;val len=dx*dx+dy*dy
                val t=if(len>0)((((p.longitude-a.longitude)*k)*dx+(p.latitude-a.latitude)*dy)/len).coerceIn(0.0,1.0)else 0.0
                val d=hypot((a.longitude-p.longitude)*k+dx*t,a.latitude-p.latitude+dy*t)
                if(d<bd){bd=d;best=piece;at=piece.from+(if(total>0)(piece.to-piece.from)*(run+sqrt(len)*t)/total else 0.0)}
                run+=sqrt(len)}
        }
        return best?.let{it to at}
    }
    /** Te Araroa sections a line (a walk or a track) runs along: those holding at
     * least a few of its points within 60 m of the trail, in trail order. */
    fun sectionsAlong(line:List<Coordinate>):List<Section>{
        if(line.size<2)return emptyList()
        val step=max(1,line.size/40);val pts=line.filterIndexed{i,_->i%step==0}
        val count=HashMap<String,Int>()
        for(p in pts)nearest(p,60.0)?.first?.section?.takeIf{it.isNotEmpty()}?.let{count[it]=(count[it]?:0)+1}
        val need=max(2,ceil(pts.size*.15).toInt())
        return count.filterValues{it>=need}.keys.mapNotNull{bySlug[it]}.sortedBy{it.from}
    }

    // ---- Direction: southbound (Cape Reinga to Bluff) or northbound ----
    /** A southbound km as the walker counts it. */
    fun kmIn(v:Double,nobo:Boolean)=if(nobo)max(0.0,total-v)else v
    fun <T> walking(list:List<T>,nobo:Boolean)=if(nobo)list.reversed()else list
    /** The sections before and after a main section along the trail, in walking order. */
    fun neighbours(s:Section,nobo:Boolean):Pair<Section?,Section?>{
        if(s.alternative)return null to null
        val order=walking(mains.sortedBy{it.from},nobo);val i=order.indexOf(s)
        return if(i<0)null to null else order.getOrNull(i-1) to order.getOrNull(i+1)
    }

    // ---- Food ----
    /** Rough days to walk the trail from km a to km b: the Trust's section times for the
     * part of each section in between (20 to 28 km a day where a section has none). */
    fun days(a:Double,b:Double):Pair<Double,Double>{
        var lo=0.0;var hi=0.0
        for(s in mains){val part=max(0.0,min(b,s.to)-max(a,s.from));if(part<=0||s.to<=s.from)continue
            val t=daysOf(s.time);val f=part/(s.to-s.from)
            if(t!=null){lo+=t.first*f;hi+=t.second*f}else{lo+=part/28;hi+=part/20}}
        return lo to hi
    }
    /** The food carries, in walking order: from each stop on the trail to the next,
     * with any stops a hitch away in between. */
    fun carries(nobo:Boolean):List<Carry>{
        val on=stops.filter{it.onTrail};val out=ArrayList<Carry>()
        for(i in 1 until on.size){val a=on[i-1];val b=on[i];if(b.km-a.km<.5)continue
            val hitch=stops.filter{!it.onTrail&&it.km>=a.km&&it.km<b.km}
            out.add(if(nobo)Carry(b,a,b.km-a.km,days(a.km,b.km),hitch.reversed())else Carry(a,b,b.km-a.km,days(a.km,b.km),hitch))}
        return if(nobo)out.reversed()else out
    }
    /** The nearest stops on the trail with food behind and ahead of [km], in walking order. */
    fun foodAround(km:Double,nobo:Boolean):Pair<Stop?,Stop?>{
        val on=stops.filter{it.onTrail}
        val south=on.firstOrNull{it.km>=km+.05};val north=on.lastOrNull{it.km<=km-.05}
        return if(nobo)south to north else north to south
    }
    /** The next place to buy food from the start of a section (or [at]), a town just
     * before it counting (Hamilton for Hamilton City); and towns a hitch away before it. */
    fun nextFood(s:Section,nobo:Boolean,at:Double?=null):Triple<Stop?,Double,List<Stop>>{
        val start=at?:(if(nobo)s.to else s.from)
        val next=foodAround(if(nobo)start+1.5 else start-1.5,nobo).second?:return Triple(null,0.0,emptyList())
        val ahead=if(nobo)start-next.km else next.km-start
        val lo=min(start,next.km);val hi=max(start,next.km)
        return Triple(next,max(0.0,ahead),walking(stops.filter{!it.onTrail&&it.km>lo&&it.km<hi},nobo))
    }

    /** Where to put the trail's name on each island: where the trail passes nearest
     * the middle of the North Island (its trail runs down the west side), the middle
     * of the South Island's trail. */
    fun namePoints():List<Pair<String,Coordinate>>{
        val split=mains.firstOrNull{it.island=="SI"}?.from?:total/2
        var best=split/2;var bd=Double.MAX_VALUE;val cx=175.75;val cy=-39.0;val k=cos(Math.toRadians(cy))
        var km=1.0;while(km<split-1){pointAt(km)?.let{p->val d=((p.longitude-cx)*k).pow(2)+(p.latitude-cy).pow(2);if(d<bd){bd=d;best=km}};km+=2.0}
        return listOfNotNull(pointAt(best)?.let{"north" to it},pointAt((split+total)/2)?.let{"south" to it})
    }

    /** The trail's name on each island, as the website writes it: a gentle curve
     * through a stretch of the trail (x and y as cubics of the distance along it,
     * least squares), one per zoom band (0: to zoom 7, 1: to 9, 2: closer), longer
     * further out and lengthened until the curve is long enough for the name. Centred
     * where the trail passes nearest the North Island's middle, and at the middle of
     * the South Island's trail. Each curve has [points] points. */
    /** The North Island's name sits just east of the trail, clear of it, per band: km along
     * the trail from the island's middle (north negative), km east (as on the website). The
     * middle is at Tongariro, where the trail loops east; closer in, it moves north. */
    private val NORTH_NAME=listOf(0.0 to 28.0,-60.0 to 8.0,-90.0 to 1.5)
    fun nameCurves(points:Int=24):List<Pair<String,List<List<Coordinate>>>>{
        val split=mains.firstOrNull{it.island=="SI"}?.from?:total/2
        val northMid=run{var best=split/2;var bd=Double.MAX_VALUE;val k=cos(Math.toRadians(-39.0));var km=1.0
            while(km<split-1){pointAt(km)?.let{p->val d=((p.longitude-175.75)*k).pow(2)+(p.latitude+39.0).pow(2);if(d<bd){bd=d;best=km}};km+=2.0};best}
        // [step km, shortest curve km, longest stretch km] per band.
        val bands=listOf(Triple(3.0,480.0,1700.0),Triple(1.0,135.0,640.0),Triple(.25,36.0,200.0))
        fun fit(c:Double,span:Double,step:Double):List<Coordinate>{
            val pts=ArrayList<Coordinate>();var k=c-span/2;while(k<=c+span/2){pointAt(k)?.let(pts::add);k+=step}
            if(pts.size<8)return emptyList()
            val ts=DoubleArray(pts.size);for(i in 1 until pts.size)ts[i]=ts[i-1]+hypot(pts[i].longitude-pts[i-1].longitude,pts[i].latitude-pts[i-1].latitude)
            val t=ts.last().takeIf{it>0}?:1.0
            fun cubic(axis:(Coordinate)->Double):DoubleArray{
                val a=Array(4){DoubleArray(5)}
                for((i,p) in pts.withIndex()){val u=ts[i]/t;for(r in 0..3){for(q in 0..3)a[r][q]+=u.pow(r+q);a[r][4]+=axis(p)*u.pow(r)}}
                for(col in 0..3){var piv=col;for(r in col+1..3)if(abs(a[r][col])>abs(a[piv][col]))piv=r
                    val tmp=a[col];a[col]=a[piv];a[piv]=tmp
                    for(r in 0..3){if(r==col)continue;val f=a[r][col]/a[col][col];for(q in col..4)a[r][q]-=f*a[col][q]}}
                return DoubleArray(4){a[it][4]/a[it][it]}
            }
            val cx=cubic{it.longitude};val cy=cubic{it.latitude}
            // The ends trimmed a little: a fitted curve strays most there.
            return List(points){i->val u=.06+.88*i/(points-1);Coordinate(cx[0]+cx[1]*u+cx[2]*u*u+cx[3]*u*u*u,cy[0]+cy[1]*u+cy[2]*u*u+cy[3]*u*u*u)}
        }
        fun km(line:List<Coordinate>)=(1 until line.size).sumOf{val k=cos(Math.toRadians(line[it].latitude));hypot((line[it].longitude-line[it-1].longitude)*111.32*k,(line[it].latitude-line[it-1].latitude)*111.32)}
        return listOf(Triple("north",0.0 to split,northMid),Triple("south",split to total,(split+total)/2)).map{(island,range,centre)->
            island to bands.mapIndexed{band,(step,least,most)->
                val north=island=="north"
                val mid=centre+if(north)NORTH_NAME[band].first else 0.0
                var line=emptyList<Coordinate>();var span=least*1.6
                while(span<=most){val lo=max(range.first+1,mid-span/2);val hi=min(range.second-1,mid+span/2)
                    line=fit((lo+hi)/2,hi-lo,step);if(km(line)>=least)break;span*=1.15}
                if(north)line.map{Coordinate(it.longitude+NORTH_NAME[band].second/(111.32*cos(Math.toRadians(it.latitude))),it.latitude)}else line}
        }
    }

    companion object {
        val KINDS=mapOf("supermarket" to "Supermarket","store" to "Store","cafe" to "Café only","box" to "No shop: send a box")
        val LEVELS=mapOf("full" to "Supermarket","limited" to "Small store","box" to "Send a box")
        const val SITE="https://www.teararoa.org.nz/"
        fun sectionPage(slug:String)="https://www.teararoa.org.nz/sections/$slug/"
        const val ALERTS_PAGE="https://www.teararoa.org.nz/trail-alerts/"
        /** The Trust's live data for a section (its alerts). */
        const val TRUST_API="https://www.teararoa.org.nz/wp-json/ta/v1/"
        fun trackApi(slug:String)="${TRUST_API}track/$slug"

        private fun unpack(c:JSONArray):List<Coordinate>{
            val out=ArrayList<Coordinate>(c.length()/2);var x=0L;var y=0L;var k=0
            while(k+1<c.length()){x+=c.getLong(k);y+=c.getLong(k+1);out.add(Coordinate(x/1e5,y/1e5));k+=2}
            return out
        }
        private fun JSONObject.str(key:String)=if(isNull(key))"" else optString(key,"")
        private fun JSONObject.num(key:String)=if(isNull(key))Double.NaN else optDouble(key,Double.NaN)
        private fun JSONObject.point(key:String)=optJSONArray(key)?.takeIf{it.length()>=2}?.let{Coordinate(it.getDouble(0),it.getDouble(1))}

        fun parse(text:String):TeAraroa{
            val j=JSONObject(text)
            val jp=j.getJSONArray("pieces")
            val pieces=List(jp.length()){i->val a=jp.getJSONArray(i);Piece(i,a.optString(0),a.getDouble(1),a.getDouble(2),a.optString(3),a.optString(4),unpack(a.getJSONArray(5)))}
            val js=j.getJSONArray("sections")
            val sections=List(js.length()){i->val s=js.getJSONObject(i)
                val parts=s.optJSONArray("line")?.let{l->List(l.length()){unpack(l.getJSONArray(it))}}.orEmpty()
                Section(s.str("slug"),s.optInt("n"),s.str("name"),s.str("region"),s.str("island"),s.optDouble("length",0.0),s.str("time"),s.optInt("grade",0),
                    s.optDouble("from",0.0),s.optDouble("to",0.0),s.optBoolean("alternative"),parts)}
            val jl=j.optJSONArray("places")?:JSONArray()
            val places=List(jl.length()){i->val p=jl.getJSONObject(i)
                Place(p.str("name"),p.str("type"),p.str("category"),p.str("facilities"),p.optBoolean("booking"),p.optBoolean("pass"),p.str("page"),p.str("website"),
                    p.str("phone"),p.str("address"),p.point("point"),p.num("km"),p.optDouble("off",0.0),p.str("section"),p.optBoolean("approx"),p.str("town"),p.str("from")=="notes")}
            val jr=j.optJSONArray("resupply")?:JSONArray()
            val stops=(0 until jr.length()).mapNotNull{i->val r=jr.getJSONObject(i);val pt=r.point("point")?:return@mapNotNull null
                val shops=r.optJSONArray("shops")?.let{a->List(a.length()){a.optString(it)}}.orEmpty()
                val level=r.str("level");val kind=r.str("kind").ifEmpty{when(level){"full"->"supermarket";"box"->"box";else->"store"}}
                Stop(r.str("name"),pt,r.optDouble("km",0.0),r.optDouble("off",0.0),level,r.str("note"),r.optBoolean("bounce"),r.optBoolean("via"),shops,kind)}
            val jh=j.optJSONArray("shops")?:JSONArray()
            val shops=(0 until jh.length()).mapNotNull{i->val x=jh.getJSONObject(i);val pt=x.point("point")?:return@mapNotNull null
                Shop(x.str("name"),x.str("kind"),pt,x.optInt("stop",-1),x.str("address"),x.str("hours"),x.str("phone"),x.str("website"))}
            return TeAraroa(j.str("name"),j.optDouble("total",0.0),pieces,sections,places,stops,shops)
        }
        /** The Trust's time for a section as least and most days ("2–3 days", "6 hours"), or null. */
        fun daysOf(time:String):Pair<Double,Double>?{
            val m=Regex("(\\d+(?:\\.\\d+)?)(?:\\s*(?:-|–|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*(days?|hours?|hrs?|h\\b|mins?)",RegexOption.IGNORE_CASE).find(time)?:return null
            val lo=m.groupValues[1].toDouble();val hi=m.groupValues[2].toDoubleOrNull()?:lo;val unit=m.groupValues[3].lowercase()
            return when{unit.startsWith("d")->lo to hi;unit.startsWith("m")->lo/480 to hi/480;else->min(lo,10.0)/8 to min(hi,10.0)/8}
        }
        fun daysText(d:Pair<Double,Double>):String{
            fun r(v:Double)=max(.5,(v*2).roundToInt()/2.0)
            fun f(v:Double)=if(v%1.0==0.0)v.toInt().toString()else v.toString()
            if(d.second<.75)return "under a day"
            return if(r(d.first)==r(d.second))"≈ ${f(r(d.first))} day${if(r(d.first)==1.0)"" else "s"}" else "≈ ${f(r(d.first))}–${f(r(d.second))} days"
        }
        /** What kind of option a bypass, water or ferry leg is: its label and why. */
        fun optionKind(s:Section):Pair<String,String>{
            val n=s.name.lowercase()
            return when{
                "hazard zone" in n->"Flood bypass" to "For when the river is high: the Trust advises against crossing it then."
                "interislander" in n||"ferry" in n->"Ferry" to "The crossing of Cook Strait."
                "water taxi" in n->"Water taxi" to "By boat to the start of the Queen Charlotte Track."
                "kayak" in n||"by water" in n||"canoe" in n->"By water" to "Paddled rather than walked."
                "road bypass" in n->"Road bypass" to "A road route around this part of the trail."
                "lake wakatipu" in n->"Bypass" to "Around Lake Wakatipu by road, instead of the boat across it."
                else->"Bypass" to "An alternative way around this part of the trail."
            }
        }
        fun km(v:Double):String{val r=(v*10).roundToInt()/10.0;return if(r%1.0==0.0)String.format(java.util.Locale.ROOT,"%,d",r.toLong())else String.format(java.util.Locale.ROOT,"%,.1f",r)}
    }

    /** A section's notes from the Trust (HTML), bundled. */
    class Notes(val intro:String,val walk:String,val access:String,val transport:String,val accommodation:String,val food:String,val conditions:String){
        companion object {
            fun parseAll(text:String):Map<String,Notes>{
                val j=JSONObject(text);val out=HashMap<String,Notes>()
                for(k in j.keys()){val o=j.optJSONObject(k)?:continue
                    fun s(key:String)=if(o.isNull(key))"" else o.optString(key,"")
                    out[k]=Notes(s("intro"),s("walk"),s("access"),s("transport"),s("accommodation"),s("food"),s("conditions"))}
                return out
            }
        }
    }
    /** One live alert from the Trust or DOC. */
    class Alert(val title:String,val status:String,val from:Double?,val to:Double?,val text:String,val date:String=""){
        companion object {
            /** The Trust's alerts for a section (its track API), images left out. */
            /** The Trust's section data (about 600 KB) reduced to its alerts, as parseTrust
             * reads them: what is kept for offline. Null when unreadable. */
            fun trustAlertsOnly(text:String):String?=runCatching{
                val a=JSONObject(text).optJSONObject("data")?.optJSONArray("alerts")?:org.json.JSONArray()
                JSONObject().put("data",JSONObject().put("alerts",a)).toString()}.getOrNull()
            fun parseTrust(text:String):List<Alert>{
                val d=JSONObject(text).optJSONObject("data")?:return emptyList();val a=d.optJSONArray("alerts")?:return emptyList()
                return (0 until min(30,a.length())).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null
                    fun s(k:String)=if(o.isNull(k))"" else o.optString(k,"")
                    fun n(k:String)=if(o.isNull(k))null else o.optString(k,"").toDoubleOrNull()
                    Alert(s("title").take(300),s("status").take(40),n("fromKm"),n("toKm"),Html.text(s("description").replace(Regex("<img\\b[^>]*>",RegexOption.IGNORE_CASE),"")).take(8000))}
                    .sortedBy{when{Regex("closure",RegexOption.IGNORE_CASE).containsMatchIn(it.status)->0;Regex("warning",RegexOption.IGNORE_CASE).containsMatchIn(it.status)->1;else->2}}
            }
            /** DOC's alerts for a walk, track or hut (www.doc.govt.nz/api/alerts/guid/…). */
            fun parseDoc(text:String):List<Alert>{
                val t=text.trim();if(!t.startsWith("["))return emptyList()
                val a=JSONArray(t)
                return (0 until min(30,a.length())).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null
                    fun s(k:String)=if(o.isNull(k))"" else o.optString(k,"")
                    val summary=s("summary").take(400);val body=Html.text(s("description")).take(8000)
                    if(summary.isBlank()&&body.isBlank())null else Alert(summary.ifBlank{"Alert"},s("subText").take(300),null,null,body,s("displayDate").take(80))}
            }
        }
    }
    /** Plain text and paragraphs from the small HTML the Trust and DOC publish. */
    object Html {
        private val ENTITIES=mapOf("amp" to "&","lt" to "<","gt" to ">","quot" to "\"","apos" to "'","nbsp" to " ","ndash" to "–","mdash" to "—","rsquo" to "’","lsquo" to "‘","rdquo" to "”","ldquo" to "“","hellip" to "…","eacute" to "é","amacr" to "ā","emacr" to "ē","imacr" to "ī","omacr" to "ō","umacr" to "ū","Amacr" to "Ā","Emacr" to "Ē","Imacr" to "Ī","Omacr" to "Ō","Umacr" to "Ū","deg" to "°")
        fun decode(s:String)=Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s){m->val e=m.groupValues[1]
            when{e.startsWith("#x")||e.startsWith("#X")->e.drop(2).toIntOrNull(16)?.let{String(Character.toChars(it))};e.startsWith("#")->e.drop(1).toIntOrNull()?.let{String(Character.toChars(it))};else->ENTITIES[e]}?:m.value}
        /** Blocks: (kind, text): "h" heading, "p" paragraph, "li" list item. */
        fun blocks(html:String):List<Pair<String,String>>{
            val cleaned=html.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"),"").replace(Regex("(?i)<br\\s*/?>"),"\n")
            val out=ArrayList<Pair<String,String>>()
            val tag=Regex("(?is)<(h[1-6]|p|li|div|tr)\\b[^>]*>(.*?)</\\1>")
            var last=0
            fun add(kind:String,raw:String){val t=decode(raw.replace(Regex("<[^>]+>")," ")).replace(Regex("[ \\t\\u00a0]+")," ").lines().joinToString("\n"){it.trim()}.trim();if(t.isNotEmpty())out.add((if(kind.startsWith("h"))"h" else if(kind=="li")"li" else "p") to t)}
            for(m in tag.findAll(cleaned)){
                val gap=cleaned.substring(last,m.range.first);if(gap.replace(Regex("<[^>]+>"),"").isNotBlank())add("p",gap)
                val kind=m.groupValues[1].lowercase();val inner=m.groupValues[2]
                // A container holding its own blocks (a div of paragraphs): its blocks.
                if(kind=="div"||kind=="tr"){if(tag.containsMatchIn(inner))out.addAll(blocks(inner))else add("p",inner)}else add(kind,inner)
                last=m.range.last+1
            }
            if(last<cleaned.length){val rest=cleaned.substring(last);if(rest.replace(Regex("<[^>]+>"),"").isNotBlank())add("p",rest)}
            return out
        }
        fun text(html:String)=blocks(html).joinToString("\n\n"){(k,t)->if(k=="li")"• $t" else t}
    }
}
