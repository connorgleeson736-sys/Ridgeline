package nz.ridgeline.nativeapp

import org.json.JSONObject
import kotlin.math.*

/** Department of Conservation track details, queried live from DOC's open
 * ArcGIS services (CC BY 4.0) when a track name is tapped: the walk (summary,
 * difficulty, time, photo, page), the track asset (status, type, dogs), the
 * walk's own line for the elevation profile, and huts beside the track.
 * Pure Kotlin (org.json only), so parsing and matching are unit tested. */
internal object DocTracks {
    const val ORG="https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/"
    private const val WALKS="DOC_Walking_Experiences"
    private const val LINES="DOC_WebsiteWalkingandTrampingLns_DTO"
    private const val TRACKS="DOC_Tracks_Open_Data"
    private const val HUTS="DOC_Huts_Open_Data"
    private const val HUT_PAGES="DOC_Huts"
    /** Walks and tracks with the tapped name are searched this far (degrees). */
    private const val SEARCH=.35
    /** Huts this close to the track are listed. */
    const val HUT_METERS=350.0
    fun isUrl(url:String)=url.startsWith(ORG)

    class Walk(val name:String,val introduction:String,val difficulty:String,val time:String,val thumbnail:String,val page:String,val point:Coordinate?){
        /** DOC's page link carries the walk's id, which keys its line. */
        val guid:String? get()=guidOf(page)
    }
    class Track(val name:String,val status:String,val category:String,val dogs:String,val type:String,val meters:Double,val paths:List<List<Coordinate>>){
        val closed get()=status.isNotBlank()&&!status.equals("OPEN",true)
    }
    class Hut(val name:String,val category:String,val bunks:String,val facilities:String,val point:Coordinate){
        var page="";var bookable=false
    }
    /** A hut beside the track, [along] metres from its start. */
    class Stop(val hut:Hut,val along:Double)

    private fun enc(s:String)=java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20")
    private fun quote(s:String)="'"+s.replace("'","''")+"'"
    private fun f(v:Double)=String.format(java.util.Locale.ROOT,"%.6f",v)
    private fun query(service:String,where:String,fields:String,geometry:Boolean,extra:String="")=
        ORG+service+"/FeatureServer/0/query?where="+enc(where)+"&outFields="+enc(fields)+"&returnGeometry=$geometry&outSR=4326$extra&f=json"
    private fun near(lon:Double,lat:Double,r:Double)="&geometry=${f(lon-r)},${f(lat-r)},${f(lon+r)},${f(lat+r)}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
    /** Names as on the map; DOC sometimes differs only in case. */
    fun walkUrl(name:String,lon:Double,lat:Double)=query(WALKS,"UPPER(name)=UPPER(${quote(name)})","name,introduction,difficulty,completionTime,introductionThumbnail,walkingAndTrampingWebPage",true,near(lon,lat,SEARCH))
    fun trackUrl(name:String,lon:Double,lat:Double)=query(TRACKS,"UPPER(TechObjectName)=UPPER(${quote(name)})","*",true,near(lon,lat,SEARCH)+"&maxAllowableOffset=0.00002")
    fun lineUrl(guid:String)=query(LINES,"EPISERVERGUID=${quote(guid)}","EPISERVERGUID",true,"&maxAllowableOffset=0.00002")
    fun hutsUrl(b:DoubleArray)=query(HUTS,"1=1","*",true,"&geometry=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects")
    fun hutPagesUrl(b:DoubleArray)=query(HUT_PAGES,"1=1","name,bookable,staticLink",true,"&geometry=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects")

