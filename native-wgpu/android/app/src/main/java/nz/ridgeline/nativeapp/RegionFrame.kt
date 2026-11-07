package nz.ridgeline.nativeapp

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Capacitor's offline region frame: a page-shaped box (paper aspect) over a
 * 2D north-up map, dimmed outside, with four corner handles that resize it
 * at a fixed aspect. Touches away from the handles go to the map, so the map
 * pans and zooms underneath the frame. */
internal class RegionFrame(context:Context):View(context){
    /** Paper sizes in millimetres, as the Capacitor app. */
    companion object{
        val PAPER=linkedMapOf("A5" to (148.0 to 210.0),"A4" to (210.0 to 297.0),"A3" to (297.0 to 420.0),"Letter" to (215.9 to 279.4),"Legal" to (215.9 to 355.6))
        /** Capacitor pageFrame(): the printable map area keeps the page aspect,
         * 84% of the available space. Fractions [left, top, right, bottom]. */
        fun pageFrame(size:String,landscape:Boolean,width:Double,height:Double):DoubleArray{
            var (w,h)=(PAPER[size]?:PAPER.getValue("A4")).let{it.first*72/25.4 to it.second*72/25.4}
            if(landscape){val t=w;w=h;h=t}
            val ratio=(w-60)/(h-94);val frameW=min(width*.84,height*.84*ratio);val frameH=frameW/ratio
            return doubleArrayOf(.5-frameW/width/2,.5-frameH/height/2,.5+frameW/width/2,.5+frameH/height/2)
        }
    }
    private val dp=resources.displayMetrics.density
    var box=doubleArrayOf(.15,.16,.85,.82);private set
    private var aspect=0.0
    /** Height at the bottom covered by the panel (the frame stays above it). */
    var bottomInset=0;set(value){field=value;invalidate()}
    private val shade=Paint().apply{color=Color.argb(70,16,40,35)}
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.WHITE;strokeWidth=2.5f*dp}
    private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.argb(150,18,32,35);strokeWidth=5f*dp}
    private val handle=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE}
    private val handleRing=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=NativeUi.ink;strokeWidth=2f*dp}
    private var dragging=-1
    private val usable get()=max(60f,height-bottomInset.toFloat())
    fun page(size:String,landscape:Boolean){
        val b=pageFrame(size,landscape,width.toDouble(),usable.toDouble())
        box=doubleArrayOf(b[0],b[1]*usable/height,b[2],b[3]*usable/height)
        val (w,h)=(PAPER[size]?:PAPER.getValue("A4")).let{if(landscape)it.second to it.first else it}
        aspect=(w*72/25.4-60)/(h*72/25.4-94);invalidate()
    }
    /** Screen corners in pixels: left, top, right, bottom. */
    fun corners()=floatArrayOf((box[0]*width).toFloat(),(box[1]*height).toFloat(),(box[2]*width).toFloat(),(box[3]*height).toFloat())
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){if(ow==0||oh==0)return;val cx=(box[0]+box[2])/2;val cy=(box[1]+box[3])/2;val fw=(box[2]-box[0])*ow;val fh=(box[3]-box[1])*oh
        box=doubleArrayOf(cx-fw/w/2,cy-fh/h/2,cx+fw/w/2,cy+fh/h/2)}
    override fun onDraw(canvas:Canvas){
        val c=corners()
        canvas.drawRect(0f,0f,width.toFloat(),c[1],shade);canvas.drawRect(0f,c[3],width.toFloat(),height.toFloat(),shade)
        canvas.drawRect(0f,c[1],c[0],c[3],shade);canvas.drawRect(c[2],c[1],width.toFloat(),c[3],shade)
        canvas.drawRect(c[0],c[1],c[2],c[3],halo);canvas.drawRect(c[0],c[1],c[2],c[3],outline)
        for((x,y)in listOf(c[0] to c[3],c[2] to c[3],c[2] to c[1],c[0] to c[1])){canvas.drawCircle(x,y,9*dp,handle);canvas.drawCircle(x,y,9*dp,handleRing)}
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        val c=corners();val points=listOf(c[0] to c[3],c[2] to c[3],c[2] to c[1],c[0] to c[1])
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{dragging=points.indexOfFirst{(x,y)->hypot(x-event.x,y-event.y)<28*dp};if(dragging<0)return false;parent?.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->if(dragging>=0){resize(dragging,event.x.toDouble(),event.y.toDouble());return true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->if(dragging>=0){dragging=-1;return true}
        }
        return dragging>=0
    }
    /** Corner [index] (0 SW, 1 SE, 2 NE, 3 NW) to (x, y), the opposite corner fixed, aspect kept. */
    private fun resize(index:Int,x:Double,y:Double){
        val w=width.toDouble();val h=height.toDouble();val right=index==1||index==2;val lower=index<2
        val ax=box[if(right)0 else 2]*w;val ay=box[if(lower)1 else 3]*h
        var fw=max(64*dp.toDouble(),abs(x-ax));if(aspect>0)fw=max(fw,abs(y-ay)*aspect)
        val maxW=if(right)w*.96-ax else ax-w*.04;val maxH=if(lower)min(h*.94,usable.toDouble())-ay else ay-h*.06
        fw=min(fw,maxW);var fh=if(aspect>0)fw/aspect else max(64*dp.toDouble(),abs(y-ay));if(fh>maxH){fh=maxH;if(aspect>0)fw=fh*aspect}
        if(right)box[2]=(ax+fw)/w else box[0]=(ax-fw)/w
        if(lower)box[3]=(ay+fh)/h else box[1]=(ay-fh)/h
        invalidate()
    }
}
