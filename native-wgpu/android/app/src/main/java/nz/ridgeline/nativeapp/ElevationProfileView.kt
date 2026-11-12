package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** O(log n) section statistics and grade over fixed profile samples. Same
 * values as [NativeGeo.section] and [NativeGeo.grade], without scanning every
 * sample on each drag event. */
internal class ProfileIndex(private val samples:List<NativeGeo.ProfilePoint>){
    private val distances=DoubleArray(samples.size){samples[it].distance}
    /** Sums over consecutive pairs (i-1,i) for i in 1..k: up, down, missing pairs. */
    private val up=DoubleArray(samples.size);private val down=DoubleArray(samples.size);private val gaps=IntArray(samples.size)
    private val nulls=IntArray(samples.size+1)
    init{
        for(i in samples.indices){
            nulls[i+1]=nulls[i]+if(samples[i].height==null)1 else 0
            if(i==0)continue
            val l=samples[i-1].height;val r=samples[i].height
            up[i]=up[i-1];down[i]=down[i-1];gaps[i]=gaps[i-1]
            if(l==null||r==null)gaps[i]++ else{val d=r-l;if(d>0)up[i]+=d else down[i]-=d}
        }
    }
    /** First index whose distance is greater than [d]. */
    private fun after(d:Double):Int{var lo=0;var hi=distances.size;while(lo<hi){val mid=(lo+hi) ushr 1;if(distances[mid]<=d)lo=mid+1 else hi=mid};return lo}
    /** First index whose distance is at least [d]. */
    private fun atLeast(d:Double):Int{var lo=0;var hi=distances.size;while(lo<hi){val mid=(lo+hi) ushr 1;if(distances[mid]<d)lo=mid+1 else hi=mid};return lo}
    fun section(a:Double,b:Double):NativeGeo.Section?{
        if(samples.isEmpty()||!a.isFinite()||!b.isFinite())return null
        val start=max(distances.first(),min(a,b));val end=min(distances.last(),max(a,b));if(end<=start)return null
        val first=NativeGeo.profileAt(samples,start)!!;val last=NativeGeo.profileAt(samples,end)!!
        val i0=after(start);val i1=atLeast(end)-1
        var ascent=0.0;var descent=0.0;var complete=true
        fun pair(l:Double?,r:Double?){if(l==null||r==null){complete=false;return};val d=r-l;if(d>0)ascent+=d else descent-=d}
        if(i0>i1)pair(first.height,last.height)
        else{
            pair(first.height,samples[i0].height)
            ascent+=up[i1]-up[i0];descent+=down[i1]-down[i0];if(gaps[i1]-gaps[i0]>0)complete=false
            pair(samples[i1].height,last.height)
        }
        return NativeGeo.Section(start,end,end-start,ascent,descent,complete)
    }
    fun grade(distance:Double):Double?{
        if(samples.size<2||!distance.isFinite())return null
        val lo=max(distances.first(),distance-10);val hi=min(distances.last(),distance+10);if(hi<=lo)return null
        if(nulls[atLeast(hi)]-nulls[after(lo)]>0)return null
        val a=NativeGeo.profileAt(samples,lo)?.height?:return null;val b=NativeGeo.profileAt(samples,hi)?.height?:return null
        return (b-a)/(hi-lo)*100
    }
}

/** Capacitor chart semantics: inspect a point, drag a section (A to B),
 * preserve missing gaps. The static chart is cached as a bitmap, so a drag
 * only redraws the cursor, the section and the tooltip. */
