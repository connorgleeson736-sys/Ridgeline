package nz.ridgeline.nativeapp

import java.text.Normalizer
import kotlin.math.*

/** Offline search over the bundled NZ Gazetteer and DOC huts, campsites and
 * walks (assets/search/places.tsv). Macron- and case-insensitive, word
 * prefixes in any order ("gilles pass"), "Mt" for "Mount", and one typo per
 * longer word. Ranked by match quality, importance, then distance. */
internal class PlaceSearch(lines:Sequence<String>){
    data class Place(val kind:String,val name:String,val alternatives:List<String>,val latitude:Double,val longitude:Double,val rank:Double,val detail:String,val link:String)
    data class Result(val place:Place,val score:Double,val distanceKm:Double)
    private class Entry(val place:Place,val words:Array<String>,val full:Array<String>)
    private val entries:List<Entry> = lines.mapNotNull{line->
        val f=line.split('\t');if(f.size<8)return@mapNotNull null
        val lat=f[3].toDoubleOrNull()?:return@mapNotNull null;val lon=f[4].toDoubleOrNull()?:return@mapNotNull null
        val alts=if(f[2].isEmpty())emptyList()else f[2].split('|')
        val place=Place(f[0],f[1],alts,lat,lon,f[5].toDoubleOrNull()?:5.0,f[6],f[7])
        // Each name normalised once; its words come from the same string.
        val full=Array(1+alts.size){normalize(if(it==0)place.name else alts[it-1])}
        val words=LinkedHashSet<String>();for(n in full)if(n.isNotEmpty())words.addAll(n.split(' '))
        Entry(place,words.toTypedArray(),full)
    }.toList()
    val size get()=entries.size
    /** The place called exactly [name] (macron- and case-insensitive, "Mt" as
     * "Mount") nearest [near], within [maxKm]: where a DOC walk's leg starts
     * or ends ("Makarora", "Kerin Forks Hut"). */
    fun locate(name:String,near:Coordinate,maxKm:Double):Place?{
        val key=normalize(name);if(key.isEmpty())return null
        fun nearest(match:(String)->Boolean)=entries.asSequence().filter{e->e.full.any(match)}.map{it.place to distanceKm(near,it.place.latitude,it.place.longitude)}
            .filter{it.second<=maxKm}.minByOrNull{it.second}?.first
        // Exact; else a longer name that starts with it ("Anchorage" → "Anchorage Bay");
        // else without generic words ("Kepler Track carpark", "Lewis Pass picnic area").
        val bare=key.split(' ').filterNot{it in GENERIC}.joinToString(" ")
        return nearest{it==key}?:nearest{it.startsWith("$key ")}?:bare.takeIf{it.isNotEmpty()&&it!=key}?.let{b->nearest{it==b}?:nearest{it.startsWith("$b ")}}
    }
    private val GENERIC=setOf("carpark","car","park","road","end","roadend","shelter","picnic","area","village","settlement","township","track","entrance","start","the")
    fun search(query:String,near:Coordinate?,limit:Int=30):List<Result>{
        val q=words(query);if(q.isEmpty())return emptyList()
        val phrase=normalize(query)
        val out=ArrayList<Result>()
        fun scan(typosAllowed:Boolean){
            for(e in entries){
                var typos=0;var matched=true
                for(word in q){
                    if(e.words.any{it.startsWith(word)})continue
                    if(typosAllowed&&word.length>=4&&e.words.any{close(it,word)}){typos++;continue}
                    matched=false;break
                }
                if(!matched||typosAllowed&&typos==0)continue
                val quality=when{e.full.any{it==phrase}->0.0;e.full.any{it.startsWith(phrase)}->1.0;else->2.0}+typos*1.5
                val km=near?.let{distanceKm(it,e.place.latitude,e.place.longitude)}?:0.0
                // Importance first, then a gentle preference for nearby places.
                out.add(Result(e.place,quality*2+e.place.rank+ln(1+km/50)*.6,km))
            }
        }
        scan(false)
        // Typo-tolerant pass only when exact prefixes find little.
        if(out.size<10)scan(true)
        return out.sortedBy{it.score}.take(limit)
    }
    companion object{
        @Volatile private var loaded:PlaceSearch?=null
        /** The bundled index, read once and shared by search and walk routes (blocking). */
        fun get(assets:android.content.res.AssetManager):PlaceSearch?=loaded?:synchronized(this){
            loaded?:runCatching{assets.open("search/places.tsv").bufferedReader().use{PlaceSearch(it.lineSequence())}}.getOrNull()?.also{loaded=it}}
        /** Lowercase, macrons and other accents removed, anything that is not a
         * letter or digit as one space, "mt" as "mount" and "st" as "saint".
         * A plain character loop: no regular expressions (this runs for every
         * one of ~60,000 names when search loads). */
        fun normalize(s:String):String{
            val ascii=s.all{it.code<128}
            val text=if(ascii)s else Normalizer.normalize(s,Normalizer.Form.NFD)
            val out=StringBuilder(text.length);var space=true
            for(ch in text){
                val c=if(ch in 'A'..'Z')ch+32 else ch
                if(c in 'a'..'z'||c in '0'..'9'){out.append(c);space=false}
                else if(!ascii&&Character.getType(c)==Character.NON_SPACING_MARK.toInt())continue
                else if(!ascii&&Character.isLetterOrDigit(c)){out.append(c.lowercaseChar());space=false}
                else if(!space){out.append(' ');space=true}
            }
            if(out.isNotEmpty()&&out[out.length-1]==' ')out.setLength(out.length-1)
            // Whole-word abbreviations.
            if(out.indexOf("mt")<0&&out.indexOf("st")<0)return out.toString()
            return out.split(' ').joinToString(" "){when(it){"mt"->"mount";"st"->"saint";else->it}}
        }
        fun words(s:String):List<String>{val n=normalize(s);return if(n.isEmpty())emptyList()else n.split(' ')}
        /** A word within one edit of [word]'s length-prefix (a typo while typing). */
        private fun close(candidate:String,word:String):Boolean{
            if(candidate.length<word.length-1)return false
            val target=candidate.take(word.length)
            return edits(target,word)<=1||candidate.length>=word.length+1&&edits(candidate.take(word.length+1),word)<=1||edits(candidate.take(max(1,word.length-1)),word)<=1
        }
        private fun edits(a:String,b:String):Int{
            if(abs(a.length-b.length)>1)return 2
            var prev=IntArray(b.length+1){it};var cur=IntArray(b.length+1)
            for(i in 1..a.length){cur[0]=i;var best=cur[0]
                for(j in 1..b.length){cur[j]=minOf(prev[j]+1,cur[j-1]+1,prev[j-1]+if(a[i-1]==b[j-1])0 else 1);best=min(best,cur[j])}
                if(best>1)return 2;val t=prev;prev=cur;cur=t}
            return prev[b.length]
        }
        fun distanceKm(a:Coordinate,lat:Double,lon:Double):Double{
            val p1=Math.toRadians(a.latitude);val p2=Math.toRadians(lat);val dp=p2-p1;val dl=Math.toRadians(lon-a.longitude)
            val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return 6371*2*asin(min(1.0,sqrt(h)))
        }
        fun label(kind:String)=when(kind){"walk"->"Track";"hut"->"Hut";"camp"->"Campsite";"peak"->"Peak";"pass"->"Pass";"lake"->"Lake";"glacier"->"Glacier";"river"->"River";"place"->"Place";"valley"->"Valley";"ridge"->"Ridge";"waterfall"->"Waterfall";"coast"->"Coast";"area"->"Area";else->"Place"}
        /** Zoom to fly to for a result: wide for tracks and places, close for huts. */
        fun zoom(kind:String)=when(kind){"walk"->12.0;"place","area","lake","river","valley","coast"->12.5;"glacier","ridge"->13.0;"peak","pass","waterfall"->13.5;else->14.5}
    }
}
