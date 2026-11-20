package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Offline controlled GPU fixture; no provider or six-pack dependency. */
class GestureProofActivity:Activity(){
    lateinit var terrain:TerrainView
    override fun onCreate(state:Bundle?){
        super.onCreate(state);terrain=TerrainView(this)
        val root=FrameLayout(this);root.addView(terrain)
        root.addView(TextView(this).apply{text="GESTURE TEST · synthetic flat terrain"})
        setContentView(root)
        val heights=ByteBuffer.allocateDirect(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(129*129){heights.putFloat(800f)};heights.rewind()
        val image=ByteBuffer.allocateDirect(256*256*4)
        repeat(256*256){image.put(90.toByte());image.put(130.toByte());image.put(80.toByte());image.put(255.toByte())};image.rewind()
        terrain.onCamera={terrain.upload(8,248,162,heights,image)}
        terrain.centerHeight(800f)
    }
    override fun onResume(){super.onResume();terrain.setActive(true)}
    override fun onPause(){terrain.setActive(false);super.onPause()}
    override fun onDestroy(){terrain.close();super.onDestroy()}
}
class GestureProofInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        var activity:GestureProofActivity?=null
        try{
            activity=startActivitySync(Intent(targetContext,GestureProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GestureProofActivity
            val view=activity.terrain
            val deadline=SystemClock.uptimeMillis()+90000
            while((view.residentSourceCounts().first==0||!view.frameReady()||abs(view.centerElevation-800)>1)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
            check(view.residentSourceCounts().first>0&&view.frameReady()){"No controlled native frame"}
            fun pick(x:Double,y:Double):DoubleArray{
                val latch=CountDownLatch(1);var result:DoubleArray?=null
                runOnMainSync{view.pick(x,y){result=it;latch.countDown()}}
                check(latch.await(10,TimeUnit.SECONDS));return checkNotNull(result){"Missing controlled terrain pick"}
            }
            val x=view.width*.5;val y=view.height*.58
            val anchor=pick(x,y);val initial=view.cameraPose
            val start=SystemClock.uptimeMillis()
            fun event(action:Int,xx:Double,yy:Double){runOnMainSync{val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,xx.toFloat(),yy.toFloat(),0);view.onTouchEvent(e);e.recycle()}}
            event(MotionEvent.ACTION_DOWN,x,y)
            // Allow the one start-of-gesture native terrain pick to return.
            SystemClock.sleep(80)
            val dx=view.width*.12;val dy=view.height*.06
            for(i in 1..12){event(MotionEvent.ACTION_MOVE,x+dx*i/12,y+dy*i/12);SystemClock.sleep(20)}
            SystemClock.sleep(180) // Hold before release: test drag anchoring separately from momentum.
            event(MotionEvent.ACTION_UP,x+dx,y+dy)
            val frameDeadline=SystemClock.uptimeMillis()+10000
            while(!view.frameReady()&&SystemClock.uptimeMillis()<frameDeadline)SystemClock.sleep(20)
            val after=pick(x+dx,y+dy)
            val meters=NativeGeo.distance(Coordinate(anchor[0],anchor[1]),Coordinate(after[0],after[1]))
            check(meters<2.0){"Grabbed point drifted ${meters}m on rendered terrain"}
            check(initial!=view.cameraPose){"Camera did not pan"}
            SystemClock.sleep(350) // Separate gestures, avoiding Android double-tap recognition.
            val fastAnchor=pick(x,y)
            runOnMainSync{
                val now=SystemClock.uptimeMillis()
                for((action,xx,yy)in listOf(Triple(MotionEvent.ACTION_DOWN,x,y),Triple(MotionEvent.ACTION_MOVE,x+dx,y+dy),Triple(MotionEvent.ACTION_UP,x+dx,y+dy))){
                    val e=MotionEvent.obtain(now,now,action,xx.toFloat(),yy.toFloat(),0);view.onTouchEvent(e);e.recycle()
                }
            }
            SystemClock.sleep(150)
            val fastDeadline=SystemClock.uptimeMillis()+10000
            while(!view.frameReady()&&SystemClock.uptimeMillis()<fastDeadline)SystemClock.sleep(20)
            val fastAfter=pick(x+dx,y+dy)
            val fastMeters=NativeGeo.distance(Coordinate(fastAnchor[0],fastAnchor[1]),Coordinate(fastAfter[0],fastAfter[1]))
            check(fastMeters<2.0){"Fast drag was lost while anchor pick was pending: ${fastMeters}m drift"}
            val result=Bundle().apply{putString("stream","PASS native gesture: fixed terrain anchor, diagonal pitched drag, ${meters}m drift; immediate-release drag ${fastMeters}m drift; synthetic fixture, not real-terrain visual acceptance\n")}
            runOnMainSync{activity.finish()};finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){activity?.let{runOnMainSync{it.finish()}};finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream","FAIL ${e.javaClass.simpleName}: ${e.message}\n")})}
    }
}
