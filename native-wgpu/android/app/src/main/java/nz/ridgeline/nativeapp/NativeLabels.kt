package nz.ridgeline.nativeapp

import android.graphics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal data class NativeLabelAtlas(val labels:List<Row>,val width:Int,val height:Int,val rgba:ByteBuffer,val metadata:ByteBuffer,val points:ByteBuffer){
    data class Row(val label:TopographyStyle.Label,val width:Float,val height:Float)
    companion object {
        fun empty()=NativeLabelAtlas(emptyList(),1,1,ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0);rewind()},ByteBuffer.allocateDirect(0))
    }
}
internal data class NativeLayerBatch(val lines:ByteBuffer,val atlas:NativeLabelAtlas)

/** Rasterize native fonts only for changed label data. GPU moves/depth-tests every glyph quad. */
internal object NativeLabels {
    private const val SCALE=2f
    private const val WIDTH=2048
    private data class Packed(val label:TopographyStyle.Label,val text:List<String>,val x:Int,val y:Int,val width:Int,val height:Int)
    fun atlas(input:List<TopographyStyle.Label>):NativeLabelAtlas{
        if(input.isEmpty())return NativeLabelAtlas.empty()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG);val packed=ArrayList<Packed>();var x=2;var y=2;var rowHeight=0
        for(label in input.take(512)){
            paint.textSize=label.size*SCALE;paint.typeface=if(label.bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val lines=if(label.circle)emptyList()else wrap(label.text,paint,label.size*SCALE*9)
            val width=if(label.circle)ceil(label.size*2*SCALE+8).toInt()else ceil((lines.maxOfOrNull{paint.measureText(it)}?:1f)+8*SCALE).toInt()
            val height=if(label.circle)width else ceil(lines.size*label.size*SCALE*1.25+8*SCALE).toInt()
            if(width>WIDTH-4||height>512)continue
            if(x+width>WIDTH){x=2;y+=rowHeight+2;rowHeight=0}
            if(y+height>2048)break
            packed.add(Packed(label,lines,x,y,width,height));x+=width+2;rowHeight=max(rowHeight,height)
        }
        if(packed.isEmpty())return NativeLabelAtlas.empty()
        val h=((y+rowHeight+31)/32*32).coerceAtMost(2048)
        val bitmap=Bitmap.createBitmap(WIDTH,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        val metadata=ByteBuffer.allocateDirect(4+packed.size*40).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(packed.size)}
        val points=ByteBuffer.allocateDirect(packed.size*16).order(ByteOrder.LITTLE_ENDIAN)
        val rows=ArrayList<NativeLabelAtlas.Row>()
        for(p in packed){
            val label=p.label;paint.textSize=label.size*SCALE;paint.typeface=if(label.bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if(label.circle){paint.style=Paint.Style.FILL;paint.color=NativeUi.ink;canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE+1.5f*SCALE,paint);paint.color=label.color;canvas.drawCircle(p.x+p.width/2f,p.y+p.height/2f,label.size*SCALE,paint)}
            else for((i,text)in p.text.withIndex()){
                val tx=p.x+(p.width-paint.measureText(text))/2;val ty=p.y+4*SCALE-paint.fontMetrics.top+i*label.size*SCALE*1.25f
                paint.strokeJoin=Paint.Join.ROUND;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.8f*SCALE;paint.color=0xe0122023.toInt();canvas.drawText(text,tx,ty,paint)
                paint.style=Paint.Style.FILL;paint.color=label.color;canvas.drawText(text,tx,ty,paint)
            }
            metadata.putDouble(label.point.x).putDouble(label.point.y).putFloat(p.x.toFloat()/WIDTH).putFloat(p.y.toFloat()/h).putFloat(p.width.toFloat()/WIDTH).putFloat(p.height.toFloat()/h).putFloat(p.width/SCALE).putFloat(p.height/SCALE)
            points.putDouble(label.point.x).putDouble(label.point.y);rows.add(NativeLabelAtlas.Row(label,p.width/SCALE,p.height/SCALE))
        }
        val pixels=IntArray(WIDTH*h);bitmap.getPixels(pixels,0,WIDTH,0,0,WIDTH,h);bitmap.recycle();metadata.rewind();points.rewind()
        return NativeLabelAtlas(rows,WIDTH,h,PixelPacking.rgbaOwned(pixels),metadata,points)
    }
    private fun wrap(text:String,paint:Paint,maxWidth:Float):List<String>{
        val rows=ArrayList<String>()
        for(paragraph in text.split('\n')){var line="";for(word in paragraph.split(' ')){val next=if(line.isEmpty())word else "$line $word";if(line.isNotEmpty()&&paint.measureText(next)>maxWidth){rows.add(line);line=word}else line=next};if(line.isNotEmpty())rows.add(line)}
        return rows.take(4).ifEmpty{listOf(text.take(60))}
    }
    /** Uniform screen grid avoids pairwise collision checks over every map label. */
    fun place(atlas:NativeLabelAtlas,projected:FloatArray,width:Int,height:Int,scale:Float):ByteBuffer{
        val cells=HashMap<Long,MutableList<RectF>>();val selected=ArrayList<Triple<Int,Float,Float>>()
        val cell=64*scale;val padding=5*scale
        fun keys(rect:RectF):List<Long>{val result=ArrayList<Long>();for(y in floor(rect.top/cell).toInt()..floor(rect.bottom/cell).toInt())for(x in floor(rect.left/cell).toInt()..floor(rect.right/cell).toInt())result.add((x.toLong() shl 32)or(y.toLong() and 0xffffffffL));return result}
        for((i,row)in atlas.labels.withIndex()){
            if(i*3+2>=projected.size||projected[i*3+2]==0f)continue
            val px=projected[i*3];val py=projected[i*3+1];if(px<0||py<0||px>width||py>height)continue
            if(row.label.circle){selected.add(Triple(i,-row.width/2,-row.height/2));continue}
            val anchors=if(row.label.variable)listOf("top","bottom","left","right")else listOf(row.label.anchor)
            for(anchor in anchors){val ox=when(anchor){"left"->row.label.size*.7f;"right"->-row.width-row.label.size*.7f;else->-row.width/2};val oy=when(anchor){"top"->row.label.size*.5f;"bottom"->-row.height-row.label.size*.5f;else->-row.height/2}
                val rect=RectF(px+ox*scale-padding,py+oy*scale-padding,px+(ox+row.width)*scale+padding,py+(oy+row.height)*scale+padding)
                if(rect.left<0||rect.top<0||rect.right>width||rect.bottom>height)continue
                val indexes=keys(rect);if(indexes.any{k->cells[k]?.any{RectF.intersects(it,rect)}==true})continue
                selected.add(Triple(i,ox,oy));for(k in indexes)cells.getOrPut(k){ArrayList()}.add(rect);break
            }
        }
        return ByteBuffer.allocateDirect(4+selected.size*12).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(selected.size);for((i,x,y)in selected){putInt(i);putFloat(x);putFloat(y)};rewind()}
    }
}
