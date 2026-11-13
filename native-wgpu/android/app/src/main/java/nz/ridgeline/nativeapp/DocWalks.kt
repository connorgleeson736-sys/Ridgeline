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
    /** A DOC page for several tracks ("Wilkin Valley tracks"), not one walk. */
    fun collection(name:String)=COLLECTION.containsMatchIn(name)

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
    /** [whole]: DOC's entire line of a large walk, side trips included: for
     * matching a track to its walk by name, never for naming others. */
    class Line(val guid:String,val meters:Double,val paths:List<List<VectorTile.Point>>,val whole:Boolean=false)
    fun lines(walkLines:List<DocTracks.WalkLine>,whole:Boolean=false)=walkLines.map{w->Line(w.guid,w.meters,w.paths.map{p->p.map{VectorTile.Point((it.longitude+180)/360,CameraMath.mercatorY(it.latitude))}},whole)}

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

    private val generic=setOf("track","tracks","walk","walks","walkway","route","routes","circuit","loop","the","great","trail")
    private fun words(name:String)=PlaceSearch.normalize(name).split(' ').filter{it.isNotEmpty()&&it !in generic}
    /** The map's name and a DOC walk's name are the same walk: equal without
     * generic words ("Gillespie Pass Circuit Track" and "Gillespie Pass
     * Circuit", "Wilkin Valley Track" and "Wilkin Valley tracks"). */
    fun sameName(a:String,b:String):Boolean{val x=words(a);val y=words(b);return x.isNotEmpty()&&x==y}
    /** The DOC walk a map track belongs to, and whether the track shows that
     * walk's name until [CHILD_NAMES]. Of the walks along at least [COVER] of
     * it (within [NEAR_METERS]; large walks by the walk itself, see
     * WalkRoutes.extent): the one with the track's own name; else the most
     * specific large walk (circuit, traverse, multi-day), whose name it takes
     * (the Gillespie Pass Circuit's Siberia Track); else the most specific walk.
     * Side trips and extensions on DOC's line are not the walk: the Wilkin
     * Valley Track up to Top Forks Hut keeps its own name. */
    fun walkFor(path:List<VectorTile.Point>,own:String,index:Index,infos:Map<String,Info>):Pair<Line,Boolean>?{
        if(path.size<2||index.isEmpty)return null
        val step=max(1,path.size/40);val counts=HashMap<Int,Int>();var samples=0;val near=HashSet<Int>()
        val r=worldMeters(NEAR_METERS,path[0].y)
        var i=0
        while(i<path.size){val p=path[i];near.clear();index.near(p.x,p.y,r,near);for(li in near)counts[li]=(counts[li]?:0)+1;samples++;i+=step}
        val along=counts.filter{(_,c)->c>=samples*COVER}.keys.map{index.lines[it]}.filter{infos.containsKey(it.guid)}
        if(along.isEmpty())return null
        along.filter{own.isNotBlank()&&sameName(own,infos[it.guid]!!.name)}.minByOrNull{it.meters}?.let{return it to infos[it.guid]!!.large}
        // Large walks take precedence over a track's own section name: a track on
        // a large walk (the walk itself, without its side trips) carries its name;
        // of several, the most specific (the Travers-Sabine Circuit, not the Te
        // Araroa section that also runs up the Travers valley).
        along.filter{!it.whole&&infos[it.guid]!!.large}.minByOrNull{it.meters}?.let{return it to true}
        // Otherwise it opens the most specific DOC walk along it, under its own name.
        return along.minByOrNull{it.meters}?.let{it to false}
    }
    /** [features] with each map track marked by its DOC walk ([WALK_ID], for
     * taps) and, for large walks, the walk's name ([WALK]); unnamed pieces of
     * a large walk take its name. */
    fun mark(features:List<VectorTile.Feature>,index:Index?,infos:Map<String,Info>):List<VectorTile.Feature>{
        if(index==null||index.isEmpty||infos.isEmpty())return features
        return features.map{f->
            if(f.layer!="streets"||f.properties["kind"]!="track")return@map f
            val own=(f.properties["name"] as? String).orEmpty()
            val (line,named)=f.paths.maxByOrNull{it.size}?.let{walkFor(it,own,index,infos)}?:return@map f
            val info=infos[line.guid]?:return@map f
            val props=HashMap(f.properties);props[WALK_ID]=info.guid
            if(named){props[WALK]=info.name;if(own.isBlank())props["name"]=info.name}
            f.copy(properties=props)
        }
    }
}
