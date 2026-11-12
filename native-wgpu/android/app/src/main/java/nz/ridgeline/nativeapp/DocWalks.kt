package nz.ridgeline.nativeapp

import org.json.JSONObject
import kotlin.math.*

/** Which map tracks belong to a larger DOC walk (a circuit, traverse or
 * multi-day walk), from DOC's walk lines (CC BY 4.0). The map (LINZ) names
 * each piece on its own: part of the Gillespie Pass Circuit is "Siberia
 * Track". A piece mostly within [NEAR_METERS] of a large walk's line carries
 * that walk's name until [CHILD_NAMES], then its own. Pure Kotlin (org.json
 * only), so it is unit tested on real data. */
internal object DocWalks {
    /** Camera zoom from which a section shows its own name instead of its walk's. */
    const val CHILD_NAMES=15.0
    /** Walk lines are fetched per tile of this zoom (about 40 km in NZ). */
    const val DATA_ZOOM=10
    /** Walks are matched to map tracks from this camera zoom. */
    const val MIN_ZOOM=11.5
    const val NEAR_METERS=50.0
    /** Share of a map track that must lie along the walk. */
    const val COVER=.7
    /** Feature properties set on map tracks that belong to a walk. */
    const val WALK="ridgeline:walk";const val WALK_ID="ridgeline:walkId"
    private const val HALF=20037508.342789244

    class Info(val guid:String,val name:String,val difficulty:String,val time:String,val page:String){
        /** A circuit, traverse, crossing or multi-day walk: not a collection
         * ("Wilkin Valley tracks") or a short walk. */
        val large:Boolean get()=!COLLECTION.containsMatchIn(name)&&(DAYS.containsMatchIn(time)||LARGE.containsMatchIn(name))
    }
    private val DAYS=Regex("\\bdays?\\b",RegexOption.IGNORE_CASE)
    private val LARGE=Regex("\\b(circuit|traverse|crossing|walkway|great walk)\\b",RegexOption.IGNORE_CASE)
    private val COLLECTION=Regex("\\b(tracks|routes|walks|trails)\\b",RegexOption.IGNORE_CASE)

    /** Every DOC walk (about 1,350; ~50 KB compressed), keyed by its line id. */
    fun listUrl()=DocTracks.ORG+"DOC_Walking_Experiences/FeatureServer/0/query?where=1%3D1&outFields=name%2Cdifficulty%2CcompletionTime%2CwalkingAndTrampingWebPage&returnGeometry=false&f=json"
    fun parseList(json:String):Map<String,Info>{
        val list=runCatching{JSONObject(json).optJSONArray("features")}.getOrNull()?:return emptyMap()
        val out=HashMap<String,Info>()
        for(i in 0 until list.length()){val a=list.getJSONObject(i).optJSONObject("attributes")?:continue
            fun t(k:String)=if(a.isNull(k))"" else a.optString(k).trim()
            val page=t("walkingAndTrampingWebPage");val guid=DocTracks.guidOf(page)?:continue
            out[guid]=Info(guid,t("name"),t("difficulty"),t("completionTime"),page)}
        return out
    }
    /** Walk lines crossing a data tile, generalised to about 10 m, in WGS84. */
    fun linesUrl(z:Int,x:Int,y:Int):String{
        val n=(1 shl z).toDouble()
        fun lon(v:Double)=v/n*360-180
        fun lat(v:Double)=CameraMath.latitude(v/n)
        return DocTracks.linesNearUrl(doubleArrayOf(lon(x.toDouble()),lat(y+1.0),lon(x+1.0),lat(y.toDouble())))
    }

    /** A walk line in Web Mercator world units (0..1). */
    class Line(val guid:String,val meters:Double,val paths:List<List<VectorTile.Point>>)
    fun lines(walkLines:List<DocTracks.WalkLine>)=walkLines.map{w->Line(w.guid,w.meters,w.paths.map{p->p.map{VectorTile.Point((it.longitude+180)/360,CameraMath.mercatorY(it.latitude))}})}

