package nz.ridgeline.nativeapp

import android.graphics.*
import android.graphics.pdf.PdfDocument
import kotlin.math.*

/** One printable page: the framed map fills the page, oriented as it was on
 * screen (rotated maps print rotated). On the map: a north arrow, a scale bar,
 * an optional magnetic grid and the required source credit. No other text. */
internal object PdfLayout {
    /** Page size in points for a paper name, portrait or landscape. */
    fun pageSize(paper:String,landscape:Boolean):Pair<Int,Int>{
        val (w,h)=(RegionFrame.PAPER[paper]?:RegionFrame.PAPER.getValue("A4")).let{(it.first*72/25.4).roundToInt() to (it.second*72/25.4).roundToInt()}
        return if(landscape)h to w else w to h
    }
    /** A 1, 2 or 5 × 10ⁿ distance (km) that fits in [maxKm]. */
    fun scaleKm(maxKm:Double):Double{
        if(maxKm<=0||!maxKm.isFinite())return 0.0
        val p=10.0.pow(floor(log10(maxKm)));val d=maxKm/p
        return p*when{d>=5->5.0;d>=2->2.0;else->1.0}
    }
    fun attribution(base:MapSettings.Base)=when(base){
        MapSettings.Base.SATELLITE->"Esri World Imagery"
        else->"LINZ · CC BY 4.0"
    }
    /** The framed area as printed: centre (Mercator), width (Mercator units
     * along the frame), bearing (degrees, as the map was turned) and its width
     * in metres. [map] covers [plan]'s north-up box around it. */
    data class Frame(val cx:Double,val cy:Double,val width:Double,val height:Double,val bearing:Double,val widthMeters:Double)
    fun render(map:Bitmap,plan:PrintMap.Plan,frame:Frame,paper:String,landscape:Boolean,credit:String,declination:Double?):PdfDocument{
        val (w,h)=pageSize(paper,landscape)
        val doc=PdfDocument();val page=doc.startPage(PdfDocument.PageInfo.Builder(w,h,1).create());val c=page.canvas
        val ink=Color.rgb(35,61,52)
        // Map: Mercator -> page about the frame centre, turned like the screen.
        // A paper frame has the page's shape; a custom one is fitted whole.
        val s=min(w/frame.width,h/frame.height)
        val m=Matrix().apply{
            setScale(((plan.x1-plan.x0)/map.width*s).toFloat(),((plan.y1-plan.y0)/map.height*s).toFloat())
            postTranslate(((plan.x0-frame.cx)*s).toFloat(),((plan.y0-frame.cy)*s).toFloat())
            postRotate(-frame.bearing.toFloat());postTranslate(w/2f,h/2f)
        }
        c.save();c.clipRect(0f,0f,w.toFloat(),h.toFloat())
        c.drawColor(Color.rgb(242,240,232));c.drawBitmap(map,m,Paint(Paint.FILTER_BITMAP_FLAG))
        val metersPerPt=frame.widthMeters/(frame.width*s)
        val km=scaleKm(frame.widthMeters/1000/4)
        // Magnetic grid: lines to magnetic north and across, one scale bar apart.
        if(declination!=null&&km>0){
            val step=(km*1000/metersPerPt).toFloat();val reach=hypot(w.toFloat(),h.toFloat())
            val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(170,24,92,196);strokeWidth=.6f}
            c.save();c.rotate((declination-frame.bearing).toFloat(),w/2f,h/2f)
            var o=0f;while(o<=reach){for(x in listOf(w/2f+o,w/2f-o))c.drawLine(x,h/2f-reach,x,h/2f+reach,grid);for(y in listOf(h/2f+o,h/2f-o))c.drawLine(w/2f-reach,y,w/2f+reach,y,grid);o+=step}
            c.restore()
        }
        c.restore()
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;textSize=9f;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
        val backing=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(225,255,255,255)}
        // North arrow, top right: true north, and magnetic north with the grid.
        val nx=w-34f;val ny=40f
        c.drawCircle(nx,ny,24f,backing)
        fun arrow(angle:Float,length:Float,color:Int,solid:Boolean){
            c.save();c.rotate(angle,nx,ny)
            val p=Path().apply{moveTo(nx,ny-length);lineTo(nx+length*.32f,ny+length*.55f);lineTo(nx,ny+length*.28f);lineTo(nx-length*.32f,ny+length*.55f);close()}
            c.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=if(solid)Paint.Style.FILL else Paint.Style.STROKE;strokeWidth=1.2f})
            c.restore()
        }
        if(declination!=null)arrow((declination-frame.bearing).toFloat(),15f,Color.rgb(24,92,196),false)
        arrow(-frame.bearing.toFloat(),18f,ink,true)
        c.save();c.rotate(-frame.bearing.toFloat(),nx,ny);text.textAlign=Paint.Align.CENTER;text.textSize=10f;c.drawText("N",nx,ny-19.5f,text.apply{color=ink});c.restore()
        if(declination!=null){text.textSize=7f;text.color=Color.rgb(24,92,196);c.drawText(String.format(java.util.Locale.ROOT,"MN %.0f°",abs(declination))+if(declination>=0)" E" else " W",nx,ny+33f,text)}
        // Scale bar, bottom left, on the map.
        if(km>0){
            val len=(km*1000/metersPerPt).toFloat();val x=18f;val y=h-22f
            c.drawRoundRect(RectF(x-7f,y-17f,x+len+46f,y+8f),5f,5f,backing)
            val bar=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;strokeWidth=1.6f}
            c.drawLine(x,y,x+len,y,bar);c.drawLine(x,y-5f,x,y,bar);c.drawLine(x+len,y-5f,x+len,y,bar);c.drawLine(x+len/2,y-3f,x+len/2,y,bar)
            text.textAlign=Paint.Align.LEFT;text.textSize=9f;text.color=ink
            c.drawText(if(km>=1)"${km.roundToInt()} km" else "${(km*1000).roundToInt()} m",x+len+6f,y+1f,text)
        }
        // Source credit (required by the data licence), small, bottom right.
        text.typeface=Typeface.DEFAULT;text.textSize=6.5f;text.textAlign=Paint.Align.RIGHT;text.color=Color.rgb(70,80,74)
        val cw=text.measureText(credit);c.drawRoundRect(RectF(w-14f-cw,h-17f,w-6f,h-6f),3f,3f,backing);c.drawText(credit,w-10f,h-9f,text)
        doc.finishPage(page);return doc
    }
}
