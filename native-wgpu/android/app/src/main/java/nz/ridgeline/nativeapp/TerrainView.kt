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
 @Volatile var longitude=170.14219572; private set
 @Volatile var latitude=-43.5955671; private set
 @Volatile var zoom=11.9; private set
 @Volatile var bearing=-24f; private set
 @Volatile var pitch=58f; private set
 @Volatile var centerElevation=3724f; private set
 var onCamera:()->Unit={}
 var onStatus:(String)->Unit={}
 val pixelScale:Double get()=resources.displayMetrics.density.toDouble()
 val renderZoom:Double get()=zoom+ln(pixelScale)/ln(2.0)
 private val uploads=GpuUploadQueue(96)
 private val queued=java.util.concurrent.atomic.AtomicBoolean(false)
 private var presentationFailures=0
 private val frame=object:Runnable {override fun run(){
  queued.set(false)
  if(live&&active&&handle!=0L){
   // At most two full native tiles (~650KiB input) per frame. Coalescing avoids
   // an unbounded Handler backlog when the camera changes faster than upload.
   for(tile in uploads.take(2))if(NativeBridge.upload(handle,tile.id.z,tile.id.x,tile.id.y,tile.heights,tile.rgba))post{onStatus("")}
   val shown=NativeBridge.render(handle,width,height,longitude,latitude,renderZoom,bearing,pitch,centerElevation)
   if(shown)presentationFailures=0 else {presentationFailures++;if(presentationFailures<=8)requestFrame(minOf(250L,16L shl presentationFailures)) else post{onStatus("GPU presentation unavailable")}}
   if(uploads.isNotEmpty())requestFrame()
  }
 }}
 private fun requestFrame(delay:Long=16){if(live&&active&&queued.compareAndSet(false,true))gpu.postDelayed(frame,delay)}
 private fun changed(){requestFrame();onCamera()}
 init {holder.addCallback(this)}
 override fun surfaceCreated(holder:SurfaceHolder){live=true;gpu.post{handle=NativeBridge.create(holder.surface,width,height)
  if(handle!=0L){val am=context.getSystemService(android.app.ActivityManager::class.java);NativeBridge.tileBudget(handle,if(am.isLowRamDevice)32 else (am.memoryClass/2).coerceIn(64,128))}
  post{onStatus(if(handle==0L)"Native GPU initialization failed" else "Loading terrain and imagery…")};requestFrame()};changed()}
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){requestFrame()}
 override fun surfaceDestroyed(holder:SurfaceHolder){live=false;gpu.removeCallbacks(frame);queued.set(false);val done=CountDownLatch(1);gpu.post{if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()};done.await(5,TimeUnit.SECONDS)}
 fun setActive(value:Boolean){active=value;gpu.removeCallbacks(frame);queued.set(false);if(value&&live)requestFrame()}
 fun close(){active=false;live=false;uploads.clear();gpu.removeCallbacks(frame);queued.set(false);gpu.post{if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}}
 fun upload(z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer){
  uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),heights,rgba));requestFrame()
 }
 fun centerHeight(value:Float){centerElevation=value;requestFrame()}
 private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(detector:ScaleGestureDetector):Boolean{zoom=(zoom+ln(detector.scaleFactor.toDouble())/ln(2.0)).coerceIn(4.0,19.0);changed();return true}})
 private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDown(e:MotionEvent)=true;override fun onDoubleTap(e:MotionEvent):Boolean{zoom=(zoom+1).coerceAtMost(19.0);changed();return true}
  override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean{if(b.pointerCount!=1||scaler.isInProgress)return true;val world=512*2.0.pow(zoom)*pixelScale;val angle=Math.toRadians(bearing.toDouble());longitude+=(dx*cos(angle)+dy*sin(angle))/world*360;val m=(1-asinh(tan(Math.toRadians(latitude)))/PI)/2+(dy*cos(angle)-dx*sin(angle))/world;latitude=Math.toDegrees(atan(sinh(PI*(1-2*m)))).coerceIn(-49.0,-33.0);longitude=longitude.coerceIn(165.0,180.0);changed();return true}})
 private var lastAngle=0.0;private var lastY=0f
 override fun onTouchEvent(e:MotionEvent):Boolean{scaler.onTouchEvent(e);gestures.onTouchEvent(e);if(e.pointerCount==2){val angle=atan2((e.getY(1)-e.getY(0)).toDouble(),(e.getX(1)-e.getX(0)).toDouble());val y=(e.getY(0)+e.getY(1))/2;if(e.actionMasked==MotionEvent.ACTION_MOVE){bearing+=(Math.toDegrees(angle-lastAngle)).toFloat();if(!scaler.isInProgress)pitch=(pitch+(y-lastY)*0.1f).coerceIn(0f,85f);changed()};lastAngle=angle;lastY=y};return true}
}
