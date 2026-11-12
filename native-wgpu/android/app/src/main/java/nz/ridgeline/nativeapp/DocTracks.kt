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
    /** The map's name and DOC's spellings of it: case, and "Mt"/"Mount" (the
     * map's "Mt Iron Track" is DOC's "Mount Iron Track"). */
    fun names(name:String):List<String>{
        val n=name.trim().replace(Regex("\\s+")," ")
        val out=linkedSetOf(n)
        out.add(n.replace(Regex("\\bMt\\.?(?= )"),"Mount"));out.add(n.replace(Regex("\\bMount(?= )"),"Mt"))
        return out.toList()
    }
    private fun anyOf(field:String,name:String)="UPPER($field) IN (${names(name).joinToString(","){quote(it.uppercase(java.util.Locale.ROOT))}})"
    fun walkUrl(name:String,lon:Double,lat:Double)=query(WALKS,anyOf("name",name),"name,introduction,difficulty,completionTime,introductionThumbnail,walkingAndTrampingWebPage",true,near(lon,lat,SEARCH))
    fun trackUrl(name:String,lon:Double,lat:Double)=query(TRACKS,anyOf("TechObjectName",name),"*",true,near(lon,lat,SEARCH)+"&maxAllowableOffset=0.0001")
    /** DOC's walk lines crossing [b] (generalised to ~10 m), to find the walk a
     * track belongs to whatever either is called. */
    fun linesNearUrl(b:DoubleArray)=query(LINES,"1=1","EPISERVERGUID,Shape__Length",true,"&geometry=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects&maxAllowableOffset=0.0001")
    /** The walk whose DOC page link carries [guid]. */
    fun walkByGuidUrl(guid:String)=query(WALKS,"walkingAndTrampingWebPage LIKE ${quote("%"+guid.replace("-","").lowercase()+"%")}","name,introduction,difficulty,completionTime,introductionThumbnail,walkingAndTrampingWebPage",true)
    fun lineUrl(guid:String)=query(LINES,"EPISERVERGUID=${quote(guid)}","EPISERVERGUID",true,"&maxAllowableOffset=0.00002")
    fun hutsUrl(b:DoubleArray)=query(HUTS,"1=1","*",true,"&geometry=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects")
    fun hutPagesUrl(b:DoubleArray)=query(HUT_PAGES,"1=1","name,bookable,staticLink",true,"&geometry=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects")

    // Hut cards: DOC's hut assets (category, bunks, walk from the road end;
    // DOC_Huts_DTO) and hut pages (place, bookings, facilities, photo, page;
    // DOC_Huts), both CC BY 4.0, searched about 1 km around the tapped hut.
    private const val HUT_ASSETS="DOC_Huts_DTO"
    fun hutAssetsUrl(lon:Double,lat:Double)=query(HUT_ASSETS,"1=1","TechObjectName,SubObjectType,CharName1,CharValue1,CharName2,CharValue2,CharName3,CharValue3,CharName4,CharValue4,CharName5,CharValue5,CharName6,CharValue6,CharName7,CharValue7,CharName8,CharValue8",true,near(lon,lat,.012))
    fun hutPagesNearUrl(lon:Double,lat:Double)=query(HUT_PAGES,"1=1","name,place,bookable,facilities,introductionThumbnail,staticLink",true,near(lon,lat,.012))
    class HutAsset(val name:String,val category:String,val bunks:String,val roadEnd:String,val facilities:String,val status:String,val point:Coordinate){
        val closed get()=status.isNotBlank()&&!status.equals("OPEN",true)
    }
    class HutPage(val name:String,val place:String,val bookable:Boolean,val facilities:List<String>,val thumbnail:String,val page:String,val point:Coordinate)
    fun parseHutAssets(json:String)=features(json).mapNotNull{f->val a=f.getJSONObject("attributes");val c=characteristics(a)
        val p=point(f.optJSONObject("geometry"))?:return@mapNotNull null
        if(c["PUBLIC_USE"]?.equals("No",true)==true)return@mapNotNull null
        HutAsset(a.text("TechObjectName"),c["WEB_CATEGORY_HUT"]?:a.text("SubObjectType").removeSuffix(" Hut"),c["WEB_HUT_BUNKS"].orEmpty(),c["WEB_WALK_ROADEND"].orEmpty(),c["WEB_FACILITIES_HUT"].orEmpty(),c["USER_STATUS"].orEmpty(),p)}
    fun parseHutPages(json:String)=features(json).mapNotNull{f->val a=f.getJSONObject("attributes")
        val p=point(f.optJSONObject("geometry"))?:return@mapNotNull null
        // "Heating, Water from tap - not treated, boil before use, Toilets": items start with a capital.
        val facilities=a.text("facilities").split(Regex(",\\s+(?=[A-Z])")).map{it.trim()}.filter{it.isNotEmpty()}
        HutPage(a.text("name"),a.text("place"),a.text("bookable").equals("Yes",true),facilities,a.text("introductionThumbnail"),a.text("staticLink"),p)}.distinctBy{it.page.ifEmpty{it.name}}
    /** "Siberia Hut", "Siberia hut " and "Siberia" are one hut; "Biv",
     * "Bivvy" and "Bivouac" are one word. */
    fun hutKey(name:String)=name.lowercase().replace(Regex("\\b(bivouac|bivvy|biv)\\b"),"biv").replace(Regex("\\bhut\\b"),"").replace(Regex("[^a-z0-9]+")," ").trim()
    /** The record for the map's hut [name] at [at]: same name nearest, else the
     * nearest within [within] metres (the map and DOC sometimes differ). */
    fun <T> matchHut(items:List<T>,name:String,at:Coordinate,nameOf:(T)->String,pointOf:(T)->Coordinate,within:Double=250.0):T?{
        val key=hutKey(name)
        return items.filter{hutKey(nameOf(it))==key&&meters(pointOf(it),at)<3000}.minByOrNull{meters(pointOf(it),at)}
            ?:items.filter{meters(pointOf(it),at)<within}.minByOrNull{meters(pointOf(it),at)}
    }
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
    class WalkLine(val guid:String,val meters:Double,val paths:List<List<Coordinate>>)
    fun parseWalkLines(json:String)=features(json).mapNotNull{f->val a=f.getJSONObject("attributes")
        val guid=a.text("EPISERVERGUID").lowercase();if(guid.isEmpty())return@mapNotNull null
        WalkLine(guid,a.optDouble("Shape__Length",0.0).takeIf{it.isFinite()}?:0.0,paths(f.optJSONObject("geometry")))}
    /** The DOC walk line that runs along [track]: the one within [near] metres
     * of the largest share of it (sampled every ~50 m). A walk must cover half
     * the track or 1 km of it; between near-equal shares the shorter, more
     * specific walk wins (a side trip rather than a whole valley's tracks). */
    fun alongTrack(lines:List<WalkLine>,track:List<Coordinate>,near:Double=120.0):WalkLine?{
        val samples=resample(track,50.0,400);if(samples.size<2)return null
        val step=length(track)/(samples.size-1)
        var best:WalkLine?=null;var bestShare=0.0
        for(l in lines){
            val segs=l.paths.filter{it.size>=2};if(segs.isEmpty())continue
            val box=bounds(segs.flatten(),near)!!
            var covered=0
            for(p in samples){if(p.longitude<box[0]||p.longitude>box[2]||p.latitude<box[1]||p.latitude>box[3])continue
                if(segs.any{s->(1 until s.size).any{i->segment(p,s[i-1],s[i]).first<=near}})covered++}
            val share=covered.toDouble()/samples.size
            if(share<.5&&covered*step<1000)continue
            if(best==null||share>bestShare+.05||(share>bestShare-.05&&l.meters<best.meters)){if(share>bestShare)bestShare=share;best=l}
        }
        return best
    }
    /** Points every [spacing] metres along [line] (at most [limit]). */
    fun resample(line:List<Coordinate>,spacing:Double,limit:Int):List<Coordinate>{
        if(line.size<2)return line
        val total=length(line);val step=max(spacing,total/(limit-1));val out=ArrayList<Coordinate>();var next=0.0;var walked=0.0
        for(i in 1 until line.size){val a=line[i-1];val b=line[i];val len=meters(a,b)
            while(next<=walked+len&&len>0){val t=(next-walked)/len;out.add(Coordinate(a.longitude+(b.longitude-a.longitude)*t,a.latitude+(b.latitude-a.latitude)*t));next+=step}
            walked+=len}
        if(out.isEmpty()||out.last()!=line.last())out.add(line.last())
        return out
    }
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
