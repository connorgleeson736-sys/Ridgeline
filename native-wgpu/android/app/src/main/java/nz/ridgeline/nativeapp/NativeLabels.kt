package nz.ridgeline.nativeapp

import android.graphics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal data class NativeLabelAtlas(val labels:List<Row>,val width:Int,val height:Int,val rgba:ByteBuffer,val metadata:ByteBuffer,val points:ByteBuffer,
    /** Pixel revision of the shared [rgba] buffer (see NativeLabels.changedRows). */
    val revision:Long=0){
    data class Row(val label:TopographyStyle.Label,val width:Float,val height:Float)
    companion object {
        fun empty()=NativeLabelAtlas(emptyList(),1,1,ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0);rewind()},ByteBuffer.allocateDirect(0))
    }
}
/** One source tile's clipped lines in the renderer's packet layout (48-byte
 * header, then the lines). [id] is unique per content: the renderer keeps
 * groups by id, so unchanged tiles are referenced, not re-sent or re-parsed. */
internal class LineGroup(val id:Long,val bytes:ByteArray,val lines:Int){
    companion object{
        private val ids=java.util.concurrent.atomic.AtomicLong(1);fun nextId()=ids.getAndIncrement()
        fun pack(z:Int,x:Int,y:Int,clip:DoubleArray,lines:List<TopographyStyle.Line>):LineGroup{
            val total=lines.sumOf{it.points.size};require(lines.size<=65536&&total<=262144*4){"Visible vector geometry exceeds safe upload budget"}
            val buffer=ByteBuffer.allocate(48+lines.size*24+total*16).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(z).putInt(x).putInt(y);for(v in clip)buffer.putDouble(v);buffer.putInt(lines.size)
            for(line in lines){buffer.putInt(line.points.size).putInt(line.color).putFloat(line.width).putFloat(line.dash).putFloat(line.gap).putInt(line.order);for(p in line.points)buffer.putDouble(p.x).putDouble(p.y)}
            return LineGroup(nextId(),buffer.array(),lines.size)
        }
        /** Body length marking a group the renderer already holds (overlay::REFERENCE).
         * Not 0: a tile with no lines has an empty body. */
        const val REFERENCE=-1
        /** Renderer packet for [groups]: bodies only for groups it does not hold ([held] ids). */
        fun packet(groups:List<LineGroup>,held:Set<Long>):ByteBuffer{
            val out=ByteBuffer.allocateDirect(4+groups.sumOf{60+if(it.id in held)0 else it.bytes.size-48}).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(groups.size)
            for(g in groups){val reference=g.id in held;val body=g.bytes.size-48
                out.put(g.bytes,0,48).putLong(g.id).putInt(if(reference)REFERENCE else body);if(!reference&&body>0)out.put(g.bytes,48,body)}
            out.rewind();return out
        }
    }
}
internal data class NativeLayerBatch(val lines:List<LineGroup>,val atlas:NativeLabelAtlas)

