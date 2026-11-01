package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Capacitor chart semantics: inspect a point, drag a section, preserve missing gaps. */
class ElevationProfileView(context:Context):View(context){
    private var samples=emptyList<NativeGeo.ProfilePoint>()
    private val path=Path();private val fill=Path();private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var low=0.0;private var high=1.0;private var cursor:NativeGeo.ProfilePoint?=null
    private var start:Double?=null;private var range:Pair<Double,Double>?=null;private var downX=0f;private var moved=false
    private val density=resources.displayMetrics.density
    private val left get()=44*density
    private val right get()=max(left+1,width-12*density)
    private val top get()=12*density
    private val bottom get()=max(top+1,height-25*density)
    var onInspect:(NativeGeo.ProfilePoint,Boolean)->Unit={_,_->}
    var onRange:(NativeGeo.Section?)->Unit={}
    var onLeave:()->Unit={}
    init{isFocusable=true;contentDescription="Route elevation profile. Drag for section statistics; arrow keys inspect samples."}
    fun data(value:List<NativeGeo.ProfilePoint>){
        val sameGeometry=samples.size==value.size&&samples.indices.all{samples[it].point==value[it].point&&samples[it].distance==value[it].distance}
        val distance=cursor?.distance;samples=value
        if(sameGeometry)cursor=distance?.let{NativeGeo.profileAt(value,it)}else{cursor=null;range=null;start=null}
        rebuild();invalidate()
    }
    fun refreshInspection(){cursor?.let{onInspect(it,false)};range?.let{onRange(NativeGeo.section(samples,it.first,it.second))}}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){rebuild()}
    private fun x(d:Double)=(left+d/max(1.0,samples.lastOrNull()?.distance?:1.0)*(right-left)).toFloat()
    private fun y(h:Double)=(bottom-(h-low)/(high-low)*(bottom-top)).toFloat()
    private fun rebuild(){
        val hs=samples.mapNotNull{it.height};val min=hs.minOrNull()?:0.0;val max=hs.maxOrNull()?:1.0
        val pad=max(15.0,(max-min)*.08);low=floor((min-pad)/10)*10;high=ceil((max+pad)/10)*10
        if(high<=low)high=low+30
        path.reset();fill.reset();var pen=false;var lastX=0f
        for(p in samples){val h=p.height
            if(h==null){if(pen){fill.lineTo(lastX,bottom);fill.close()};pen=false;continue}
            val px=x(p.distance);val py=y(h)
            if(pen){path.lineTo(px,py);fill.lineTo(px,py)}else{path.moveTo(px,py);fill.moveTo(px,bottom);fill.lineTo(px,py)}
            lastX=px;pen=true
        };if(pen){fill.lineTo(lastX,bottom);fill.close()}
    }
    override fun onDraw(canvas:Canvas){
        canvas.drawColor(NativeUi.paper);paint.style=Paint.Style.FILL;paint.textSize=10*resources.displayMetrics.scaledDensity;paint.color=NativeUi.muted
        for(i in 0..2){val h=low+(high-low)*i/2;val yy=y(h);paint.color=NativeUi.line;paint.strokeWidth=density;canvas.drawLine(left,yy,right,yy,paint);paint.color=NativeUi.muted;canvas.drawText("${h.roundToInt()}",2*density,yy+3*density,paint)}
        val total=samples.lastOrNull()?.distance?:0.0
        for(i in 0..2){val d=total*i/2;val label=if(total>=1000)String.format(java.util.Locale.ROOT,"%.1f km",d/1000)else"${d.roundToInt()} m";canvas.drawText(label,(x(d)-paint.measureText(label)/2).coerceIn(0f,width-paint.measureText(label)),height-5*density,paint)}
        paint.color=0x22e87944;canvas.drawPath(fill,paint)
        range?.let{val save=canvas.save();canvas.clipRect(x(min(it.first,it.second)),top,x(max(it.first,it.second)),bottom);paint.color=0x66587d62;canvas.drawPath(fill,paint);canvas.restoreToCount(save)}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=NativeUi.orange;canvas.drawPath(path,paint)
        cursor?.let{p->paint.color=NativeUi.ink;paint.strokeWidth=density;canvas.drawLine(x(p.distance),top,x(p.distance),bottom,paint);p.height?.let{h->paint.style=Paint.Style.FILL;canvas.drawCircle(x(p.distance),y(h),3*density,paint)}}
    }
    private fun inspect(d:Double){cursor=NativeGeo.profileAt(samples,d);cursor?.let{onInspect(it,false)};invalidate()}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(samples.size<2||width==0)return false
        val d=((e.x-left)/(right-left)).coerceIn(0f,1f)*samples.last().distance
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent.requestDisallowInterceptTouchEvent(true);start=d;downX=e.x;moved=false;range=null;onRange(null);inspect(d);return true}
            MotionEvent.ACTION_MOVE->{if(start==null)return false;moved=moved||abs(e.x-downX)>=4*density;inspect(d);if(moved){range=start!! to d;onRange(NativeGeo.section(samples,start!!,d))};return true}
            MotionEvent.ACTION_UP->{if(start==null)return false;inspect(d);if(moved){range=start!! to d;onRange(NativeGeo.section(samples,start!!,d))}else{range=null;onRange(null);performClick()};start=null;parent.requestDisallowInterceptTouchEvent(false);return true}
            MotionEvent.ACTION_CANCEL->{start=null;cursor=null;range=null;onRange(null);onLeave();parent.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean{
        if(key==KeyEvent.KEYCODE_ESCAPE){cursor=null;range=null;onRange(null);onLeave();invalidate();return true}
        if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)&&samples.isNotEmpty()){
            val index=samples.indexOfFirst{it.distance>=(cursor?.distance?:0.0)}.coerceAtLeast(0);inspect(samples[(index+if(key==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1).coerceIn(samples.indices)].distance);return true
        };return super.onKeyDown(key,event)
    }
}
