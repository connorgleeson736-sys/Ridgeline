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
    companion object{private val ids=java.util.concurrent.atomic.AtomicLong(1);fun nextId()=ids.getAndIncrement()}
}
internal data class NativeLayerBatch(val lines:List<LineGroup>,val atlas:NativeLabelAtlas)

/** Rasterize native fonts only for changed label data. GPU moves/depth-tests every glyph quad. */
internal object NativeLabels {
    private const val SCALE=2f
    private const val WIDTH=2048
    private data class Packed(val label:TopographyStyle.Label,val text:List<String>,val x:Int,val y:Int,val width:Int,val height:Int)
    private data class SpriteKey(val text:String,val size:Float,val color:Int,val bold:Boolean,val circle:Boolean)
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
        if(regular==null){regular=Typeface.Builder(assets,"fonts/OpenSans.ttf").setFontVariationSettings("'wght' 400, 'wdth' 100").build();bold=Typeface.Builder(assets,"fonts/OpenSans.ttf").setFontVariationSettings("'wght' 700, 'wdth' 100").build()}
        return buildAtlas(input,true)
    }
    private fun buildAtlas(input:List<TopographyStyle.Label>,canReset:Boolean):NativeLabelAtlas{
        if(input.isEmpty())return NativeLabelAtlas.empty()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val packed=ArrayList<Pair<TopographyStyle.Label,Packed>>();var dirty=false
        var grew=false;var top=Int.MAX_VALUE;var bottom=0
        for(label in input.take(512)){
            val key=SpriteKey(label.text,label.size,label.color,label.bold,label.circle)
            var slot=sprites[key]
            if(slot==null){
                paint.textSize=label.size*SCALE;paint.typeface=if(label.bold)bold else regular
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
                    val height=Integer.highestOneBit(max(128,required)-1)*2
                    val grown=Bitmap.createBitmap(WIDTH,height,Bitmap.Config.ARGB_8888)
                    bitmap?.let{Canvas(grown).drawBitmap(it,0f,0f,null);it.recycle()};bitmap=grown;grew=true
                }
                slot=Packed(label,lines,nextX,nextY,w,h);sprites[key]=slot;nextX+=w+2;rowHeight=max(rowHeight,h);dirty=true
                top=min(top,slot.y);bottom=max(bottom,slot.y+h)
                val canvas=Canvas(bitmap!!);val p=slot
                if(label.circle){paint.style=Paint.Style.FILL;paint.color=0xff233d34.toInt();canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE+1.5f*SCALE,paint);paint.color=label.color;canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE,paint)}
                else for((i,text)in p.text.withIndex()){
                    val tx=p.x+(p.width-paint.measureText(text))/2;val ty=p.y+4*SCALE-paint.fontMetrics.top+i*label.size*SCALE*1.25f
                    paint.strokeJoin=Paint.Join.ROUND;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.8f*SCALE;paint.color=0xe0122023.toInt();canvas.drawText(text,tx,ty,paint)
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
    fun place(atlas:NativeLabelAtlas,projected:FloatArray,width:Int,height:Int,scale:Float):ByteBuffer{
        val cells=HashMap<Long,MutableList<Box>>();val selected=ArrayList<Triple<Int,Float,Float>>()
        val next=HashMap<String,String>()
        val cell=64*scale;val padding=5*scale
        // Placed line labels by name: a road or river name is not repeated
        // closer than its symbol-spacing on screen (MapLibre spaces them along the line).
        val named=HashMap<String,ArrayList<Float>>()
        fun keys(rect:Box):List<Long>{val result=ArrayList<Long>();for(y in floor(rect.top/cell).toInt()..floor(rect.bottom/cell).toInt())for(x in floor(rect.left/cell).toInt()..floor(rect.right/cell).toInt())result.add((x.toLong() shl 32)or(y.toLong() and 0xffffffffL));return result}
        // More important names first (MapLibre's symbol-sort-key); among equally
        // important ones, those already shown keep their place (no reshuffling).
        val order=atlas.labels.indices.sortedWith(compareBy<Int>{atlas.labels[it].label.priority}.thenBy{if(atlas.labels[it].label.key in shown)0 else 1})
        for(i in order){val row=atlas.labels[i]
            if(i*3+2>=projected.size||projected[i*3+2]==0f)continue
            val px=projected[i*3];val py=projected[i*3+1];if(px<0||py<0||px>width||py>height)continue
            if(row.label.circle){selected.add(Triple(i,-row.width/2,-row.height/2));next[row.label.key]="circle";continue}
            val spacing=row.label.spacing*scale
            if(spacing>0f&&named[row.label.text]?.let{p->(p.indices step 2).any{k->val dx=p[k]-px;val dy=p[k+1]-py;dx*dx+dy*dy<spacing*spacing}}==true)continue
            val previous=shown[row.label.key]
            val anchors=if(row.label.variable)listOf("top","bottom","left","right").sortedBy{if(it==previous)0 else 1}else listOf(row.label.anchor)
            for(anchor in anchors){val ox=when(anchor){"left"->row.label.size*.7f;"right"->-row.width-row.label.size*.7f;else->-row.width/2};val oy=when(anchor){"top"->row.label.size*.5f;"bottom"->-row.height-row.label.size*.5f;else->-row.height/2}
                val rect=Box(px+ox*scale-padding,py+oy*scale-padding,px+(ox+row.width)*scale+padding,py+(oy+row.height)*scale+padding)
                if(rect.left<0||rect.top<0||rect.right>width||rect.bottom>height)continue
                val indexes=keys(rect);if(indexes.any{k->cells[k]?.any{it.intersects(rect)}==true})continue
                selected.add(Triple(i,ox,oy));next[row.label.key]=anchor;if(spacing>0f)named.getOrPut(row.label.text){ArrayList()}.apply{add(px);add(py)};for(k in indexes)cells.getOrPut(k){ArrayList()}.add(rect);break
            }
        }
        shown=next
        return ByteBuffer.allocateDirect(4+selected.size*12).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(selected.size);for((i,x,y)in selected){putInt(i);putFloat(x);putFloat(y)};rewind()}
    }
}