    /** "https://www.doc.govt.nz/link/c073dc44e9aa4c369b8a75cb8365f047.aspx" →
     * "c073dc44-e9aa-4c36-9b8a-75cb8365f047". */
    fun guidOf(link:String):String?{
        val hex=Regex("/link/([0-9a-fA-F]{32})\\.").find(link)?.groupValues?.get(1)?.lowercase()?:return null
        return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
    }
    private fun features(json:String):List<JSONObject>{
        val root=runCatching{JSONObject(json)}.getOrNull()?:return emptyList()
        val list=root.optJSONArray("features")?:return emptyList()
        return List(list.length()){list.getJSONObject(it)}
    }
    private fun JSONObject.text(key:String)=if(isNull(key))"" else optString(key).trim()
    /** Pairs of CharNameN/CharValueN as a map. */
    private fun characteristics(a:JSONObject):Map<String,String>{
        val out=HashMap<String,String>()
        for(key in a.keys())if(key.startsWith("CharName")){val name=a.text(key);val value=a.text("CharValue"+key.removePrefix("CharName"));if(name.isNotEmpty()&&value.isNotEmpty())out[name]=value}
        return out
    }
    private fun paths(g:JSONObject?):List<List<Coordinate>>{
        val paths=g?.optJSONArray("paths")?:return emptyList()
        return List(paths.length()){i->val p=paths.getJSONArray(i);List(p.length()){k->val c=p.getJSONArray(k);Coordinate(c.getDouble(0),c.getDouble(1))}}.filter{it.size>=2}
    }
    private fun point(g:JSONObject?):Coordinate?{
        if(g==null||!g.has("x")||!g.has("y"))return null
        val x=g.optDouble("x");val y=g.optDouble("y");return if(x.isFinite()&&y.isFinite())Coordinate(x,y)else null
    }
    fun parseWalks(json:String)=features(json).map{f->val a=f.getJSONObject("attributes")
        Walk(a.text("name"),a.text("introduction"),a.text("difficulty"),a.text("completionTime"),a.text("introductionThumbnail"),a.text("walkingAndTrampingWebPage"),point(f.optJSONObject("geometry")))}
    fun parseTracks(json:String)=features(json).map{f->val a=f.getJSONObject("attributes");val c=characteristics(a)
        Track(a.text("TechObjectName"),c["USER_STATUS"].orEmpty(),c["WEB_CATEGORY_TRACK"].orEmpty(),c["WEB_DOGS_ALLOWED"].orEmpty(),c["WEB_TRACK_TYPE"].orEmpty(),a.optDouble("Shape__Length",0.0).takeIf{it.isFinite()}?:0.0,paths(f.optJSONObject("geometry")))}
    fun parseLines(json:String)=features(json).flatMap{paths(it.optJSONObject("geometry"))}
    fun parseHuts(json:String)=features(json).mapNotNull{f->val a=f.getJSONObject("attributes");val c=characteristics(a)
        val p=point(f.optJSONObject("geometry"))?:return@mapNotNull null
        if(c["USER_STATUS"]?.equals("OPEN",true)==false||c["PUBLIC_USE"]?.equals("No",true)==true)return@mapNotNull null
        Hut(a.text("TechObjectName"),a.text("SubObjectType"),c["WEB_HUT_BUNKS"].orEmpty(),c["WEB_FACILITIES_HUT"].orEmpty(),p)}
    /** DOC's hut pages (name, bookable, link), joined to [huts] by name. */
    fun joinHutPages(huts:List<Hut>,json:String){
        val pages=features(json).associate{val a=it.getJSONObject("attributes");a.text("name").lowercase() to a}
        for(h in huts){val a=pages[h.name.lowercase()]?:continue;h.page=a.text("staticLink");h.bookable=a.text("bookable").equals("Yes",true)}
    }

