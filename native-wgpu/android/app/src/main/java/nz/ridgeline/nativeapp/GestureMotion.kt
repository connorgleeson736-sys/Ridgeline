package nz.ridgeline.nativeapp

import kotlin.math.*

/** MapLibre GL JS 6.14 handler_inertia.ts defaults, in logical screen pixels.
 * Cumulative accepted input avoids summing duplicate or cancelled camera frames. */
internal class GestureMotion {
    data class Sample(val time:Long,val x:Double,val y:Double,val zoom:Double,val bearing:Double,val pitch:Double)
    data class Coast(val duration:Long,val x:Double,val y:Double,val zoom:Double,val bearing:Double,val pitch:Double)
    private val samples=ArrayDeque<Sample>()
    fun clear()=samples.clear()
    fun record(sample:Sample){
        while(samples.isNotEmpty()&&sample.time-samples.first().time>160)samples.removeFirst()
        if(samples.lastOrNull()?.time==sample.time)samples.removeLast()
        samples.addLast(sample)
        while(samples.size>64)samples.removeFirst()
    }
    private fun ease(amount:Double,elapsed:Long,deceleration:Double,maxSpeed:Double):Pair<Double,Double>{
        val speed=(amount*.3/(elapsed.coerceAtLeast(1)/1000.0)).coerceIn(-maxSpeed,maxSpeed)
        val duration=abs(speed)/(deceleration*.3)
        return speed*duration/2 to duration*1000
    }
    fun finish(now:Long):Coast? {
        while(samples.isNotEmpty()&&now-samples.first().time>160)samples.removeFirst()
        if(samples.size<2){clear();return null}
        val rows=samples.toList();var first=max(0,rows.size-2)
        while(first>0&&rows[first-1].time>=now-60)first--
        val a=rows[first];val b=rows.last();val elapsed=now-a.time
        val dx=b.x-a.x;val dy=b.y-a.y;val distance=hypot(dx,dy)
        val pan=ease(distance,elapsed,2500.0,1400.0)
        val zoom=ease(b.zoom-a.zoom,elapsed,20.0,1400.0)
        val bearing=ease(b.bearing-a.bearing,elapsed,1000.0,360.0)
        val pitch=ease(b.pitch-a.pitch,elapsed,1000.0,90.0)
        clear()
        val duration=maxOf(pan.second,zoom.second,bearing.second,pitch.second).roundToLong()
        if(duration<16)return null
        return Coast(duration,if(distance>0)dx/distance*pan.first else 0.0,if(distance>0)dy/distance*pan.first else 0.0,zoom.first,bearing.first.coerceIn(-179.0,179.0),pitch.first)
    }
}

/** MapLibre's pinch/rotation activation and deliberate two-finger tilt detection.
 * Input coordinates are logical pixels; tilt/rotation signs match its screen axes. */
internal class TwoFingerGesture {
    data class Delta(val zoom:Double,val bearing:Double,val pitch:Double,val tilting:Boolean)
    private var ax=0.0;private var ay=0.0;private var bx=0.0;private var by=0.0
    private var startSpan=1.0;private var span=1.0;private var minSpan=1.0
    private var angle=0.0;private var startAngle=0.0
    private var zooming=false;private var rotating=false;private var tilt:Boolean?=null;private var firstMove:Long?=null
    fun start(x0:Double,y0:Double,x1:Double,y1:Double){
        ax=x0;ay=y0;bx=x1;by=y1
        span=hypot(bx-ax,by-ay).coerceAtLeast(.001);startSpan=span;minSpan=span
        angle=atan2(by-ay,bx-ax);startAngle=angle
        zooming=false;rotating=false;tilt=if(abs(by-ay)>abs(bx-ax))false else null;firstMove=null
    }
    fun move(x0:Double,y0:Double,x1:Double,y1:Double,time:Long):Delta{
        val dax=x0-ax;val day=y0-ay;val dbx=x1-bx;val dby=y1-by
        if(tilt==null){
            val a=hypot(dax,day)>=2;val b=hypot(dbx,dby)>=2
            if(a&&b)tilt=abs(day)>abs(dax)&&abs(dby)>abs(dbx)&&(day>0)==(dby>0)
            else if(a||b){if(firstMove==null)firstMove=time;if(time-firstMove!!>=100)tilt=false}
        }
        val nextSpan=hypot(x1-x0,y1-y0).coerceAtLeast(.001)
        val nextAngle=atan2(y1-y0,x1-x0)
        val rotation=atan2(sin(nextAngle-angle),cos(nextAngle-angle))
        val totalRotation=atan2(sin(nextAngle-startAngle),cos(nextAngle-startAngle))
        minSpan=min(minSpan,nextSpan)
        if(abs(log2(nextSpan/startSpan))>=.1)zooming=true
        if(abs(totalRotation)>=50/minSpan)rotating=true // 25px along circumference.
        val result=if(tilt==true)Delta(0.0,0.0,-(day+dby)*.25,true) else Delta(if(zooming)log2(nextSpan/span)else 0.0,if(rotating)-Math.toDegrees(rotation)else 0.0,0.0,false)
        // Tilt waits for both fingers; its initial displacement must not be lost.
        if(tilt!=null){ax=x0;ay=y0;bx=x1;by=y1}
        span=nextSpan;angle=nextAngle
        return result
    }
}
