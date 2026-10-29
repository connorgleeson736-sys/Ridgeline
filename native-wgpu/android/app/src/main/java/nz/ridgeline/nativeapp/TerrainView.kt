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
    private val cameraPreferences=context.getSharedPreferences("camera",0)
    @Volatile var cameraPose=runCatching{
        CameraPose(Double.fromBits(cameraPreferences.getLong("lon",170.14219572.toBits())),Double.fromBits(cameraPreferences.getLong("lat",(-43.5955671).toBits())),Double.fromBits(cameraPreferences.getLong("zoom",11.9.toBits())),cameraPreferences.getFloat("bearing",-24f),cameraPreferences.getFloat("pitch",58f),cameraPreferences.getFloat("elevation",3724f)).also{
            require(it.longitude in 165.0..180.0&&it.latitude in -49.0..-33.0&&it.zoom in 4.0..19.0&&it.bearing.isFinite()&&it.pitch in 0f..85f&&it.elevation.isFinite())
        }
    }.getOrElse{CameraPose()};private set
    @Volatile private var groundedPose:CameraPose?=null
    @Volatile private var presentedPose:CameraPose?=null
    internal fun groundedFrameReady()=groundedPose==cameraPose&&presentedPose==cameraPose
    val longitude get()=cameraPose.longitude
    val latitude get()=cameraPose.latitude
    val zoom get()=cameraPose.zoom
    val bearing get()=cameraPose.bearing
    val pitch get()=cameraPose.pitch
    val centerElevation get()=cameraPose.elevation
    val pixelScale:Double get()=resources.displayMetrics.density.toDouble()
    val renderZoom:Double get()=zoom+ln(pixelScale)/ln(2.0)
    var onMapTap:((Double,Double,Int)->Unit)?=null
    var onWaypointDragStart:((Int)->Boolean)?=null
    var onWaypointDrag:((Coordinate)->Unit)?=null
    var onWaypointDragEnd:((Boolean)->Unit)?=null
    @Volatile private var routePacket:ByteBuffer?=null
    private var appliedRoutes:ByteBuffer?=null
    private val projectedBytes=ByteBuffer.allocateDirect(2000*12).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var projected=FloatArray(0)
    fun projectedWaypoints()=projected
    fun routes(paths:List<Triple<List<Coordinate>,Int,Int>>){
        val buffer=ByteBuffer.allocateDirect(4+paths.sumOf{12+it.first.size*16}).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(paths.size)
        for((points,color,selected)in paths){buffer.putInt(points.size);buffer.putInt(color);buffer.putInt(selected);for(p in points){buffer.putDouble(p.longitude);buffer.putDouble(p.latitude)}}
        buffer.rewind();routePacket=buffer;requestFrame()
    }
    private fun nearestWaypoint(x:Double,y:Double):Int {
        val points=projected;val radius=20*pixelScale;var best=radius*radius;var found=-1
        for(i in 0 until points.size/3)if(points[i*3+2]>0){val dx=x-points[i*3];val dy=y-points[i*3+1];val d=dx*dx+dy*dy;if(d<best){best=d;found=i}}
        return found
    }
    fun focus(points:List<Coordinate>){
        if(points.isEmpty())return
        val minLon=points.minOf{it.longitude};val maxLon=points.maxOf{it.longitude}
        val minLat=points.minOf{it.latitude};val maxLat=points.maxOf{it.latitude}
        val span=max((maxLon-minLon)/360,(asinh(tan(Math.toRadians(maxLat)))-asinh(tan(Math.toRadians(minLat))))/(2*PI))
        val size=min(width/pixelScale,height/pixelScale)*.65
        val z=if(span>0)ln(size/(512*span))/ln(2.0) else 15.0
        cameraPose=cameraPose.copy(longitude=(minLon+maxLon)/2,latitude=(minLat+maxLat)/2,zoom=z.coerceIn(4.0,19.0),pitch=min(pitch,45f));changed()
    }
    fun goTo(point:Coordinate){cameraPose=cameraPose.copy(longitude=point.longitude,latitude=point.latitude);changed()}
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
        val packet=routePacket
        if(packet!=null&&packet!==appliedRoutes&&NativeBridge.routes(handle,packet))appliedRoutes=packet
        val pose=cameraPose // One immutable snapshot, no mixed camera revisions.
        val shown=NativeBridge.render(handle,width,height,pose.longitude,pose.latitude,pose.zoom+ln(pixelScale)/ln(2.0),pose.zoom,pose.bearing,pose.pitch,pose.elevation)
        if(shown){presentationFailures=0;presentedPose=pose
            val count=NativeBridge.waypoints(handle,projectedBytes)
            projectedBytes.rewind();projected=FloatArray(count*3){projectedBytes.float}
        }else {
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
            handle=created;appliedRoutes=null;presentationFailures=0
            if(handle!=0L){val am=context.getSystemService(android.app.ActivityManager::class.java);NativeBridge.tileBudget(handle,if(am.isLowRamDevice)32 else (am.memoryClass/2).coerceIn(64,128))}
            post{onStatus(if(created==0L)"Native GPU initialization failed"else "Loading terrain and imagery…")};requestFrame()
        }
        changed()
    }
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){changed()}
    override fun surfaceDestroyed(holder:SurfaceHolder) {
        live=false;presentedPose=null;generation.incrementAndGet();residency=Residency(LongArray(0),LongArray(0));queued.set(false)
        val done=CountDownLatch(1)
        gpu.post{choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()}
        done.await(5,TimeUnit.SECONDS)
    }
    fun setActive(value:Boolean){
        if(!value){val pose=cameraPose;cameraPreferences.edit().putLong("lon",pose.longitude.toBits()).putLong("lat",pose.latitude.toBits()).putLong("zoom",pose.zoom.toBits()).putFloat("bearing",pose.bearing).putFloat("pitch",pose.pitch).putFloat("elevation",pose.elevation).apply()}
        active=value;cancelFrames();if(value&&live)requestFrame()}
    fun close() {
        active=false;live=false;generation.incrementAndGet();uploads.clear();queued.set(false)
        gpu.post{choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}
    }
    fun updateUploadDemand(geometry:Set<GpuUploadQueue.Id>,images:Set<GpuUploadQueue.Id>){uploads.updateDemand(geometry,images)}
    fun upload(z:Int,x:Int,y:Int,heights:ByteBuffer,rgba:ByteBuffer){if(uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),heights,rgba)))requestFrame()}
    fun uploadGeometry(z:Int,x:Int,y:Int,data:ByteBuffer){if(uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),data,GpuUploadQueue.EMPTY,GpuUploadQueue.Kind.GEOMETRY)))requestFrame()}
    fun uploadImage(z:Int,x:Int,y:Int,data:ByteBuffer){if(uploads.offer(GpuUploadQueue.Tile(GpuUploadQueue.Id(z,x,y),GpuUploadQueue.EMPTY,data,GpuUploadQueue.Kind.IMAGE)))requestFrame()}
    internal fun checkMipmapContent(callback:(IntArray?)->Unit){gpu.post{
        val output=ByteBuffer.allocateDirect(108).order(ByteOrder.LITTLE_ENDIAN)
        val result=if(live&&handle!=0L&&NativeBridge.mipmapProof(handle,output))IntArray(27){output.int}else null
        post{callback(result)}
    }}
    fun pick(x:Double,y:Double,callback:(DoubleArray?)->Unit) {
        gpu.post {
            val output=ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
            val point=if(live&&handle!=0L&&NativeBridge.pick(handle,x,y,output))DoubleArray(3){output.double}else null
            post{callback(point)}
        }
    }
    fun centerHeight(value:Float){
        if(!value.isFinite())return
        val next=cameraPose.copy(elevation=value);groundedPose=next
        if(next!=cameraPose){cameraPose=next;changed()}
    }
    fun zoomBy(delta:Double){
        if(!delta.isFinite())return
        val next=cameraPose.copy(zoom=(zoom+delta).coerceIn(4.0,19.0))
        if(next!=cameraPose){cameraPose=next;changed()}
    }
    fun northUp(){
        if(bearing!=0f){cameraPose=cameraPose.copy(bearing=0f);changed()}
    }
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            zoomBy(ln(detector.scaleFactor.toDouble())/ln(2.0));return true
        }
    })
    private var draggingWaypoint=false
    private var dragEpoch=0L
    private var dragBusy=false
    private var dragTarget:Triple<Double,Double,Boolean>?=null
    private fun dragPick(x:Double,y:Double,finish:Boolean){
        dragTarget=Triple(x,y,finish);drainDrag()
    }
    private fun drainDrag(){
        if(dragBusy)return
        val target=dragTarget?:return;dragTarget=null;dragBusy=true;val token=dragEpoch
        pick(target.first,target.second){hit->
            dragBusy=false
            if(token==dragEpoch&&draggingWaypoint){
                if(hit!=null)onWaypointDrag?.invoke(Coordinate(hit[0],hit[1]))
                if(target.third&&dragTarget==null){draggingWaypoint=false;onWaypointDragEnd?.invoke(true)}else drainDrag()
            }
        }
    }
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {onMapTap?.invoke(e.x.toDouble(),e.y.toDouble(),nearestWaypoint(e.x.toDouble(),e.y.toDouble()));return true}
        override fun onLongPress(e:MotionEvent){val index=nearestWaypoint(e.x.toDouble(),e.y.toDouble());if(index>=0&&onWaypointDragStart?.invoke(index)==true){dragEpoch++;draggingWaypoint=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)}}
        override fun onDoubleTap(e:MotionEvent):Boolean{zoomBy(1.0);return true}
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(b.pointerCount!=1||scaler.isInProgress)return true
            val pose=cameraPose;val world=512*2.0.pow(pose.zoom)*pixelScale
            val before=CameraMath.offset(width,height,pose.bearing,pose.pitch,b.x+dx.toDouble(),b.y+dy.toDouble())
            val after=CameraMath.offset(width,height,pose.bearing,pose.pitch,b.x.toDouble(),b.y.toDouble())
            if(before!=null&&after!=null){
                val longitude=pose.longitude+(before.first-after.first)/world*360
                val my=CameraMath.mercatorY(pose.latitude)-(before.second-after.second)/world
                val next=pose.copy(longitude=longitude.coerceIn(165.0,180.0),latitude=CameraMath.latitude(my).coerceIn(-49.0,-33.0))
                if(next!=pose){cameraPose=next;changed()}
            };return true
        }
    })
    private var lastAngle=0.0
    private var lastY=0f
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(draggingWaypoint){
            if(event.actionMasked==MotionEvent.ACTION_CANCEL||event.pointerCount>1){dragEpoch++;draggingWaypoint=false;dragTarget=null;onWaypointDragEnd?.invoke(false)}
            else {if(event.actionMasked==MotionEvent.ACTION_MOVE||event.actionMasked==MotionEvent.ACTION_UP)dragPick(event.x.toDouble(),event.y.toDouble(),event.actionMasked==MotionEvent.ACTION_UP);return true}
        }
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