    /** The walk named as on the map nearest the tap. */
    fun nearestWalk(walks:List<Walk>,at:Coordinate)=walks.minByOrNull{w->w.point?.let{meters(it,at)}?:Double.MAX_VALUE}
    /** Joins pieces into one line through the piece nearest [at]: each step
     * adds the piece whose end is closest to either end of the line (within
     * [gap] metres), reversed as needed. Side branches stay out. */
    fun chain(pieces:List<List<Coordinate>>,at:Coordinate,gap:Double=150.0):List<Coordinate>{
        val left=pieces.filter{it.size>=2}.toMutableList();if(left.isEmpty())return emptyList()
        val first=left.minByOrNull{p->distanceTo(p,at)}!!;left.remove(first)
        val line=ArrayList(first)
        while(left.isNotEmpty()){
            var best:List<Coordinate>?=null;var bestD=gap;var atEnd=true;var reverse=false
            for(p in left)for((end,head) in listOf(true to p.first(),true to p.last(),false to p.first(),false to p.last())){
                val d=meters(if(end)line.last()else line.first(),head)
                if(d<bestD){bestD=d;best=p;atEnd=end;reverse=if(end)head==p.last()else head==p.first()}
            }
            val p=best?:break;left.remove(p);val piece=if(reverse)p.reversed()else p
            if(atEnd)line.addAll(piece.drop(1))else line.addAll(0,piece.dropLast(1))
        }
        return line
    }
    /** Huts within [limit] metres of [line], in order along it. */
    fun stops(huts:List<Hut>,line:List<Coordinate>,limit:Double=HUT_METERS):List<Stop>{
        if(line.size<2)return emptyList()
        val out=ArrayList<Stop>();val seen=HashSet<String>()
        for(h in huts){
            var best=Double.MAX_VALUE;var along=0.0;var walked=0.0
            for(i in 1 until line.size){val a=line[i-1];val b=line[i];val (d,t)=segment(h.point,a,b);val len=meters(a,b)
                if(d<best){best=d;along=walked+len*t};walked+=len}
            if(best<=limit&&seen.add(h.name.lowercase()))out.add(Stop(h,along))
        }
        return out.sortedBy{it.along}
    }
    /** Longitude/latitude bounds of [line] grown by [meters]. */
    fun bounds(line:List<Coordinate>,meters:Double=HUT_METERS):DoubleArray?{
        if(line.isEmpty())return null
        val lat=line.sumOf{it.latitude}/line.size;val dy=meters/111_320.0;val dx=dy/max(.2,cos(Math.toRadians(lat)))
        return doubleArrayOf(line.minOf{it.longitude}-dx,line.minOf{it.latitude}-dy,line.maxOf{it.longitude}+dx,line.maxOf{it.latitude}+dy)
    }
    fun length(line:List<Coordinate>)=(1 until line.size).sumOf{meters(line[it-1],line[it])}
    private fun distanceTo(path:List<Coordinate>,p:Coordinate)=(1 until path.size).minOfOrNull{segment(p,path[it-1],path[it]).first}?:path.firstOrNull()?.let{meters(it,p)}?:Double.MAX_VALUE
    /** Distance from [p] to segment [a]-[b] in metres, and the position along it (0..1). */
    private fun segment(p:Coordinate,a:Coordinate,b:Coordinate):Pair<Double,Double>{
        val k=cos(Math.toRadians(p.latitude))*111_320.0;val m=111_320.0
        val ax=(a.longitude-p.longitude)*k;val ay=(a.latitude-p.latitude)*m;val bx=(b.longitude-p.longitude)*k;val by=(b.latitude-p.latitude)*m
        val dx=bx-ax;val dy=by-ay;val l=dx*dx+dy*dy
        val t=if(l==0.0)0.0 else(-(ax*dx+ay*dy)/l).coerceIn(0.0,1.0)
        return hypot(ax+dx*t,ay+dy*t) to t
    }
    fun meters(a:Coordinate,b:Coordinate):Double{
        val k=cos(Math.toRadians((a.latitude+b.latitude)/2))
        return hypot((a.longitude-b.longitude)*k,a.latitude-b.latitude)*111_320.0
    }
    /** "Over 4 hours", "No Dogs" → sentence case for chips. */
    fun tidy(value:String)=value.trim().lowercase().replaceFirstChar{it.uppercase()}
}
