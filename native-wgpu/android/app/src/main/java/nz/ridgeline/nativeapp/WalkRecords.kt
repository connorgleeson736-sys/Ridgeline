package nz.ridgeline.nativeapp

import org.json.JSONObject
import kotlin.math.roundToLong

/** DOC walks compiled offline (native-wgpu/tools/walks): each walk read from
 * its DOC page (where it starts and ends, the places and tracks it goes
 * through, loop or out and back, the way round, its huts), followed over the
 * map's tracks and checked against DOC's own line and time. Bundled as
 * assets/walks/walks.json.gz. A walk is [Walk.verified] only when every check
 * passed; the app then shows it as compiled, and otherwise DOC's own line.
 * Pure Kotlin (org.json), unit tested. */
internal object WalkRecords {
    const val ASSET="walks/walks.json.gz"
    /** One way to walk it: [from] to [to] ([shape]: one_way, out_and_back or
     * loop; an out-and-back [line] is the way out), DOC's [time] and
     * [distance] for it when the page gives them. */
    class Way(val label:String,val from:String,val to:String,val shape:String,val main:Boolean,val line:List<Coordinate>,val time:String,val distance:String,
              /** The places named on the way, in order, and where each is along [line] (metres). */
              val stops:List<Pair<String,Double>> = emptyList())
    /** A hut on the walk, from DOC's own link on the page. */
    class Hut(val name:String,val point:Coordinate,val link:String)
    class Walk(val guid:String,val verified:Boolean,val flags:List<String>,val kind:String,val ways:List<Way>,val huts:List<Hut>)

    fun parse(json:String):Map<String,Walk>{
        val root=JSONObject(json);val walks=root.getJSONObject("walks");val out=HashMap<String,Walk>()
        for(guid in walks.keys()){val w=walks.getJSONObject(guid)
            val ways=w.optJSONArray("ways")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}.map{x->
                Way(x.optString("label"),x.optString("from"),x.optString("to"),x.optString("shape"),x.optBoolean("main"),decode(x.optString("line")),x.optString("time"),x.optString("distance"),
                    x.optJSONArray("stops")?.let{st->(0 until st.length()).map{st.getJSONObject(it).let{o->o.getString("name") to o.getDouble("at")}}}.orEmpty())}}.orEmpty()
            val huts=w.optJSONArray("huts")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}.map{h->Hut(h.getString("name"),Coordinate(h.getDouble("lon"),h.getDouble("lat")),h.optString("link"))}}.orEmpty()
            val flags=w.optJSONArray("flags")?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()
            out[guid]=Walk(guid,w.optString("status")=="verified",flags,w.optString("kind"),ways,huts)
        }
        return out
    }

    /** A line as text: longitude/latitude in millionths of a degree, each the
     * difference from the last, zigzag base-64 varints (Google's polyline
     * scheme at 1e-6, about 0.1 m). */
    fun encode(line:List<Coordinate>):String{
        val b=StringBuilder();var lx=0L;var ly=0L
        fun put(v:Long){var z=if(v<0)(v shl 1).inv() else v shl 1
            while(z>=0x20){b.append(((0x20L or (z and 0x1f))+63).toInt().toChar());z=z shr 5}
            b.append((z+63).toInt().toChar())}
        for(c in line){val x=(c.longitude*1e6).roundToLong();val y=(c.latitude*1e6).roundToLong();put(y-ly);put(x-lx);lx=x;ly=y}
        return b.toString()
    }
    fun decode(s:String):List<Coordinate>{
        val out=ArrayList<Coordinate>();var i=0;var x=0L;var y=0L
        fun next():Long{var r=0L;var shift=0
            while(true){val c=s[i++].code-63L;r=r or ((c and 0x1f) shl shift);shift+=5;if(c<0x20)break}
            return if(r and 1L==1L)(r shr 1).inv() else r shr 1}
        while(i<s.length){y+=next();x+=next();out.add(Coordinate(x/1e6,y/1e6))}
        return out
    }
}
