package nz.ridgeline.nativeapp

import android.graphics.*
import android.graphics.pdf.PdfDocument
import kotlin.math.*

/** One printable page: title, the framed map, a scale bar, north and the
 * source credit. Margins match the region frame's page aspect (Capacitor's
 * pageFrame: 30 pt each side, 94 pt in total above and below the map). */
internal object PdfLayout {
    const val SIDE=30f;const val TOP=56f;const val BOTTOM=38f
    /** Page size in points for a paper name, portrait or landscape. */
    fun pageSize(paper:String,landscape:Boolean):Pair<Int,Int>{
        val (w,h)=(RegionFrame.PAPER[paper]?:RegionFrame.PAPER.getValue("A4")).let{(it.first*72/25.4).roundToInt() to (it.second*72/25.4).roundToInt()}
        return if(landscape)h to w else w to h
    }
    /** Where the map goes on the page: the largest rectangle with the image's
     * aspect inside the map area, centred. */
    fun mapRect(pageW:Int,pageH:Int,imageW:Int,imageH:Int):RectF{
        val area=RectF(SIDE,TOP,pageW-SIDE,pageH-BOTTOM)
        val scale=min(area.width()/imageW,area.height()/imageH)
        val w=imageW*scale;val h=imageH*scale
        return RectF(area.centerX()-w/2,area.centerY()-h/2,area.centerX()+w/2,area.centerY()+h/2)
    }
    /** A 1, 2 or 5 × 10ⁿ distance (km) that fits in [maxKm]. */
    fun scaleKm(maxKm:Double):Double{
        if(maxKm<=0||!maxKm.isFinite())return 0.0
        val p=10.0.pow(floor(log10(maxKm)));val d=maxKm/p
        return p*when{d>=5->5.0;d>=2->2.0;else->1.0}
    }
    fun attribution(base:MapSettings.Base)=when(base){
        MapSettings.Base.SATELLITE->"Esri World Imagery and contributors · Ridgeline"
        MapSettings.Base.TOPO->"NZ Topo: LINZ, CC BY 4.0 · Ridgeline"
        else->"Imagery and data: LINZ, CC BY 4.0 · Ridgeline"
    }
    fun render(map:Bitmap,title:String,paper:String,landscape:Boolean,widthKm:Double,credit:String):PdfDocument{
        val (w,h)=pageSize(paper,landscape)
        val doc=PdfDocument();val page=doc.startPage(PdfDocument.PageInfo.Builder(w,h,1).create());val c=page.canvas
        val ink=Color.rgb(35,61,52);val muted=Color.rgb(101,115,105)
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;textSize=16f;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
        c.drawText(title.take(80),SIDE,34f,text)
        text.typeface=Typeface.DEFAULT;text.textSize=8f;text.color=muted;text.textAlign=Paint.Align.RIGHT
        c.drawText(java.text.SimpleDateFormat("d MMMM yyyy",java.util.Locale.getDefault()).format(java.util.Date())+" · $paper",w-SIDE,34f,text)
        val rect=mapRect(w,h,map.width,map.height)
        c.drawBitmap(map,null,rect,Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(rect,Paint().apply{style=Paint.Style.STROKE;color=ink;strokeWidth=.8f})
        // Scale bar: up to a third of the map width.
        val perPoint=widthKm/rect.width()
        val km=scaleKm(perPoint*rect.width()/3)
        val baseline=rect.bottom+18f
        if(km>0){
            val len=(km/perPoint).toFloat()
            val bar=Paint().apply{color=ink;strokeWidth=1.2f}
            c.drawLine(rect.left,baseline,rect.left+len,baseline,bar)
            c.drawLine(rect.left,baseline-4,rect.left,baseline,bar);c.drawLine(rect.left+len,baseline-4,rect.left+len,baseline,bar)
            text.textAlign=Paint.Align.LEFT;text.color=ink
            c.drawText(if(km>=1)"${km.roundToInt()} km" else "${(km*1000).roundToInt()} m",rect.left+len+5,baseline+3,text)
        }
        // North arrow (the map is drawn north-up).
        val nx=rect.right-10f;val ny=rect.top+10f
        c.drawPath(Path().apply{moveTo(nx,ny-6);lineTo(nx+4,ny+5);lineTo(nx,ny+2);lineTo(nx-4,ny+5);close()},Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f})
        c.drawPath(Path().apply{moveTo(nx,ny-6);lineTo(nx+4,ny+5);lineTo(nx,ny+2);lineTo(nx-4,ny+5);close()},Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink})
        text.textAlign=Paint.Align.RIGHT;text.color=muted
        c.drawText(credit,rect.right,baseline+3,text)
        doc.finishPage(page);return doc
    }
}
