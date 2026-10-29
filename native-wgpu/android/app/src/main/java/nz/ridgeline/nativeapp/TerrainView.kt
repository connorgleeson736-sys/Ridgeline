package nz.ridgeline.nativeapp
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*
class TerrainView(context:Context):SurfaceView(context),SurfaceHolder.Callback {
 private val thread=HandlerThread("ridgeline-gpu").apply{start()}
 private val gpu=Handler(thread.looper)
 private var handle=0L
 @Volatile private var live=false
 @Volatile private var active=true
 @Volatile var longitude=170.141; private set
 @Volatile var latitude=-43.595; private set
 @Volatile var zoom=11.9; private set
 @Volatile var bearing=-24f; private set
 @Volatile var pitch=58f; private set
 @Volatile var centerElevation=2500f; private set
 var onCamera:()->Unit={}
 var onStatus:(String)->Unit={}
 private val queued=java.util.concurrent.atomic.AtomicBoolean(false)
 private val frame=object:Runnable {override fun run(){queued.set(false);if(live&&active&&handle!=0L)NativeBridge.render(handle,width,height,longitude,latitude,zoom,bearing,pitch,centerElevation)}}
 private fun requestFrame(){if(live&&active&&queued.compareAndSet(false,true))gpu.post(frame)}
 private fun changed(){requestFrame();onCamera()}
 init {holder.addCallback(this)}
 override fun surfaceCreated(holder:SurfaceHolder){live=true;gpu.post{handle=NativeBridge.create(holder.surface,width,height);post{onStatus(if(handle==0L)"Native GPU initialization failed" else "Loading terrain and imagery…")};requestFrame()};changed()}
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){requestFrame()}
 override fun surfaceDestroyed(holder:SurfaceHolder){live=false;gpu.removeCallbacks(frame);queued.set(false);val done=CountDownLatch(1);gpu.post{if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()};done.await(5,TimeUnit.SECONDS)}
 fun setActive(value:Boolean){active=value;gpu.removeCallbacks(frame);queued.set(false);if(value&&live)requestFrame()}
 fun close(){active=false;live=false;gpu.removeCallbacks(frame);queued.set(false);gpu.post{if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}}
 fun upload(z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer){gpu.post{if(handle!=0L&&NativeBridge.upload(handle,z,x,y,heights,rgba))post{onStatus("");requestFrame()}}}
 fun centerHeight(value:Float){centerElevation=value;requestFrame()}
 private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(detector:ScaleGestureDetector):Boolean{zoom=(zoom+ln(detector.scaleFactor.toDouble())/ln(2.0)).coerceIn(4.0,19.0);changed();return true}})
 private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onDoubleTap(e:MotionEvent):Boolean{zoom=(zoom+1).coerceAtMost(19.0);changed();return true}
  override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean{if(b.pointerCount!=1||scaler.isInProgress)return true;val world=512*2.0.pow(zoom);val angle=Math.toRadians(bearing.toDouble());longitude+=(dx*cos(angle)+dy*sin(angle))/world*360;val m=(1-asinh(tan(Math.toRadians(latitude)))/PI)/2+(dy*cos(angle)-dx*sin(angle))/world;latitude=Math.toDegrees(atan(sinh(PI*(1-2*m)))).coerceIn(-49.0,-33.0);longitude=longitude.coerceIn(165.0,180.0);changed();return true}})
 private var lastAngle=0.0;private var lastY=0f
 override fun onTouchEvent(e:MotionEvent):Boolean{scaler.onTouchEvent(e);gestures.onTouchEvent(e);if(e.pointerCount==2){val angle=atan2((e.getY(1)-e.getY(0)).toDouble(),(e.getX(1)-e.getX(0)).toDouble());val y=(e.getY(0)+e.getY(1))/2;if(e.actionMasked==MotionEvent.ACTION_MOVE){bearing+=(Math.toDegrees(angle-lastAngle)).toFloat();if(!scaler.isInProgress)pitch=(pitch+(y-lastY)*0.1f).coerceIn(0f,80f);changed()};lastAngle=angle;lastY=y};return true}
}
