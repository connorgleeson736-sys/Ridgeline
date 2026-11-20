package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Built only for an opened route list; camera and title changes do not retessellate it. */
internal class RouteThumbnailView(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path();private var points=emptyList<Coordinate>();private var start=PointF();private var end=PointF()
    fun data(value:List<Coordinate>){if(points===value)return;points=value;rebuild();invalidate()}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){rebuild()}
    private fun rebuild(){path.reset();if(points.isEmpty())return;val west=points.minOf{it.longitude};val east=points.maxOf{it.longitude};val ys=points.map{asinh(tan(Math.toRadians(it.latitude)))};val south=ys.min();val north=ys.max();val lonSpan=Math.toRadians(east-west);val scale=min((width-12).coerceAtLeast(1)/max(1e-10,lonSpan),(height-12).coerceAtLeast(1)/max(1e-10,north-south));points.forEachIndexed{i,p->val x=(width/2+(Math.toRadians(p.longitude-(west+east)/2))*scale).toFloat();val y=(height/2-(ys[i]-(north+south)/2)*scale).toFloat();if(i==0){path.moveTo(x,y);start=PointF(x,y)}else path.lineTo(x,y);end=PointF(x,y)}}
    // Capacitor thumbnail: rounded pale card (background), rust route line,
    // dark green start dot and orange end dot.
    init{clipToOutline=true}
    override fun onDraw(canvas:Canvas){paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND;paint.color=Color.rgb(196,96,45);canvas.drawPath(path,paint);if(points.isEmpty())return;paint.style=Paint.Style.FILL;paint.color=Color.rgb(72,115,88);canvas.drawCircle(start.x,start.y,3*resources.displayMetrics.density,paint);paint.color=Color.rgb(232,121,68);canvas.drawCircle(end.x,end.y,3*resources.displayMetrics.density,paint)}
}
