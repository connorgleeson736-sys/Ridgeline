package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
/** Cached vector chart; redraws only for new data, size or explicit inspection. */
class ElevationProfileView(context:Context):View(context){
    private var samples=emptyList<NativeGeo.ProfilePoint>()
    private val path=Path();private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var hasHeights=false
    private var low=0.0;private var high=1.0;private var cursor=-1
    var onInspect:(NativeGeo.ProfilePoint,Boolean)->Unit={_,_->}
    fun data(value:List<NativeGeo.ProfilePoint>){samples=value;cursor=-1;rebuild();invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){rebuild()}
    private fun rebuild(){
        val hs=samples.mapNotNull{it.height};hasHeights=hs.isNotEmpty();low=hs.minOrNull()?:0.0;high=max(low+1,(hs.maxOrNull()?:1.0))
        path.reset();var pen=false;val length=samples.lastOrNull()?.distance?:return
        for(p in samples){val height=p.height;if(height==null){pen=false;continue};val x=(p.distance/max(1.0,length)*width).toFloat()
            val py=(this.height.toDouble()-(height-low)/(high-low)*(this.height-20)-10).toFloat()
            if(pen)path.lineTo(x,py)else path.moveTo(x,py);pen=true
        }
    }
    override fun onDraw(canvas:Canvas){
        canvas.drawColor(Color.rgb(246,248,245));paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density;paint.color=Color.rgb(38,105,79);canvas.drawPath(path,paint)
        if(cursor in samples.indices){val length=samples.lastOrNull()?.distance?:1.0;val x=(samples[cursor].distance/max(1.0,length)*width).toFloat();paint.color=Color.rgb(192,100,20);canvas.drawLine(x,0f,x,height.toFloat(),paint)}
        paint.style=Paint.Style.FILL;paint.textSize=10*resources.displayMetrics.scaledDensity;paint.color=Color.DKGRAY;if(hasHeights)canvas.drawText("${high.roundToInt()} m",4f,paint.textSize,paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(samples.isEmpty()||width==0)return false
        parent.requestDisallowInterceptTouchEvent(true)
        val d=(e.x/width).coerceIn(0f,1f)*(samples.last().distance)
        var lo=0;var hi=samples.lastIndex
        while(lo<hi){val mid=(lo+hi)/2;if(samples[mid].distance<d)lo=mid+1 else hi=mid}
        if(cursor!=lo||e.actionMasked==MotionEvent.ACTION_UP){cursor=lo;onInspect(samples[lo],e.actionMasked==MotionEvent.ACTION_UP);invalidate()}
        return true
    }
}