class ElevationProfileView(context:Context):View(context){
    private var samples=emptyList<NativeGeo.ProfilePoint>()
    private var index=ProfileIndex(samples)
    private var measured=false
    private val path=Path();private val fill=Path();private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=Paint(Paint.ANTI_ALIAS_FLAG)
    private var low=0.0;private var high=1.0;private var cursor:NativeGeo.ProfilePoint?=null
    private var start:Double?=null;private var range:Pair<Double,Double>?=null;private var downX=0f;private var moved=false
    private var section:NativeGeo.Section?=null
    private var base:Bitmap?=null;private var highlight:Bitmap?=null;private var dirty=true
    private val density=resources.displayMetrics.density
    private val scaled=resources.displayMetrics.scaledDensity
    private val left get()=46*density
    private val right get()=max(left+1,width-12*density)
    private val top get()=22*density
    private val bottom get()=max(top+1,height-30*density)
    /** Shown in place of the chart while there is no elevation to draw. */
    var message="Elevation will appear here";set(value){if(field!=value){field=value;dirty=true;invalidate()}}
    /** Huts along the line (distance, name), drawn as small hut marks on the
     * profile; tap one for its name. */
    var markers:List<Pair<Double,String>> = emptyList();set(value){if(field!=value){field=value;picked=-1;dirty=true;invalidate()}}
    private var picked=-1
    /** Zoomed distance window [v0, v1] (pinch, two-finger pan; double-tap resets); v1 <= v0 is the whole line. */
    private var v0=0.0;private var v1=0.0
    private var pinching=false;private var anchorA=0.0;private var anchorB=0.0
    private var lastTapAt=0L;private var lastTapX=0f
    private val total get()=samples.lastOrNull()?.distance?:0.0
    private val zoomed get()=v1>v0
    private val lo get()=if(zoomed)v0 else 0.0
    private val hi get()=if(zoomed)v1 else max(1.0,total)
    private fun distanceAt(px:Float)=lo+((px-left)/(right-left)).coerceIn(0f,1f)*(hi-lo)
    fun resetZoom(){if(!zoomed)return;v0=0.0;v1=0.0;dirty=true;invalidate()}
    var onInspect:(NativeGeo.ProfilePoint,Boolean)->Unit={_,_->}
    var onRange:(NativeGeo.Section?)->Unit={}
    var onLeave:()->Unit={}
    init{isFocusable=true;contentDescription="Route elevation profile. Drag for section statistics, pinch to zoom, double-tap to show all; arrow keys inspect samples."}
    fun data(value:List<NativeGeo.ProfilePoint>){
        val sameGeometry=samples.size==value.size&&samples.indices.all{samples[it].point==value[it].point&&samples[it].distance==value[it].distance}
        val distance=cursor?.distance;samples=value;index=ProfileIndex(value);measured=value.count{it.height!=null}>=2
        if(sameGeometry){cursor=distance?.let{NativeGeo.profileAt(value,it)};section=range?.let{directed(it.first,it.second)}}
        else{cursor=null;range=null;start=null;section=null;v0=0.0;v1=0.0;picked=-1}
        dirty=true;invalidate()
    }
    fun clearInspection(){if(cursor==null&&range==null)return;cursor=null;range=null;start=null;section=null;onRange(null);onLeave();invalidate()}
    fun refreshInspection(){cursor?.let{onInspect(it,false)};range?.let{onRange(section)}}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){dirty=true}
    private fun x(d:Double)=(left+(d-lo)/max(1e-6,hi-lo)*(right-left)).toFloat()
    private fun y(h:Double)=(bottom-(h-low)/(high-low)*(bottom-top)).toFloat()
    private fun rebuild(){
        dirty=false
        if(width<=0||height<=0)return
        // Only the zoomed window (and one sample either side, for the edges).
        val first=(samples.indexOfFirst{it.distance>=lo}-1).coerceAtLeast(0)
        val last=(samples.indexOfLast{it.distance<=hi}+1).coerceAtMost(samples.size-1)
        val visible=if(samples.isEmpty()||last<first)samples else samples.subList(first,last+1)
        val hs=visible.filter{it.distance in lo..hi}.mapNotNull{it.height}.ifEmpty{visible.mapNotNull{it.height}};val min=hs.minOrNull()?:0.0;val max=hs.maxOrNull()?:1.0
        val pad=max(15.0,(max-min)*.08);low=floor((min-pad)/10)*10;high=ceil((max+pad)/10)*10
        if(high<=low)high=low+30
        path.reset();fill.reset();var pen=false;var lastX=0f
        // Draw at most four samples per chart column; analysis uses every sample.
        for(p in RouteProfile.chartSamples(visible)){val h=p.height
            if(h==null){if(pen){fill.lineTo(lastX,bottom);fill.close()};pen=false;continue}
            val px=x(p.distance);val py=y(h)
            if(pen){path.lineTo(px,py);fill.lineTo(px,py)}else{path.moveTo(px,py);fill.moveTo(px,bottom);fill.lineTo(px,py)}
            lastX=px;pen=true
        };if(pen){fill.lineTo(lastX,bottom);fill.close()}
        val b=base?.takeIf{it.width==width&&it.height==height}?:Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{base?.recycle();base=it}
        val canvas=Canvas(b);canvas.drawColor(NativeUi.paper)
        text.textSize=10*scaled;text.color=NativeUi.muted;text.textAlign=Paint.Align.LEFT
        canvas.drawText("Elevation (m)",left,13*density,text)
        val total=this.total
        text.textAlign=Paint.Align.CENTER
        canvas.drawText(if(total>=1000)"Distance (km)"else"Distance (m)",(left+right)/2,height-3*density,text)
        if(!measured){text.textSize=12*scaled;canvas.drawText(message,width/2f,height/2f,text);return}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=density*.7f;paint.pathEffect=DashPathEffect(floatArrayOf(3*density,4*density),0f)
        text.textAlign=Paint.Align.RIGHT
        for(i in 0..2){val h=low+(high-low)*i/2;val yy=y(h);paint.color=NativeUi.line;canvas.drawLine(left,yy,right,yy,paint);canvas.drawText(String.format(java.util.Locale.ROOT,"%,d",h.roundToInt()),left-6*density,yy+3.5f*density,text)}
        paint.pathEffect=null
        text.textAlign=Paint.Align.CENTER
        val span=hi-lo
        for(i in 0..2){val d=lo+span*i/2;val label=if(total>=1000)String.format(java.util.Locale.ROOT,when{span>=20000->"%.0f";span>=2000->"%.1f";else->"%.2f"},d/1000)else"${d.roundToInt()}";canvas.drawText(label,x(d),bottom+16*density,text)}
        paint.color=Color.rgb(167,178,168);canvas.drawLine(left,bottom,right,bottom,paint);canvas.drawLine(left,top,left,bottom,paint)
        canvas.save();canvas.clipRect(left,0f,right,bottom)
        paint.style=Paint.Style.FILL;paint.color=0x22e87944;canvas.drawPath(fill,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=Color.rgb(186,99,53);paint.strokeJoin=Paint.Join.ROUND;canvas.drawPath(path,paint)
        canvas.restore()
        // The selected-section fill, cut to the range at draw time.
        val h=highlight?.takeIf{it.width==width&&it.height==height}?:Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{highlight?.recycle();highlight=it}
        h.eraseColor(Color.TRANSPARENT);paint.style=Paint.Style.FILL;paint.color=0x52587d62
        Canvas(h).apply{clipRect(left,0f,right,bottom);drawPath(fill,paint)}
    }
    /** Where hut mark [i] is drawn (above the profile), or null if outside the window. */
    private val at=PointF()
    private fun markerAt(i:Int):PointF?{
        val d=markers.getOrNull(i)?.first?:return null;if(d<lo||d>hi)return null
        val h=NativeGeo.profileAt(samples,d)?.height?:return null
        at.set(x(d),y(h)-7*density);return at
    }
    private val hut=Path()
    private fun drawMarkers(canvas:Canvas){
        val r=4.5f*density
        for(i in markers.indices){val at=markerAt(i)?:continue;val mx=at.x;val my=at.y;val s=if(i==picked)1.35f else 1f
            hut.reset();hut.moveTo(mx-r*s,my);hut.lineTo(mx,my-r*s);hut.lineTo(mx+r*s,my);hut.lineTo(mx+r*.75f*s,my);hut.lineTo(mx+r*.75f*s,my+r*s);hut.lineTo(mx-r*.75f*s,my+r*s);hut.lineTo(mx-r*.75f*s,my);hut.close()
            paint.style=Paint.Style.FILL;paint.color=if(i==picked)END else NativeUi.ink;canvas.drawPath(hut,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.color=NativeUi.paper;canvas.drawPath(hut,paint)}
        val at=markerAt(picked)?:return;val name=markers[picked].second
        text.textSize=11.5f*scaled;text.textAlign=Paint.Align.CENTER;text.isFakeBoldText=true
        val w=text.measureText(name)+16*density;val h=21*density
        val bx=(at.x-w/2).coerceIn(left,max(left,right-w));val by=max(2*density,at.y-r*1.4f-6*density-h)
        box.set(bx,by,bx+w,by+h);paint.style=Paint.Style.FILL;paint.color=NativeUi.ink;canvas.drawRoundRect(box,h/2,h/2,paint)
        text.color=Color.WHITE;canvas.drawText(name,bx+w/2,by+h/2+4*scaled,text);text.isFakeBoldText=false
    }
    /** The hut mark under a tap, nearest first, or -1. */
    private fun markerHit(px:Float,py:Float):Int{
        var best=-1;var bestD=Float.MAX_VALUE
        for(i in markers.indices){val at=markerAt(i)?:continue;val dx=abs(px-at.x);val dy=abs(py-at.y)
            if(dx<18*density&&dy<26*density&&dx+dy<bestD){bestD=dx+dy;best=i}}
        return best
    }
    private val clip=Rect();private val box=RectF()
    override fun onDraw(canvas:Canvas){
        if(dirty)rebuild()
        base?.let{canvas.drawBitmap(it,0f,0f,null)}?:canvas.drawColor(NativeUi.paper)
        if(!measured)return
        val selected=range
        if(selected!=null){
            val a=x(min(selected.first,selected.second));val b=x(max(selected.first,selected.second))
            highlight?.let{clip.set(floor(a).toInt(),0,ceil(b).toInt(),height);canvas.drawBitmap(it,clip,clip,null)}
            // A marks where the drag started, B where it is now, as on the map.
            for((d,label,color) in listOf(Triple(selected.first,"A",START),Triple(selected.second,"B",END))){
                val px=x(d);paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*density;canvas.drawLine(px,top,px,bottom,paint)
                paint.style=Paint.Style.FILL;canvas.drawCircle(px,top,8*density,paint)
                text.textSize=10*scaled;text.color=Color.WHITE;text.textAlign=Paint.Align.CENTER;text.isFakeBoldText=true
                canvas.drawText(label,px,top+3.5f*density,text);text.isFakeBoldText=false
            }
        }
        drawMarkers(canvas)
        val p=cursor
        if(p!=null&&selected==null){
            paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.color=NativeUi.ink;canvas.drawLine(x(p.distance),top,x(p.distance),bottom,paint)
            p.height?.let{h->paint.style=Paint.Style.FILL;paint.color=END;canvas.drawCircle(x(p.distance),y(h),4*density,paint)}
        }
        tooltip(canvas,p,selected)
    }
    /** Capacitor tooltip: elevation, distance and grade at the cursor, or the
     * section's distance, ascent and descent while dragging. */
    private fun tooltip(canvas:Canvas,p:NativeGeo.ProfilePoint?,selected:Pair<Double,Double>?){
        val lines:List<Pair<String,Int>> = if(selected!=null){val s=section?:return
            listOf("Distance "+distance(s.distance) to Color.rgb(17,17,17),
                "↑ Ascent "+(if(s.complete)"${s.ascent.roundToInt()} m" else "unavailable") to Color.rgb(60,120,83),
                "↓ Descent "+(if(s.complete)"${s.descent.roundToInt()} m" else "unavailable") to END)
        }else{if(p==null)return;val grade=index.grade(p.distance)
            listOf((p.height?.let{String.format(java.util.Locale.ROOT,"%,d m",it.roundToInt())}?:"Elevation unavailable") to Color.rgb(17,17,17),
                distance(p.distance)+" from start" to Color.rgb(51,74,60),
                (if(grade==null)"Grade unavailable" else "Grade "+(if(grade>0)"+" else "")+String.format(java.util.Locale.ROOT,"%.1f%%",grade)) to Color.rgb(51,74,60))}
        text.textSize=11*scaled;text.textAlign=Paint.Align.LEFT
        val w=lines.maxOf{text.measureText(it.first)}+16*density;val line=15*density;val h=lines.size*line+8*density
        val anchor=if(selected!=null)x(selected.second)else x(p!!.distance)
        val bx=(if(anchor+12*density+w<=right)anchor+12*density else anchor-12*density-w).coerceIn(left,max(left,right-w))
        val by=top+12*density
        box.set(bx,by,bx+w,by+h);paint.style=Paint.Style.FILL;paint.color=if(selected!=null)Color.rgb(247,250,246)else Color.WHITE;canvas.drawRoundRect(box,6*density,6*density,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.color=if(selected!=null)Color.rgb(128,161,138)else Color.rgb(205,214,204);canvas.drawRoundRect(box,6*density,6*density,paint)
        for((i,row) in lines.withIndex()){text.color=row.second;canvas.drawText(row.first,bx+8*density,by+line*(i+1),text)}
    }
    private fun distance(m:Double)=if(m>=1000)String.format(java.util.Locale.ROOT,"%.2f km",m/1000)else"${m.roundToInt()} m"
    private fun inspect(d:Double){cursor=NativeGeo.profileAt(samples,d);cursor?.let{onInspect(it,false)};invalidate()}
    /** Section from A to B in drag order: dragging backwards travels the route in
     * reverse, so its ascent and descent swap. */
    private fun directed(from:Double,to:Double)=index.section(from,to)?.let{if(to<from)it.copy(ascent=it.descent,descent=it.ascent)else it}
    private fun select(from:Double,to:Double){range=from to to;section=directed(from,to);cursor=NativeGeo.profileAt(samples,to);onRange(section);invalidate()}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(samples.size<2||width==0||!measured)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent.requestDisallowInterceptTouchEvent(true);pinching=false;val d=distanceAt(e.x);start=d;downX=e.x;moved=false;range=null;section=null;onRange(null);inspect(d);return true}
            // Two fingers zoom and pan the distance axis: the distances under
            // both fingers stay under them.
            MotionEvent.ACTION_POINTER_DOWN->if(e.pointerCount==2){
                pinching=true;start=null;range=null;section=null;cursor=null;picked=-1;onRange(null);onLeave()
                if(!zoomed){v0=0.0;v1=total}
                anchorA=distanceAt(e.getX(0));anchorB=distanceAt(e.getX(1));invalidate();return true
            }
            MotionEvent.ACTION_MOVE->{
                if(pinching){
                    if(e.pointerCount<2)return true
                    val xa=e.getX(0);val xb=e.getX(1);if(abs(xb-xa)<8*density||anchorA==anchorB)return true
                    val width=(anchorB-anchorA)*(right-left)/(xb-xa);if(width<=0)return true
                    val span=width.coerceIn(min(total,MIN_SPAN),total)
                    if(span>=total*.999){v0=0.0;v1=0.0}
                    else{v0=(anchorA-(xa-left)/(right-left)*span).coerceIn(0.0,total-span);v1=v0+span}
                    dirty=true;invalidate();return true
                }
                val from=start?:return false;val d=distanceAt(e.x);moved=moved||abs(e.x-downX)>=4*density;if(moved)select(from,d)else inspect(d);return true}
            MotionEvent.ACTION_UP->{
                parent.requestDisallowInterceptTouchEvent(false)
                if(pinching){pinching=false;return true}
                val from=start?:return false;start=null;val d=distanceAt(e.x)
                if(moved){picked=-1;select(from,d);return true}
                // Double-tap: back to the whole line.
                val now=android.os.SystemClock.uptimeMillis()
                if(zoomed&&now-lastTapAt<300&&abs(e.x-lastTapX)<30*density){lastTapAt=0;resetZoom();cursor=null;picked=-1;onLeave();invalidate();return true}
                lastTapAt=now;lastTapX=e.x
                val hit=markerHit(e.x,e.y)
                if(hit>=0){picked=hit;cursor=null;range=null;section=null;onRange(null);NativeGeo.profileAt(samples,markers[hit].first)?.let{onInspect(it,false)};invalidate();performClick();return true}
                picked=-1;range=null;section=null;onRange(null);inspect(d);performClick();return true}
            MotionEvent.ACTION_CANCEL->{start=null;pinching=false;cursor=null;range=null;section=null;onRange(null);onLeave();parent.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        };return true
    }
    /** Section ends in drag order: A where the drag started, B where it is now. */
    fun rangeEnds():Pair<Coordinate,Coordinate>?{val r=range?:return null;return (NativeGeo.profileAt(samples,r.first)?.point?:return null) to (NativeGeo.profileAt(samples,r.second)?.point?:return null)}
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean{
        if(key==KeyEvent.KEYCODE_ESCAPE){cursor=null;range=null;section=null;onRange(null);onLeave();invalidate();return true}
        if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)&&samples.isNotEmpty()){
            val i=samples.indexOfFirst{it.distance>=(cursor?.distance?:0.0)}.coerceAtLeast(0);range=null;section=null;inspect(samples[(i+if(key==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1).coerceIn(samples.indices)].distance);return true
        };return super.onKeyDown(key,event)
    }
    override fun onDetachedFromWindow(){super.onDetachedFromWindow();base?.recycle();base=null;highlight?.recycle();highlight=null;dirty=true}
    internal companion object{val START=Color.rgb(72,115,88);val END=Color.rgb(186,99,53)
        /** Narrowest zoomed window, in metres. */
        const val MIN_SPAN=60.0}
}
