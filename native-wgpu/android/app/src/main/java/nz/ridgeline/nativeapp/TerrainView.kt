package nz.ridgeline.nativeapp

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class CameraPose(val longitude:Double=170.14219572,val latitude:Double=-43.5955671,
                      val zoom:Double=11.9,val bearing:Float=-24f,val pitch:Float=58f,val elevation:Float=3724f)

/** One GPU owner, coalesced native display-vsync frames and bounded uploads.
 * Camera gesture dynamics are still a prototype; see open parity gates. */
class TerrainView(context:Context):SurfaceView(context),SurfaceHolder.Callback {
    private val thread=HandlerThread("ridgeline-gpu").apply{start()}
    private val gpu=Handler(thread.looper)
    private var handle=0L
    @Volatile private var live=false
    @Volatile private var active=true
    @Volatile var cameraPose=CameraPose();private set
    val longitude get()=cameraPose.longitude
    val latitude get()=cameraPose.latitude
    val zoom get()=cameraPose.zoom
    val bearing get()=cameraPose.bearing
    val pitch get()=cameraPose.pitch
    val centerElevation get()=cameraPose.elevation
    val pixelScale:Double get()=resources.displayMetrics.density.toDouble()
    val renderZoom:Double get()=zoom+ln(pixelScale)/ln(2.0)
    var onCamera:()->Unit={}
    var onData:()->Unit={}
    var onStatus:(String)->Unit={}
    private val uploads=GpuUploadQueue(192)
    private data class Residency(val geometry:LongArray,val images:LongArray)
    @Volatile private var residency=Residency(LongArray(0),LongArray(0))
    private val residencyBytes=ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
    fun isResident(id:GpuUploadQueue.Id,geometry:Boolean)=if(geometry)residency.geometry.contains(id.packed)else residency.images.contains(id.packed)
    internal fun residentSourceCounts()=residency.let{it.geometry.size to it.images.size}
    private fun refreshResidency(){
        val token=generation.get();residencyBytes.rewind()
        if(NativeBridge.residency(handle,residencyBytes)){
            val g=residencyBytes.int;val i=residencyBytes.int
            val next=Residency(LongArray(g){residencyBytes.long},LongArray(i){residencyBytes.long})
            if(live&&generation.get()==token)residency=next
        }
    }
    private val queued=AtomicBoolean(false)
    private val generation=AtomicLong()
    private var presentationFailures=0
    // Created and used exclusively on the GPU HandlerThread's Looper.
    private val choreographer by lazy{Choreographer.getInstance()}
    private val vsync=Choreographer.FrameCallback{drawFrame()}
    private fun drawFrame() {
        queued.set(false)
        if(!live||!active||handle==0L)return
        // Bounded source count and input bytes per display frame (650KiB).
        val rows=uploads.take(4,650*1024);var accepted=false
        for(tile in rows){val id=tile.id;val ok=when(tile.kind){
            GpuUploadQueue.Kind.COMBINED->NativeBridge.upload(handle,id.z,id.x,id.y,tile.heights,tile.rgba)
            GpuUploadQueue.Kind.GEOMETRY->NativeBridge.uploadGeometry(handle,id.z,id.x,id.y,tile.heights)
            GpuUploadQueue.Kind.IMAGE->NativeBridge.uploadImage(handle,id.z,id.x,id.y,tile.rgba)
        };accepted=accepted||ok}
        if(rows.isNotEmpty())refreshResidency()
        if(accepted)post{onStatus("");onData()}
        val pose=cameraPose // One immutable snapshot, no mixed camera revisions.
        val shown=NativeBridge.render(handle,width,height,pose.longitude,pose.latitude,pose.zoom+ln(pixelScale)/ln(2.0),pose.bearing,pose.pitch,pose.elevation)
        if(shown)presentationFailures=0 else {
            presentationFailures++
            if(presentationFailures<=8)requestFrame(minOf(250L,16L shl presentationFailures)) else post{onStatus("GPU presentation unavailable")}
        }
        if(uploads.isNotEmpty())requestFrame()
    }
    private fun requestFrame(delay:Long=0) {
        if(live&&active&&queued.compareAndSet(false,true))gpu.post {
            if(live&&active)choreographer.postFrameCallbackDelayed(vsync,delay)else queued.set(false)
        }
    }
    private fun cancelFrames(){queued.set(false);gpu.post{choreographer.removeFrameCallback(vsync)}}
    private fun changed(){requestFrame();onCamera()}
    init{holder.addCallback(this)}
    override fun surfaceCreated(holder:SurfaceHolder) {
        live=true;val token=generation.incrementAndGet();val surface=holder.surface
        gpu.post {
            if(!live||generation.get()!=token)return@post
            val created=NativeBridge.create(surface,width,height)
            if(!live||generation.get()!=token){if(created!=0L)NativeBridge.destroy(created);return@post}
            handle=created;presentationFailures=0
            if(handle!=0L){val am=context.getSystemService(android.app.ActivityManager::class.java);NativeBridge.tileBudget(handle,if(am.isLowRamDevice)32 else (am.memoryClass/2).coerceIn(64,128))}
            post{onStatus(if(created==0L)"Native GPU initialization failed"else "Loading terrain and imagery…")};requestFrame()
        }
        changed()
    }
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){changed()}
    override fun surfaceDestroyed(holder:SurfaceHolder) {
        live=false;generation.incrementAndGet();residency=Residency(LongArray(0),LongArray(0));queued.set(false)
        val done=CountDownLatch(1)
        gpu.post{choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()}
        done.await(5,TimeUnit.SECONDS)
    }
    fun setActive(value:Boolean){active=value;cancelFrames();if(value&&live)requestFrame()}
    fun close() {
        active=false;live=false;generation.incrementAndGet();uploads.clear();queued.set(false)
        gpu.post{choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}
    }
    fun updateUploadDemand(geometry:Set<GpuUploadQueue.Id>,images:Set<GpuUploadQueue.Id>){uploads.updateDemand(geometry,images)}
    fun upload(z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer){uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),heights,rgba));requestFrame()}
    fun uploadGeometry(z:Int,x:Int,y:Int,data:ByteBuffer){uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),data,GpuUploadQueue.EMPTY,GpuUploadQueue.Kind.GEOMETRY));requestFrame()}
    fun uploadImage(z:Int,x:Int,y:Int,data:ByteBuffer){uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),GpuUploadQueue.EMPTY,data,GpuUploadQueue.Kind.IMAGE));requestFrame()}
    fun pick(x:Double,y:Double,callback:(DoubleArray?)->Unit) {
        gpu.post {
            val output=ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
            val point=if(live&&handle!=0L&&NativeBridge.pick(handle,x,y,output))DoubleArray(3){output.double}else null
            post{callback(point)}
        }
    }
    fun centerHeight(value:Float){if(value.isFinite()){cameraPose=cameraPose.copy(elevation=value);changed()}}
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            cameraPose=cameraPose.copy(zoom=(zoom+ln(detector.scaleFactor.toDouble())/ln(2.0)).coerceIn(4.0,19.0));changed();return true
        }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onDoubleTap(e:MotionEvent):Boolean{cameraPose=cameraPose.copy(zoom=(zoom+1).coerceAtMost(19.0));changed();return true}
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(b.pointerCount!=1||scaler.isInProgress)return true
            val pose=cameraPose;val world=512*2.0.pow(pose.zoom)*pixelScale;val angle=Math.toRadians(pose.bearing.toDouble())
            val longitude=pose.longitude+(dx*cos(angle)+dy*sin(angle))/world*360
            val my=(1-asinh(tan(Math.toRadians(pose.latitude)))/PI)/2+(dy*cos(angle)-dx*sin(angle))/world
            cameraPose=pose.copy(longitude=longitude.coerceIn(165.0,180.0),latitude=Math.toDegrees(atan(sinh(PI*(1-2*my)))).coerceIn(-49.0,-33.0));changed();return true
        }
    })
    private var lastAngle=0.0
    private var lastY=0f
    override fun onTouchEvent(event:MotionEvent):Boolean {
        scaler.onTouchEvent(event);gestures.onTouchEvent(event)
        if(event.pointerCount==2){
            val angle=atan2((event.getY(1)-event.getY(0)).toDouble(),(event.getX(1)-event.getX(0)).toDouble());val y=(event.getY(0)+event.getY(1))/2
            if(event.actionMasked==MotionEvent.ACTION_MOVE){
                val delta=atan2(sin(angle-lastAngle),cos(angle-lastAngle))
                cameraPose=cameraPose.copy(bearing=bearing+Math.toDegrees(delta).toFloat(),pitch=if(!scaler.isInProgress)(pitch+(y-lastY)*.1f).coerceIn(0f,85f)else pitch);changed()
            }
            lastAngle=angle;lastY=y
        }
        return true
    }
}
