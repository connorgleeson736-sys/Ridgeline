package nz.ridgeline.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Evaluates the actual pinned Capacitor style, rather than duplicating its zoom/filter tables. */
internal class TopographyStyle(json:String){
    data class Line(val points:List<VectorTile.Point>,val color:Int,val width:Float,val dash:Float=0f,val gap:Float=0f,val order:Int=Int.MAX_VALUE)
    data class Label(val point:VectorTile.Point,val text:String,val size:Float,val color:Int,val priority:Double,val bold:Boolean=false,val circle:Boolean=false,val key:String="",val anchor:String="center",val variable:Boolean=false)
    data class Output(val lines:List<Line>,val labels:List<Label>)
    private val specs=JSONArray(json).let{rows->List(rows.length()){rows.getJSONObject(it)}}
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
    fun build(features:List<VectorTile.Feature>,state:MapSettings.State,zoom:Double,tileZoom:Double=floor(zoom)):Output{
        val lines=ArrayList<Line>();val labels=ArrayList<Label>();val grouped=features.groupBy{it.layer}
        for((order,spec) in specs.withIndex()){val id=spec.getString("id");val group=group(id)
            if(!state.enabled(group)||zoom<spec.optDouble("minzoom",0.0)||zoom>=spec.optDouble("maxzoom",30.0))continue
            val kind=spec.getString("type");val paint=spec.getJSONObject("paint");val layout=spec.optJSONObject("layout")?:JSONObject()
            for(f in grouped[spec.getString("source-layer")].orEmpty()){
                if(!truth(eval(spec.opt("filter")?:true,f.properties,tileZoom)))continue
                if(kind=="line"){
                    val c=color(eval(paint.opt("line-color"),f.properties,zoom));val opacity=number(eval(paint.opt("line-opacity")?:1.0,f.properties,zoom),1.0)
                    val rgba=(c and 0x00ffffff) or (((c ushr 24)*opacity).roundToInt().coerceIn(0,255) shl 24)
                    val width=number(eval(paint.opt("line-width")?:1,f.properties,zoom),1.0).toFloat();val dash=paint.optJSONArray("line-dasharray")
                    for(path in f.paths)if(path.size>=2)lines.add(Line(path,rgba,width,if(dash!=null)dash.optDouble(0).toFloat()*width else 0f,if(dash!=null)dash.optDouble(1).toFloat()*width else 0f,order))
                }else{
                    val path=f.paths.maxByOrNull{it.size}?:continue;val point=(if(f.type==3)polygonAnchor(f.paths)else anchor(path))?:continue
                    val text=if(kind=="circle")""else eval(layout.opt("text-field"),f.properties,zoom)?.toString()?.replace("\\n","\n")?.trim().orEmpty()
                    if(kind!="circle"&&text.isBlank())continue
                    val size=if(kind=="circle")number(paint.opt("circle-radius"),4.0).toFloat()else number(eval(layout.opt("text-size")?:12,f.properties,zoom),12.0).toFloat()
                    val c=color(eval(paint.opt(if(kind=="circle")"circle-color"else"text-color"),f.properties,zoom))
                    val priority=number(eval(layout.opt("symbol-sort-key")?:100,f.properties,zoom),100.0)
                    val bold=layout.optJSONArray("text-font")?.toString()?.contains("Bold")==true
                    labels.add(Label(point,text.take(256),size,c,priority,bold,kind=="circle","$id:${f.id}:${point.x}:${point.y}:$text",layout.optString("text-anchor","center"),layout.has("text-variable-anchor")))
                }
            }
        };return Output(lines,labels)
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
