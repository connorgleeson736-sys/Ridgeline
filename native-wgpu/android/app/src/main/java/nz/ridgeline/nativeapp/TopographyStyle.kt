package nz.ridgeline.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Evaluates the actual pinned Capacitor style, rather than duplicating its zoom/filter tables. */
internal class TopographyStyle(json:String){
    data class Line(val points:List<VectorTile.Point>,val color:Int,val width:Float,val dash:Float=0f,val gap:Float=0f,val order:Int=Int.MAX_VALUE)
    data class Label(val point:VectorTile.Point,val text:String,val size:Float,val color:Int,val priority:Double,val bold:Boolean=false,val circle:Boolean=false,val key:String="",val anchor:String="center",val variable:Boolean=false,
        /** Line labels (roads, rivers, tracks): MapLibre's symbol-spacing in px; the
         * same name is not repeated closer than this on screen. 0 for point labels. */
        val spacing:Float=0f,
        /** A DOC walk's id for track names shown as their walk's (see DocWalks). */
        val ref:String="")
    data class Output(val lines:List<Line>,val labels:List<Label>)
    private val specs=JSONArray(json).let{rows->List(rows.length()){rows.getJSONObject(it)}}.onEach{spec->
        // Performance: river names start later than in Capacitor (1.5 zooms for
        // rivers, 1.5 for lake-river polygons). Track names follow Capacitor.
        LATER_NAMES[spec.optString("id")]?.let{spec.put("minzoom",it)}
    }
    companion object{
        /** Label identity grid in world units (2^22 cells, ~10 m at the equator). */
        const val LABEL_GRID=4_194_304.0
        val LATER_NAMES=mapOf("nz-rivers" to 15.5,"nz-river-polygons" to 14.5)
        /** Line-placed river names: one label per name per tile. */
        val ONE_PER_NAME=setOf("nz-rivers","nz-river-polygons")
        /** Track names repeat along the track (MapLibre's symbol-spacing). */
        const val TRACK_LABELS="nz-tracks-label"
        const val HUT_LABELS="nz-huts-label"
        /** Large DOC walks rank with the style's Great Walks. */
        const val WALK_PRIORITY=15.0
        /** At most this many candidates per track name per tile. */
        const val MAX_REPEATS=24
        /** Grid for the across-line coordinate in repeat keys (~0.9 km in NZ). */
        private const val CROSS_GRID=32_768.0
        /** Where [path] crosses the world grid lines of zoom [g] (cells of 512
         * px at camera zoom g), with a key that names the line at its coarsest
         * zoom. Crossings of zoom g's lines are also crossings at g+1, with the
         * same key, so zooming in only adds names and those already placed keep
         * their identity (no reloading) whichever source tile they come from. */
        fun crossings(path:List<VectorTile.Point>,g:Int,limit:Int=MAX_REPEATS):List<Pair<VectorTile.Point,String>>{
            val n=(1L shl g.coerceIn(0,30)).toDouble();val out=ArrayList<Pair<VectorTile.Point,String>>()
            fun line(k:Long):String{var kk=k;var gg=g;while(gg>0&&kk%2==0L){kk/=2;gg--};return "$gg/$kk"}
            for(i in 1 until path.size){val a=path[i-1];val b=path[i]
                for(vertical in booleanArrayOf(true,false)){
                    val a0=if(vertical)a.x else a.y;val b0=if(vertical)b.x else b.y
                    val ka=floor(a0*n).toLong();val kb=floor(b0*n).toLong();if(ka==kb)continue
                    for(k in min(ka,kb)+1..max(ka,kb)){
                        val t=(k/n-a0)/(b0-a0);val p=VectorTile.Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)
                        val across=floor((if(vertical)p.y else p.x)*CROSS_GRID).toLong()
                        out.add(p to "${if(vertical)"v" else "h"}${line(k)}:$across")
                        if(out.size>=limit)return out
                    }
                }
            }
            return out
        }
    }
    private fun truth(value:Any?):Boolean=when(value){null,JSONObject.NULL->false;is Boolean->value;is Number->value.toDouble()!=0.0;is String->value.isNotEmpty();else->true}
    private fun number(value:Any?,fallback:Double=0.0)=(value as? Number)?.toDouble()?:value?.toString()?.toDoubleOrNull()?:fallback
    private fun eval(value:Any?,p:Map<String,Any>,zoom:Double,vars:Map<String,Any?> = emptyMap()):Any?{
        if(value !is JSONArray)return if(value===JSONObject.NULL)null else value
        fun arg(i:Int)=eval(value.opt(i),p,zoom,vars)
        fun num(i:Int)=number(arg(i))
        return when(value.optString(0)){
            "literal"->value.opt(1);"get"->p[value.optString(1)];"has"->p.containsKey(value.optString(1));"zoom"->zoom;"var"->vars[value.optString(1)]
            "let"->{val local=vars.toMutableMap();var i=1;while(i<value.length()-1){local[value.getString(i)]=eval(value.get(i+1),p,zoom,local);i+=2};eval(value.get(value.length()-1),p,zoom,local)}
            "all"->(1 until value.length()).all{truth(arg(it))};"any"->(1 until value.length()).any{truth(arg(it))};"!"->!truth(arg(1))
            "=="->arg(1)==arg(2)||arg(1) is Number&&arg(2) is Number&&num(1)==num(2);"!="->!(arg(1)==arg(2)||arg(1) is Number&&arg(2) is Number&&num(1)==num(2))
            ">="->num(1)>=num(2);"<="->num(1)<=num(2);">"->num(1)>num(2);"<"->num(1)<num(2)
            "in"->{val target=arg(1);val list=arg(2);if(list is JSONArray)(0 until list.length()).any{list.opt(it)==target}else list?.toString()?.contains(target?.toString()?:"")==true}
            "coalesce"->(1 until value.length()).asSequence().map{arg(it)}.firstOrNull{it!=null}
            "to-number"->number(arg(1),number(arg(2)));"to-string"->arg(1)?.let{if(it is Number&&it.toDouble()==floor(it.toDouble()))it.toLong().toString()else it.toString()}?:""
            "concat"->(1 until value.length()).joinToString(""){arg(it)?.toString()?:""};"+"->num(1)+num(2);"/"->num(1)/num(2)
            "case"->{var result:Any?=null;var matched=false;var i=1;while(i<value.length()-1){if(truth(arg(i))){result=arg(i+1);matched=true;break};i+=2};if(matched)result else arg(value.length()-1)}
            "interpolate"->{val input=num(2);var i=3;while(i+2<value.length()&&input>number(value.get(i+2)))i+=2;if(i+2>=value.length())arg(value.length()-1)else{val t=((input-number(value.get(i)))/(number(value.get(i+2))-number(value.get(i)))).coerceIn(0.0,1.0);number(arg(i+1))+(number(arg(i+3))-number(arg(i+1)))*t}}
            else->null
        }
    }
    private val colors=HashMap<String,Int>()
    private fun color(value:Any?,fallback:Int=0xffffffff.toInt()):Int{
        val text=value?.toString()?:return fallback
        return colors.getOrPut(text){runCatching{if(text.startsWith("rgba(")){val p=text.substringAfter('(').substringBefore(')').split(',').map{it.trim().toDouble()};((p[3]*255).roundToInt().coerceIn(0,255) shl 24)or(p[0].toInt().coerceIn(0,255) shl 16)or(p[1].toInt().coerceIn(0,255) shl 8)or p[2].toInt().coerceIn(0,255)}else{require(text.startsWith("#")&&text.length==7);0xff000000.toInt() or text.substring(1).toInt(16)}}.getOrDefault(fallback)}
    }
    private fun group(id:String)=when{ id.startsWith("nz-peaks")->"peaks";"roads" in id->"roads";"tracks" in id->"tracks";"huts" in id->"huts";"contours" in id->"contours";else->"names" }
    fun sourceLayers(state:MapSettings.State,zoom:Double):Set<String> = specs.filter{state.enabled(group(it.getString("id")))&&zoom>=it.optDouble("minzoom",0.0)&&zoom<it.optDouble("maxzoom",30.0)}.map{it.getString("source-layer")}.toSet()
    /** [zoom] is the camera zoom: layer min/max zoom, paint and layout, as in
     * MapLibre. [tileZoom] is the source tile's zoom: MapLibre evaluates
     * ["zoom"] inside filters per tile, so distant coarser tiles in a pitched
     * view omit minor peaks, huts and track names exactly as the Capacitor app does. */
    /** Same name, layer and place (to ~10 m) is the same label whichever source
     * tile it came from. With the feature id and exact point in the key, a hut
     * from the next tile zoom was a new label: it lost its placement and
     * visibly reloaded while zooming. */
    internal fun labelKey(layer:String,text:String,point:VectorTile.Point)="$layer:$text:${floor(point.x*LABEL_GRID).toLong()}:${floor(point.y*LABEL_GRID).toLong()}"
    fun build(features:List<VectorTile.Feature>,state:MapSettings.State,zoom:Double,tileZoom:Double=floor(zoom)):Output{
        val lines=ArrayList<Line>();val labels=ArrayList<Label>();val grouped=features.groupBy{it.layer};val named=HashSet<String>()
        // Track names: one at the middle of each named track per tile (kept at
        // every camera zoom), plus candidates where it crosses the grid.
        val repeats=HashMap<String,Int>();val single=LinkedHashMap<String,Label>()
        for((order,spec) in specs.withIndex()){val id=spec.getString("id");val group=group(id)
            if(!state.enabled(group)||zoom<spec.optDouble("minzoom",0.0)||zoom>=spec.optDouble("maxzoom",30.0))continue
            val kind=spec.getString("type");val paint=spec.getJSONObject("paint");val layout=spec.optJSONObject("layout")?:JSONObject()
            for(f in grouped[spec.getString("source-layer")].orEmpty()){
                // A piece of a large DOC walk is named like a Great Walk (from tile zoom 12).
                val walk=if(id==TRACK_LABELS)f.properties[DocWalks.WALK] as? String else null
                if(!truth(eval(spec.opt("filter")?:true,f.properties,if(walk!=null)max(tileZoom,14.0)else tileZoom)))continue
                if(kind=="line"){
                    val c=color(eval(paint.opt("line-color"),f.properties,zoom));val opacity=number(eval(paint.opt("line-opacity")?:1.0,f.properties,zoom),1.0)
                    val rgba=(c and 0x00ffffff) or (((c ushr 24)*opacity).roundToInt().coerceIn(0,255) shl 24)
                    val width=number(eval(paint.opt("line-width")?:1,f.properties,zoom),1.0).toFloat();val dash=paint.optJSONArray("line-dasharray")
                    for(path in f.paths)if(path.size>=2)lines.add(Line(path,rgba,width,if(dash!=null)dash.optDouble(0).toFloat()*width else 0f,if(dash!=null)dash.optDouble(1).toFloat()*width else 0f,order))
                }else{
                    val path=f.paths.maxByOrNull{it.size}?:continue
                    val own=if(kind=="circle")""else eval(layout.opt("text-field"),f.properties,zoom)?.toString()?.replace("\\n","\n")?.trim().orEmpty()
                    if(kind!="circle"&&own.isBlank())continue
                    // Sections show their walk's name until CHILD_NAMES, then their own.
                    val asWalk=walk!=null&&(zoom<DocWalks.CHILD_NAMES||own==walk)
                    val text=if(asWalk)walk!! else own
                    // A name that is a DOC walk's opens that walk's summary: the walk's
                    // name on a section, or a track that is the walk itself. A section
                    // under its own name opens as a section of the walk.
                    val ref=if(asWalk||walk==null)(f.properties[DocWalks.WALK_ID] as? String).orEmpty()else""
                    val size=if(kind=="circle")number(paint.opt("circle-radius"),4.0).toFloat()else if(asWalk)12f else number(eval(layout.opt("text-size")?:12,f.properties,zoom),12.0).toFloat()
                    val spacing=if(layout.optString("symbol-placement")=="line")layout.optDouble("symbol-spacing",250.0).toFloat()else 0f
                    val c=color(eval(paint.opt(if(kind=="circle")"circle-color"else"text-color"),f.properties,zoom))
                    val priority=if(asWalk)WALK_PRIORITY else number(eval(layout.opt("symbol-sort-key")?:100,f.properties,zoom),100.0)
                    val bold=layout.optJSONArray("text-font")?.toString()?.contains("Bold")==true
                    if(id==TRACK_LABELS&&f.type!=3){
                        for(p in f.paths){if(repeats.getOrDefault(text,0)>=MAX_REPEATS)break
                            for((point,where) in crossings(p,floor(zoom).toInt(),MAX_REPEATS-repeats.getOrDefault(text,0))){
                                if(!named.add(id+"\u0000"+text+"\u0000"+where))continue
                                labels.add(Label(point,text.take(256),size,c,priority,bold,false,"$id:$text:$where",layout.optString("text-anchor","center"),false,spacing,ref))
                                repeats[text]=repeats.getOrDefault(text,0)+1
                            }
                        }
                        if(text !in single)anchor(path)?.let{point->
                            single[text]=Label(point,text.take(256),size,c,priority,bold,false,labelKey(id,text,point),layout.optString("text-anchor","center"),false,spacing,ref)}
                        continue
                    }
                    // Line labels: one candidate per name per tile, however many
                    // pieces the road or river is split into.
                    if((id in ONE_PER_NAME||spacing>0f)&&!named.add(id+"\u0000"+text))continue
                    val point=(if(f.type==3)polygonAnchor(f.paths)else anchor(path))?:continue
                    labels.add(Label(point,text.take(256),size,c,priority,bold,kind=="circle",labelKey(id,text,point),layout.optString("text-anchor","center"),layout.has("text-variable-anchor"),spacing))
                }
            }
        }
        labels.addAll(single.values)
        return Output(lines,labels)
    }
    private fun polygonAnchor(paths:List<List<VectorTile.Point>>):VectorTile.Point?{
        val all=paths.flatten();if(all.isEmpty())return null
        val minY=all.minOf{it.y};val maxY=all.maxOf{it.y};var best:VectorTile.Point?=null;var bestWidth=0.0
        // Scan interior spans across all rings; even/odd intersections preserve holes.
        for(fraction in listOf(.5,.35,.65,.2,.8)){
            val y=minY+(maxY-minY)*fraction;val hits=ArrayList<Double>()
            for(path in paths)for(i in 1 until path.size){val a=path[i-1];val b=path[i];if((a.y>y)!=(b.y>y))hits.add(a.x+(b.x-a.x)*(y-a.y)/(b.y-a.y))}
            hits.sort();for(i in 0 until hits.size-1 step 2){val width=hits[i+1]-hits[i];if(width>bestWidth){bestWidth=width;best=VectorTile.Point((hits[i]+hits[i+1])/2,y)}}
        };return best?:anchor(paths.maxByOrNull{it.size}.orEmpty())
    }
    private fun anchor(path:List<VectorTile.Point>):VectorTile.Point?{
        if(path.isEmpty())return null;if(path.size==1)return path[0]
        var sum=0.0;for(i in 1 until path.size)sum+=hypot(path[i].x-path[i-1].x,path[i].y-path[i-1].y)
        var remaining=sum/2
        for(i in 1 until path.size){val a=path[i-1];val b=path[i];val span=hypot(b.x-a.x,b.y-a.y);if(remaining<=span&&span>0){val t=remaining/span;return VectorTile.Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)};remaining-=span}
        return path[0]
    }
}