    /** Segments of [lines] bucketed on a grid (cells of zoom [GRID_ZOOM], about
     * 440 m in NZ), so "which walks pass within 50 m" checks a few segments. */
    class Index(val lines:List<Line>){
        private val cells=HashMap<Long,MutableList<Int>>()
        private val segs=ArrayList<DoubleArray>()
        init{
            val n=(1 shl GRID_ZOOM).toDouble()
            for((li,l) in lines.withIndex())for(p in l.paths)for(i in 1 until p.size){
                val a=p[i-1];val b=p[i];val s=segs.size;segs.add(doubleArrayOf(a.x,a.y,b.x,b.y,li.toDouble()))
                for(cx in floor(min(a.x,b.x)*n).toInt()..floor(max(a.x,b.x)*n).toInt())for(cy in floor(min(a.y,b.y)*n).toInt()..floor(max(a.y,b.y)*n).toInt())
                    cells.getOrPut(key(cx,cy)){ArrayList()}.add(s)
            }
        }
        val isEmpty get()=lines.isEmpty()
        /** Lines (indexes) within [r] world units of ([x], [y]). */
        fun near(x:Double,y:Double,r:Double,out:MutableSet<Int>){
            val n=(1 shl GRID_ZOOM).toDouble()
            for(cx in floor((x-r)*n).toInt()..floor((x+r)*n).toInt())for(cy in floor((y-r)*n).toInt()..floor((y+r)*n).toInt()){
                for(s in cells[key(cx,cy)]?:continue){val g=segs[s];val li=g[4].toInt();if(li in out)continue
                    if(distance(x,y,g[0],g[1],g[2],g[3])<=r)out.add(li)}
            }
        }
        companion object{
            const val GRID_ZOOM=16
            private fun key(x:Int,y:Int)=(x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
            fun distance(px:Double,py:Double,ax:Double,ay:Double,bx:Double,by:Double):Double{
                val dx=bx-ax;val dy=by-ay;val l=dx*dx+dy*dy
                val t=if(l==0.0)0.0 else(((px-ax)*dx+(py-ay)*dy)/l).coerceIn(0.0,1.0)
                return hypot(ax+dx*t-px,ay+dy*t-py)
            }
        }
    }
    /** World units for [meters] at world y [y]. */
    fun worldMeters(meters:Double,y:Double):Double{val lat=CameraMath.latitude(y);return meters/(40_075_016.686*max(.05,cos(Math.toRadians(lat))))}

    /** The largest walk whose line runs along [path] (at least [COVER] of its
     * points within [NEAR_METERS]), or null. */
    fun walkFor(path:List<VectorTile.Point>,index:Index,infos:Map<String,Info>):Line?{
        if(path.size<2||index.isEmpty)return null
        val step=max(1,path.size/40);val counts=HashMap<Int,Int>();var samples=0;val near=HashSet<Int>()
        val r=worldMeters(NEAR_METERS,path[0].y)
        var i=0
        while(i<path.size){val p=path[i];near.clear();index.near(p.x,p.y,r,near);for(li in near)counts[li]=(counts[li]?:0)+1;samples++;i+=step}
        return counts.filter{(li,c)->c>=samples*COVER&&infos[index.lines[li].guid]?.large==true}.keys.map{index.lines[it]}.maxByOrNull{it.meters}
    }
    /** [features] with each map track that belongs to a large walk marked
     * ([WALK], [WALK_ID]); unnamed pieces take the walk's name. */
    fun mark(features:List<VectorTile.Feature>,index:Index?,infos:Map<String,Info>):List<VectorTile.Feature>{
        if(index==null||index.isEmpty||infos.isEmpty())return features
        return features.map{f->
            if(f.layer!="streets"||f.properties["kind"]!="track")return@map f
            val line=f.paths.maxByOrNull{it.size}?.let{walkFor(it,index,infos)}?:return@map f
            val info=infos[line.guid]?:return@map f
            val props=HashMap(f.properties);props[WALK]=info.name;props[WALK_ID]=info.guid
            if((props["name"] as? String).isNullOrBlank())props["name"]=info.name
            f.copy(properties=props)
        }
    }
}