/** Rasterize native fonts only for changed label data. GPU moves/depth-tests every glyph quad. */
internal object NativeLabels {
    private const val SCALE=2f
    private const val WIDTH=2048
    private data class Packed(val label:TopographyStyle.Label,val text:List<String>,val x:Int,val y:Int,val width:Int,val height:Int)
    private data class SpriteKey(val text:String,val size:Float,val color:Int,val bold:Boolean,val circle:Boolean,val font:String,val halo:Int)
    /** Asset fonts by path (the Te Araroa name's lettering). */
    private val fonts=HashMap<String,Typeface?>()
    private var assetManager:android.content.res.AssetManager?=null
    private fun face(label:TopographyStyle.Label):Typeface?=if(label.font.isEmpty())(if(label.bold)bold else regular)
        else fonts.getOrPut(label.font){runCatching{Typeface.createFromAsset(assetManager,label.font)}.getOrNull()}?:regular
    private val sprites=LinkedHashMap<SpriteKey,Packed>()
    private var bitmap:Bitmap?=null
    private var nextX=2;private var nextY=2;private var rowHeight=0
    private var pixels:ByteBuffer?=null
    /** Atlas pixel revisions: [revision, firstRow, endRow], firstRow -1 for a
     * whole new buffer. Newly drawn names change only a few rows, so only
     * those rows are converted and uploaded (was the whole 4-16 MB atlas). */
    private var revision=0L
    private val changes=ArrayDeque<IntArray>()
    private val changeRevisions=ArrayDeque<Long>()
    private fun log(first:Int,end:Int){revision++;changes.addLast(intArrayOf(first,end));changeRevisions.addLast(revision);while(changes.size>64){changes.removeFirst();changeRevisions.removeFirst()}}
    /** Rows of the shared pixel buffer changed between two revisions, as
     * [first, end); empty if none; null if the whole atlas must be sent. */
    @Synchronized fun changedRows(from:Long,to:Long):IntArray?{
        if(to==from)return IntArray(0)
        if(to<from||changeRevisions.isEmpty()||changeRevisions.first()>from+1||changeRevisions.last()<to)return null
        var first=Int.MAX_VALUE;var end=0
        for(i in changes.indices){val r=changeRevisions[i];if(r<=from||r>to)continue;val c=changes[i];if(c[0]<0)return null;first=min(first,c[0]);end=max(end,c[1])}
        return if(end<=first)IntArray(0)else intArrayOf(first,end)
    }
    private var regular:Typeface?=null
    private var bold:Typeface?=null
    @Synchronized fun atlas(input:List<TopographyStyle.Label>,assets:android.content.res.AssetManager):NativeLabelAtlas {
        assetManager=assets
        if(regular==null){regular=Typeface.Builder(assets,"fonts/OpenSans.ttf").setFontVariationSettings("'wght' 400, 'wdth' 100").build();bold=Typeface.Builder(assets,"fonts/OpenSans.ttf").setFontVariationSettings("'wght' 700, 'wdth' 100").build()}
        return buildAtlas(input,true)
    }
    private fun buildAtlas(input:List<TopographyStyle.Label>,canReset:Boolean):NativeLabelAtlas{
        if(input.isEmpty())return NativeLabelAtlas.empty()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val packed=ArrayList<Pair<TopographyStyle.Label,Packed>>();var dirty=false
        var grew=false;var top=Int.MAX_VALUE;var bottom=0
        for(label in input.take(512)){
            val key=SpriteKey(label.text,label.size,label.color,label.bold,label.circle,label.font,label.halo)
            var slot=sprites[key]
            if(slot==null){
                paint.textSize=label.size*SCALE;paint.typeface=face(label);paint.letterSpacing=if(label.font.isEmpty()||label.path.isNotEmpty())0f else .16f
                val lines=if(label.circle)emptyList()else wrap(label.text,paint,label.size*SCALE*9)
                val w=if(label.circle)ceil(label.size*2*SCALE+8).toInt()else ceil((lines.maxOfOrNull{paint.measureText(it)}?:1f)+8*SCALE).toInt()
                val h=if(label.circle)w else ceil(lines.size*label.size*SCALE*1.25+8*SCALE).toInt()
                if(w>WIDTH-4||h>512)continue
                if(nextX+w>WIDTH){nextX=2;nextY+=rowHeight+2;rowHeight=0}
                if(nextY+h>2048){
                    if(canReset){sprites.clear();bitmap?.recycle();bitmap=null;pixels=null;nextX=2;nextY=2;rowHeight=0;return buildAtlas(input,false)}
                    break
                }
                val required=nextY+h
                if(bitmap==null||bitmap!!.height<required){
                    val height=Integer.highestOneBit(max(256,required)-1)*2
                    val grown=Bitmap.createBitmap(WIDTH,height,Bitmap.Config.ARGB_8888)
                    bitmap?.let{Canvas(grown).drawBitmap(it,0f,0f,null);it.recycle()};bitmap=grown;grew=true
                }
                slot=Packed(label,lines,nextX,nextY,w,h);sprites[key]=slot;nextX+=w+2;rowHeight=max(rowHeight,h);dirty=true
                top=min(top,slot.y);bottom=max(bottom,slot.y+h)
                val canvas=Canvas(bitmap!!);val p=slot
                if(label.circle){paint.style=Paint.Style.FILL;paint.color=if(label.halo!=0)label.halo else 0xff233d34.toInt();canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE+1.5f*SCALE,paint);paint.color=label.color;canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE,paint)}
                else for((i,text)in p.text.withIndex()){
                    val tx=p.x+(p.width-paint.measureText(text))/2;val ty=p.y+4*SCALE-paint.fontMetrics.top+i*label.size*SCALE*1.25f
                    paint.strokeJoin=Paint.Join.ROUND;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.8f*SCALE;paint.color=if(label.halo!=0)label.halo else 0xe0122023.toInt();canvas.drawText(text,tx,ty,paint)
                    paint.style=Paint.Style.FILL;paint.color=label.color;canvas.drawText(text,tx,ty,paint)
                }
            }
            packed.add(label to slot)
        }
        if(packed.isEmpty())return NativeLabelAtlas.empty()
        val h=bitmap!!.height
        if(pixels==null||grew||pixels!!.capacity()!=WIDTH*h*4){val rgba=IntArray(WIDTH*h);bitmap!!.getPixels(rgba,0,WIDTH,0,0,WIDTH,h);pixels=PixelPacking.rgbaOwned(rgba);log(-1,-1)}
        else if(dirty&&bottom>top){val n=bottom-top;val rgba=IntArray(WIDTH*n);bitmap!!.getPixels(rgba,0,WIDTH,0,top,WIDTH,n);PixelPacking.rgbaInto(rgba,pixels!!,top*WIDTH*4);log(top,bottom)}
        val metadata=ByteBuffer.allocateDirect(4+packed.size*40).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(packed.size)}
        val points=ByteBuffer.allocateDirect(packed.size*16).order(ByteOrder.LITTLE_ENDIAN);val rows=ArrayList<NativeLabelAtlas.Row>()
        for((label,p)in packed){metadata.putDouble(label.point.x).putDouble(label.point.y).putFloat(p.x.toFloat()/WIDTH).putFloat(p.y.toFloat()/h).putFloat(p.width.toFloat()/WIDTH).putFloat(p.height.toFloat()/h).putFloat(p.width/SCALE).putFloat(p.height/SCALE);points.putDouble(label.point.x).putDouble(label.point.y);rows.add(NativeLabelAtlas.Row(label,p.width/SCALE,p.height/SCALE))}
        metadata.rewind();points.rewind();return NativeLabelAtlas(rows,WIDTH,h,pixels!!,metadata,points,revision)
    }
    private fun wrap(text:String,paint:Paint,maxWidth:Float):List<String>{
        val rows=ArrayList<String>()
        for(paragraph in text.split('\n')){var line="";for(word in paragraph.split(' ')){val next=if(line.isEmpty())word else "$line $word";if(line.isNotEmpty()&&paint.measureText(next)>maxWidth){rows.add(line);line=word}else line=next};if(line.isNotEmpty())rows.add(line)}
        return rows.take(4).ifEmpty{listOf(text.take(60))}
    }
    /** Uniform screen grid avoids pairwise collision checks over every map label. */
    /** Screen box for collisions (plain floats: no framework object per test). */
    private class Box(val left:Float,val top:Float,val right:Float,val bottom:Float){
        fun intersects(o:Box)=left<o.right&&o.left<right&&top<o.bottom&&o.top<bottom
    }
    /** Labels shown by the previous placement and their anchors. */
    private var shown=HashMap<String,String>()
    /** Collision placement by importance, with hysteresis among equals: labels
     * already on screen go first within their priority, in their previous
     * anchor, so moving the map does not reshuffle which names are visible.
     * Labels hidden by terrain arrive unprojected and take no space. */
    /** One placed sprite: its row, its offset from its anchor (dp) and its turn (radians). */
    internal class Placed(val index:Int,val x:Float,val y:Float,val angle:Float=0f)
    /** Placement entries: row (int), offset x and y (dp), turn (radians, clockwise on screen). */
    const val ENTRY=16
    fun place(atlas:NativeLabelAtlas,projected:FloatArray,width:Int,height:Int,scale:Float):ByteBuffer{
        val cells=HashMap<Long,MutableList<Box>>();val selected=ArrayList<Placed>()
        val next=HashMap<String,String>()
        val cell=64*scale;val padding=5*scale
        // Placed line labels by name: a road or river name is not repeated
        // closer than its symbol-spacing on screen (MapLibre spaces them along the line).
        val named=HashMap<String,ArrayList<Float>>()
        fun keys(rect:Box):List<Long>{val result=ArrayList<Long>();for(y in floor(rect.top/cell).toInt()..floor(rect.bottom/cell).toInt())for(x in floor(rect.left/cell).toInt()..floor(rect.right/cell).toInt())result.add((x.toLong() shl 32)or(y.toLong() and 0xffffffffL));return result}
        // Names along curves first (the Te Araroa name: few, and the most important),
        // their letters taking space as any name does.
        for(curve in curves(atlas,projected,width,height,scale)){
            val boxes=curve.second;if(boxes.any{b->keys(b).any{k->cells[k]?.any{it.intersects(b)}==true}})continue
            selected.addAll(curve.first);for(b in boxes)for(k in keys(b))cells.getOrPut(k){ArrayList()}.add(b)
        }
        // More important names first (MapLibre's symbol-sort-key); among equally
        // important ones, those already shown keep their place (no reshuffling).
        val order=atlas.labels.indices.sortedWith(compareBy<Int>{atlas.labels[it].label.priority}.thenBy{if(atlas.labels[it].label.key in shown)0 else 1})
        for(i in order){val row=atlas.labels[i]
            if(row.label.path.isNotEmpty())continue
            if(i*3+2>=projected.size||projected[i*3+2]==0f)continue
            val px=projected[i*3];val py=projected[i*3+1];if(px<0||py<0||px>width||py>height)continue
            if(row.label.circle){selected.add(Placed(i,-row.width/2,-row.height/2));next[row.label.key]="circle";continue}
            val spacing=row.label.spacing*scale
            if(spacing>0f&&named[row.label.text]?.let{p->(p.indices step 2).any{k->val dx=p[k]-px;val dy=p[k+1]-py;dx*dx+dy*dy<spacing*spacing}}==true)continue
            val previous=shown[row.label.key]
            val anchors=if(row.label.variable)listOf("top","bottom","left","right").sortedBy{if(it==previous)0 else 1}else listOf(row.label.anchor)
            for(anchor in anchors){val ox=when(anchor){"left"->row.label.size*.7f;"right"->-row.width-row.label.size*.7f;else->-row.width/2};val oy=when(anchor){"top"->row.label.size*.5f;"bottom"->-row.height-row.label.size*.5f;else->-row.height/2}
                val rect=Box(px+ox*scale-padding,py+oy*scale-padding,px+(ox+row.width)*scale+padding,py+(oy+row.height)*scale+padding)
                if(rect.left<0||rect.top<0||rect.right>width||rect.bottom>height)continue
                val indexes=keys(rect);if(indexes.any{k->cells[k]?.any{it.intersects(rect)}==true})continue
                selected.add(Placed(i,ox,oy));next[row.label.key]=anchor;if(spacing>0f)named.getOrPut(row.label.text){ArrayList()}.apply{add(px);add(py)};for(k in indexes)cells.getOrPut(k){ArrayList()}.add(rect);break
            }
        }
        shown=next
        return ByteBuffer.allocateDirect(4+selected.size*ENTRY).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(selected.size);for(p in selected){putInt(p.index);putFloat(p.x);putFloat(p.y);putFloat(p.angle)};rewind()}
    }
    /** Names along curves in an atlas: per curve, its points' rows in order and its letters' rows. */
    internal class Curves(val curves:List<Pair<IntArray,IntArray>>,val inputs:List<ByteBuffer>,val outputs:List<ByteBuffer>)
    fun curves(atlas:NativeLabelAtlas):Curves?{
        val vertices=HashMap<String,ArrayList<Pair<Int,Int>>>();val glyphs=HashMap<String,ArrayList<Int>>()
        for((i,row) in atlas.labels.withIndex()){val l=row.label;if(l.path.isEmpty())continue
            if(l.vertex>=0)vertices.getOrPut(l.path){ArrayList()}.add(l.vertex to i)else if(l.glyph>=0)glyphs.getOrPut(l.path){ArrayList()}.add(i)}
        val curves=vertices.mapNotNull{(path,v)->val g=glyphs[path]?:return@mapNotNull null;v.sortBy{it.first};v.map{it.second}.toIntArray() to g.toIntArray()}
        if(curves.isEmpty())return null
        val inputs=curves.map{(v,_)->ByteBuffer.allocateDirect(v.size*16).order(ByteOrder.LITTLE_ENDIAN).apply{for(i in v){val p=atlas.labels[i].label.point;putDouble(p.x).putDouble(p.y)};rewind()}}
        return Curves(curves,inputs,curves.map{(v,_)->ByteBuffer.allocateDirect(v.size*12).order(ByteOrder.LITTLE_ENDIAN)})
    }
    /** The placement [base] with its names along curves laid out again for the
     * camera now: [project] gives a curve's points on screen (x, y, a flag, per
     * point; points without terrain yet included, NativeBridge.projectCurve), so
     * the name stays at its curve's middle and follows it every frame, as
     * MapLibre's line-center names do. */
    fun withCurves(atlas:NativeLabelAtlas,set:Curves,base:ByteBuffer,project:(ByteBuffer,ByteBuffer)->Int,width:Int,height:Int,scale:Float):ByteBuffer{
        val projected=FloatArray(atlas.labels.size*3)
        project(atlas,set,projected,project)
        val placed=curves(atlas,projected,width,height,scale).flatMap{it.first}
        val b=base.duplicate().order(ByteOrder.LITTLE_ENDIAN);b.rewind();val n=b.int
        val kept=ArrayList<Placed>(n+placed.size)
        repeat(n){val i=b.int;val x=b.float;val y=b.float;val a=b.float;if(atlas.labels.getOrNull(i)?.label?.path.isNullOrEmpty())kept.add(Placed(i,x,y,a))}
        kept.addAll(placed)
        return ByteBuffer.allocateDirect(4+kept.size*ENTRY).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(kept.size);for(p in kept){putInt(p.index);putFloat(p.x);putFloat(p.y);putFloat(p.angle)};rewind()}
    }
    /** Writes the curves' points and letters' anchors into [projected] (by row). */
    fun project(atlas:NativeLabelAtlas,set:Curves,projected:FloatArray,project:(ByteBuffer,ByteBuffer)->Int){
        for((k,curve) in set.curves.withIndex()){
            val (v,g)=curve;val input=set.inputs[k].apply{rewind()};val output=set.outputs[k].apply{clear()}
            if(project(input,output)!=v.size)continue;output.rewind()
            for(i in v){projected[i*3]=output.float;projected[i*3+1]=output.float;projected[i*3+2]=output.float}
            // The letters' shared anchor is the curve's middle point.
            val mid=v[v.size/2];for(i in g){projected[i*3]=projected[mid*3];projected[i*3+1]=projected[mid*3+1];projected[i*3+2]=projected[mid*3+2]}
        }
    }
    /** Names written along curves (MapLibre's line-center placement): the curve's
     * points as projected now, the letters laid along its middle, each turned to
     * the curve and kept upright, the whole name a little to the curve's left (above
     * a line read left to right). A name is left out when its curve is off screen,
     * too short for it, or bends too sharply between two letters. Each result: the
     * letters' entries and their collision boxes. */
    /** Which way each curve's name last read (true: from the curve's end), kept until it clearly turns. */
    private val readsBack=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private fun curves(atlas:NativeLabelAtlas,projected:FloatArray,width:Int,height:Int,scale:Float):List<Pair<List<Placed>,List<Box>>>{
        val vertices=HashMap<String,ArrayList<Pair<Int,Int>>>();val glyphs=HashMap<String,ArrayList<Pair<Int,Int>>>()
        for((i,row) in atlas.labels.withIndex()){val l=row.label;if(l.path.isEmpty())continue
            if(l.vertex>=0)vertices.getOrPut(l.path){ArrayList()}.add(l.vertex to i)else if(l.glyph>=0)glyphs.getOrPut(l.path){ArrayList()}.add(l.glyph to i)}
        val out=ArrayList<Pair<List<Placed>,List<Box>>>()
        for((path,letters) in glyphs){
            val all=vertices[path]?.sortedBy{it.first}?.map{it.second}?:continue
            if(all.size<2)continue
            fun seen(i:Int)=i*3+2<projected.size&&projected[i*3+2]!=0f
            // The name's centre is the curve's middle on the ground (by length on the
            // map, as MapLibre's line-center), fixed there whatever the camera does.
            val geo=FloatArray(all.size);for(k in 1 until all.size){val p=atlas.labels[all[k-1]].label.point;val q=atlas.labels[all[k]].label.point;geo[k]=geo[k-1]+hypot(q.x-p.x,q.y-p.y).toFloat()}
            var m=1;while(m<all.size-1&&geo[m]<geo.last()/2)m++
            if(!seen(all[m-1])||!seen(all[m]))continue
            val mf=((geo.last()/2-geo[m-1])/max(1e-12f,geo[m]-geo[m-1])).coerceIn(0f,1f)
            // The unbroken stretch of the curve on screen through its middle: points
            // hidden elsewhere do not bend it or move the name along it.
            var lo=m-1;while(lo>0&&seen(all[lo-1]))lo--
            var hi=m;while(hi<all.size-1&&seen(all[hi+1]))hi++
            val shown=all.subList(lo,hi+1)
            val points=shown.map{floatArrayOf(projected[it*3],projected[it*3+1])}
            if(points.size<2)continue
            val midPoint=floatArrayOf(projected[all[m-1]*3]+(projected[all[m]*3]-projected[all[m-1]*3])*mf,projected[all[m-1]*3+1]+(projected[all[m]*3+1]-projected[all[m-1]*3+1])*mf)
            // Its distance along the curve as seen (from the first point shown).
            var midRun=0f;run{val before=shown.indexOf(all[m-1]);for(k in 1..before)midRun+=hypot(points[k][0]-points[k-1][0],points[k][1]-points[k-1][1]);midRun+=hypot(midPoint[0]-points[before][0],midPoint[1]-points[before][1])}
            letters.sortBy{it.first}
            val rows=letters.map{atlas.labels[it.second]}
            val anchor=letters.first().second;if(anchor*3+2>=projected.size||projected[anchor*3+2]==0f)continue
            val ax=projected[anchor*3];val ay=projected[anchor*3+1]
            // Letter advances on screen (a sprite is its text and 4 dp each side), and tracking.
            val size=rows.first().label.size
            val advances=rows.map{max(0f,it.width-8f)*scale};val tracking=size*.2f*scale
            val total=advances.sum()+tracking*(rows.size-1)
            // Read left to right: decided by the stretch the name covers, not the
            // curve's ends, and only changed when it clearly runs the other way, so the
            // name stays put on the map until it has to turn to stay upright.
            val forward=FloatArray(points.size);for(k in 1 until points.size)forward[k]=forward[k-1]+hypot(points[k][0]-points[k-1][0],points[k][1]-points[k-1][1])
            fun along(d:Float):FloatArray{val s=d.coerceIn(0f,forward.last());var k=1;while(k<points.size-1&&forward[k]<s)k++
                val span=max(1e-3f,forward[k]-forward[k-1]);val f=(s-forward[k-1])/span
                return floatArrayOf(points[k-1][0]+(points[k][0]-points[k-1][0])*f,points[k-1][1]+(points[k][1]-points[k-1][1])*f)}
            val rightwards=along(midRun+total/2)[0]-along(midRun-total/2)[0]
            val reversed=when{rightwards< -size*scale->true;rightwards>size*scale->false;else->readsBack[path]?:(rightwards<0)}
            readsBack[path]=reversed
            val line=if(reversed)points.reversed()else points
            val run=FloatArray(line.size);for(k in 1 until line.size)run[k]=run[k-1]+hypot(line[k][0]-line[k-1][0],line[k][1]-line[k-1][1])
            val length=run.last();if(length<total+size*scale)continue
            fun at(d:Float):FloatArray{val s=d.coerceIn(0f,length);var k=1;while(k<line.size-1&&run[k]<s)k++
                val span=max(1e-3f,run[k]-run[k-1]);val f=(s-run[k-1])/span
                return floatArrayOf(line[k-1][0]+(line[k][0]-line[k-1][0])*f,line[k-1][1]+(line[k][1]-line[k-1][1])*f)}
            val centre=if(reversed)length-midRun else midRun
            var d=centre-total/2;var previous=Float.NaN;var ok=true
            if(d<0f||d+total>length)continue
            val placed=ArrayList<Placed>();val boxes=ArrayList<Box>()
            val lift=size*.75f*scale
            for((k,row) in rows.withIndex()){
                val half=advances[k]/2;val a=at(d-1f);val b=at(d+advances[k]+1f);val c=at(d+half)
                val angle=atan2(b[1]-a[1],b[0]-a[0])
                if(!previous.isNaN()){var turn=abs(angle-previous);if(turn>PI)turn=(2*PI).toFloat()-turn;if(turn>.7f){ok=false;break}}
                if(advances[k]>0f)previous=angle
                // Above the curve: its left side as read.
                val cx=c[0]+sin(angle)*lift;val cy=c[1]-cos(angle)*lift
                if(cx<0||cy<0||cx>width||cy>height){ok=false;break}
                val h=row.height*scale
                placed.add(Placed(letters[k].second,(cx-ax)/scale-row.width/2,(cy-ay)/scale-row.height/2,angle))
                val hx=abs(cos(angle))*advances[k]/2+abs(sin(angle))*h*.35f;val hy=abs(sin(angle))*advances[k]/2+abs(cos(angle))*h*.35f
                if(advances[k]>0f)boxes.add(Box(cx-hx,cy-hy,cx+hx,cy+hy))
                d+=advances[k]+tracking
            }
            if(ok&&placed.isNotEmpty())out.add(placed to boxes)
        }
        return out
    }
}
