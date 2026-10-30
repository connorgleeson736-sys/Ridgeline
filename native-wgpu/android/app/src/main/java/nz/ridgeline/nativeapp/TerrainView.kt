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
    internal fun frameReady()=presentedPose==cameraPose
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
    @Volatile private var demandPacket:ByteBuffer?=null
    private var appliedDemand:ByteBuffer?=null
    private var uploadDemand:Pair<Set<GpuUploadQueue.Id>,Set<GpuUploadQueue.Id>>?=null
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
    private val safetyBytes=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
    private var safetyPose:CameraPose?=null
    private var safetySize=0L
    private var safetyValid=false
    // Created and used exclusively on the GPU HandlerThread's Looper.
    private val choreographer by lazy{Choreographer.getInstance()}
    private val vsync=Choreographer.FrameCallback{drawFrame()}
    private val adaptiveWake=Runnable{requestFrame()}
    private fun drawFrame() {
        queued.set(false)
        if(!live||!active||handle==0L)return
        // Bounded source count and input bytes per display frame (650KiB).
        val demand=demandPacket
        if(demand!=null&&demand!==appliedDemand&&NativeBridge.demand(handle,demand)){appliedDemand=demand;safetyPose=null}
        val rows=uploads.take(4,650*1024);var accepted=false;var geometryChanged=false
        for(tile in rows){val id=tile.id;val ok=when(tile.kind){
            GpuUploadQueue.Kind.COMBINED->NativeBridge.upload(handle,id.z,id.x,id.y,tile.heights,tile.rgba)
            GpuUploadQueue.Kind.GEOMETRY->NativeBridge.uploadGeometry(handle,id.z,id.x,id.y,tile.heights)
            GpuUploadQueue.Kind.IMAGE->NativeBridge.uploadImage(handle,id.z,id.x,id.y,tile.rgba)
        };accepted=accepted||ok;geometryChanged=geometryChanged||(ok&&tile.kind!=GpuUploadQueue.Kind.IMAGE)}
        if(rows.isNotEmpty())refreshResidency()
        if(accepted)post{onStatus("");onData()}
        val packet=routePacket
        if(packet!=null&&packet!==appliedRoutes&&NativeBridge.routes(handle,packet))appliedRoutes=packet
        val requested=cameraPose // One immutable snapshot, no mixed camera revisions.
        val size=(width.toLong() shl 32) or height.toLong()
        if(requested!=safetyPose||size!=safetySize||geometryChanged){
            safetyBytes.rewind();safetyValid=NativeBridge.cameraSafety(handle,width,height,requested.longitude,requested.latitude,requested.zoom+ln(pixelScale)/ln(2.0),requested.bearing,requested.pitch,requested.elevation,safetyBytes)
            safetyPose=requested;safetySize=size
        }
        safetyBytes.rewind()
        val floor=if(safetyValid)safetyBytes.float else Float.NEGATIVE_INFINITY
        val ground=if(safetyValid)safetyBytes.float else Float.NaN
        val groundZoom=if(safetyValid)safetyBytes.float.toInt() else -1
        val pose=if(floor.isFinite()&&floor>requested.elevation+.01f)requested.copy(elevation=floor)else requested
        val mx=(requested.longitude+180)/360;val my=CameraMath.mercatorY(requested.latitude)
        val targetZoom=uploadDemand?.first?.filter{val n=2.0.pow(it.z);kotlin.math.floor(mx*n).toInt()==it.x&&kotlin.math.floor(my*n).toInt()==it.y}?.maxOfOrNull{it.z}?:Int.MAX_VALUE
        if(pose!=requested||ground.isFinite()&&groundZoom>=targetZoom)post{
            if(cameraPose==requested&&live&&active){
                if(pose!=requested){elevationAnimation?.cancel();cameraPose=pose;if(gestureActive)applyGesture()else onCamera()}
                if(cameraPose==pose&&ground.isFinite()&&groundZoom>=targetZoom)centerHeight(max(ground,floor))
            }
        }
        val shown=NativeBridge.render(handle,width,height,pose.longitude,pose.latitude,pose.zoom+ln(pixelScale)/ln(2.0),pose.zoom,pose.bearing,pose.pitch,pose.elevation)
        if(shown>0){presentationFailures=0;presentedPose=pose
            val count=NativeBridge.waypoints(handle,projectedBytes)
            projectedBytes.rewind();projected=FloatArray(count*3){projectedBytes.float}
        }else {
            presentationFailures++
            if(presentationFailures<=8)requestFrame(minOf(250L,16L shl presentationFailures)) else post{onStatus("GPU presentation unavailable")}
        }
        gpu.removeCallbacks(adaptiveWake)
        if(shown>1)gpu.postDelayed(adaptiveWake,(shown-2).toLong())
        if(uploads.isNotEmpty())requestFrame()
    }
    private fun requestFrame(delay:Long=0) {
        if(live&&active&&queued.compareAndSet(false,true))gpu.post {
            if(live&&active)choreographer.postFrameCallbackDelayed(vsync,delay)else queued.set(false)
        }
    }
    private fun cancelFrames(){queued.set(false);gpu.post{gpu.removeCallbacks(adaptiveWake);choreographer.removeFrameCallback(vsync)}}
    private fun changed(){requestFrame();onCamera()}
    init{holder.addCallback(this)}
    override fun surfaceCreated(holder:SurfaceHolder) {
        live=true;val token=generation.incrementAndGet();val surface=holder.surface
        gpu.post {
            if(!live||generation.get()!=token)return@post
            val created=NativeBridge.create(surface,width,height)
            if(!live||generation.get()!=token){if(created!=0L)NativeBridge.destroy(created);return@post}
            handle=created;safetyPose=null;safetyValid=false;appliedRoutes=null;appliedDemand=null;presentationFailures=0
            if(handle!=0L){val am=context.getSystemService(android.app.ActivityManager::class.java);NativeBridge.tileBudget(handle,if(am.isLowRamDevice)32 else (am.memoryClass/2).coerceIn(64,128))}
            post{onStatus(if(created==0L)"Native GPU initialization failed"else "Loading terrain and imagery…")};requestFrame()
        }
        changed()
    }
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){changed()}
    override fun surfaceDestroyed(holder:SurfaceHolder) {
        live=false;presentedPose=null;generation.incrementAndGet();residency=Residency(LongArray(0),LongArray(0));queued.set(false)
        val done=CountDownLatch(1)
        gpu.post{gpu.removeCallbacks(adaptiveWake);choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()}
        done.await(5,TimeUnit.SECONDS)
    }
    fun setActive(value:Boolean){
        if(!value){stopMotion();elevationAnimation?.cancel();gestureEpoch++;gestureActive=false;val pose=cameraPose;cameraPreferences.edit().putLong("lon",pose.longitude.toBits()).putLong("lat",pose.latitude.toBits()).putLong("zoom",pose.zoom.toBits()).putFloat("bearing",pose.bearing).putFloat("pitch",pose.pitch).putFloat("elevation",pose.elevation).apply()}
        active=value;cancelFrames();if(value&&live)requestFrame()}
    fun memoryPressure(){gpu.post{
        if(handle!=0L){NativeBridge.releaseUnused(handle);refreshResidency();safetyPose=null}
    }}
    fun close() {
        stopMotion();elevationAnimation?.cancel();active=false;live=false;generation.incrementAndGet();uploads.clear();queued.set(false)
        gpu.post{gpu.removeCallbacks(adaptiveWake);choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}
    }
    fun updateUploadDemand(geometry:Set<GpuUploadQueue.Id>,images:Set<GpuUploadQueue.Id>){
        val next=geometry to images
        if(next==uploadDemand)return
        uploadDemand=next
        uploads.updateDemand(geometry,images)
        val buffer=ByteBuffer.allocateDirect(8+(geometry.size+images.size)*12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(geometry.size);buffer.putInt(images.size)
        for(id in geometry.toList()+images.toList()){buffer.putInt(id.z);buffer.putInt(id.x);buffer.putInt(id.y)}
        buffer.rewind();demandPacket=buffer;requestFrame()
    }
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
    private var gestureActive=false
    private var pendingGround:Float?=null
    private var elevationAnimation:android.animation.ValueAnimator?=null
    private var elevationTarget:Float?=null
    fun centerHeight(value:Float){
        if(gestureActive){pendingGround=value;return}
        if(!value.isFinite()||elevationAnimation?.isRunning==true&&elevationTarget==value)return
        val next=cameraPose.copy(elevation=value);groundedPose=next
        if(next==cameraPose)return
        elevationAnimation?.cancel()
        val start=cameraPose.elevation;elevationTarget=value
        elevationAnimation=android.animation.ValueAnimator.ofFloat(start,value).apply{
            duration=350
            addUpdateListener { cameraPose=cameraPose.copy(elevation=it.animatedValue as Float);requestFrame() }
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){groundedPose=cameraPose;onCamera()}})
            start()
        }
    }
    fun zoomBy(delta:Double){
        if(delta.isFinite())easeControls((zoom+delta).coerceIn(4.0,19.0),bearing,width*.5,height*.5)
    }
    fun northUp(){
        val difference=(((bearing+180f)%360f+360f)%360f)-180f
        if(abs(difference)>.001f)easeControls(zoom,bearing-difference,width*.5,height*.5)
    }
    private var programmaticEase=false
    private var pendingEase:GestureMotion.Coast?=null
    private fun easeControls(targetZoom:Double,targetBearing:Float,x:Double,y:Double){
        if(targetZoom==zoom&&targetBearing==bearing)return
        beginGesture(x,y)
        programmaticEase=true
        pendingEase=GestureMotion.Coast(300,0.0,0.0,targetZoom-zoom,(targetBearing-bearing).toDouble(),0.0)
    }
    // One terrain plane per gesture. Loading/refinement cannot change its sensitivity.
    private var gestureEpoch=0L
    private var gestureAnchor:CameraMath.Anchor?=null
    private var anchorPending=false
    private var finishGesturePending=false
    private var touchX=0.0
    private var touchY=0.0
    private var touchZoom=zoom
    private var touchBearing=bearing
    private var touchPitch=pitch
    private val motion=GestureMotion()
    private val twoFingers=TwoFingerGesture()
    private var motionAnimation:android.animation.ValueAnimator?=null
    private var pendingCoast:GestureMotion.Coast?=null
    private data class MultiRelease(val time:Long,val coast:GestureMotion.Coast,val anchor:CameraMath.Anchor,val x:Double,val y:Double,val zoom:Double,val bearing:Float,val pitch:Float)
    private var multiRelease:MultiRelease?=null
    private fun stopMotion(){multiRelease=null;programmaticEase=false;pendingEase=null;motionAnimation?.removeAllListeners();motionAnimation?.cancel();motionAnimation=null;pendingCoast=null;motion.clear()}
    private fun recordMotion(time:Long){motion.record(GestureMotion.Sample(time,touchX/pixelScale,touchY/pixelScale,touchZoom,touchBearing.toDouble(),touchPitch.toDouble()))}
    private fun startCoast(coast:GestureMotion.Coast,inertia:Boolean=true){
        val x=touchX;val y=touchY;val z=touchZoom;val b=touchBearing;val p=touchPitch
        motionAnimation=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
            duration=coast.duration
            interpolator=if(inertia)android.view.animation.PathInterpolator(0f,0f,.3f,1f)else android.view.animation.PathInterpolator(.25f,.1f,.25f,1f)
            addUpdateListener{val t=(it.animatedValue as Float).toDouble()
                touchX=x+coast.x*pixelScale*t;touchY=y+coast.y*pixelScale*t
                touchZoom=(z+coast.zoom*t).coerceIn(4.0,19.0);touchBearing=b+(coast.bearing*t).toFloat();touchPitch=(p+(coast.pitch*t).toFloat()).coerceIn(0f,85f)
                applyGesture()
            }
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){motionAnimation=null;programmaticEase=false;endGesture()}})
            start()
        }
    }
    private var multiTouch=false
    private fun beginGesture(x:Double,y:Double,time:Long=android.os.SystemClock.uptimeMillis()){
        stopMotion();elevationAnimation?.cancel();pendingGround=null;gestureActive=true
        finishGesturePending=false
        val token=++gestureEpoch;touchX=x;touchY=y;touchZoom=zoom;touchBearing=bearing;touchPitch=pitch
        val pose=cameraPose
        gestureAnchor=CameraMath.anchor(pose,width,height,pixelScale,x,y)
        anchorPending=true
        recordMotion(time)
        gpu.post{
            // Pick and pose are from the same presented frame, never a newer UI pose.
            val rendered=presentedPose
            val output=ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN)
            val hit=if(live&&handle!=0L&&NativeBridge.pick(handle,x,y,output))DoubleArray(3){output.double}else null
            post{
                if(token!=gestureEpoch||!gestureActive)return@post
                anchorPending=false
                if(rendered==pose&&hit!=null){
                    val real=CameraMath.anchor(pose,width,height,pixelScale,x,y,hit[2])
                    if(real!=null)gestureAnchor=real
                }
                applyGesture()
                val ease=pendingEase;pendingEase=null
                if(ease!=null)startCoast(ease,false)else if(finishGesturePending)endGesture()
            }
        }
    }
    private fun applyGesture(){
        if(anchorPending)return
        val pose=cameraPose.copy(zoom=touchZoom,bearing=touchBearing,pitch=touchPitch)
        val next=gestureAnchor?.let{CameraMath.anchored(pose,it,width,height,pixelScale,touchX,touchY)}?:return
        if(next!=cameraPose){cameraPose=next;changed()}
    }
    private fun endGesture(){
        if(programmaticEase)return
        if(anchorPending){finishGesturePending=true;return}
        finishGesturePending=false
        val coast=pendingCoast;pendingCoast=null
        if(coast!=null){startCoast(coast);return}
        gestureActive=false;gestureEpoch++;anchorPending=false;gestureAnchor=null
        pendingGround?.let{centerHeight(it)};pendingGround=null
        onCamera()
    }
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
        override fun onDoubleTap(e:MotionEvent):Boolean{easeControls((zoom+1).coerceIn(4.0,19.0),bearing,e.x.toDouble(),e.y.toDouble());return true}
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(b.pointerCount==1&&!multiTouch){multiRelease=null;touchX=b.x.toDouble();touchY=b.y.toDouble();applyGesture();recordMotion(b.eventTime)}
            return true
        }
    })
    private fun focusOf(event:MotionEvent,skip:Int=-1):Pair<Double,Double>{
        var x=0.0;var y=0.0;var count=0
        for(i in 0 until event.pointerCount)if(i!=skip){x+=event.getX(i);y+=event.getY(i);count++}
        return x/count to y/count
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(draggingWaypoint){
            if(event.actionMasked==MotionEvent.ACTION_CANCEL||event.pointerCount>1){dragEpoch++;draggingWaypoint=false;dragTarget=null;onWaypointDragEnd?.invoke(false)}
            else {if(event.actionMasked==MotionEvent.ACTION_MOVE||event.actionMasked==MotionEvent.ACTION_UP)dragPick(event.x.toDouble(),event.y.toDouble(),event.actionMasked==MotionEvent.ACTION_UP);if(event.actionMasked==MotionEvent.ACTION_UP)endGesture();return true}
        }
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);multiTouch=false;beginGesture(event.x.toDouble(),event.y.toDouble(),event.eventTime)}
            MotionEvent.ACTION_POINTER_DOWN->{
                multiTouch=true
                val f=focusOf(event);beginGesture(f.first,f.second,event.eventTime)
                twoFingers.start(event.getX(0)/pixelScale,event.getY(0)/pixelScale,event.getX(1)/pixelScale,event.getY(1)/pixelScale)
            }
            MotionEvent.ACTION_MOVE->if(event.pointerCount==2){
                val f=focusOf(event)
                val delta=twoFingers.move(event.getX(0)/pixelScale,event.getY(0)/pixelScale,event.getX(1)/pixelScale,event.getY(1)/pixelScale,event.eventTime)
                touchZoom=(touchZoom+delta.zoom).coerceIn(4.0,19.0)
                touchBearing+=delta.bearing.toFloat();touchPitch=(touchPitch+delta.pitch.toFloat()).coerceIn(0f,85f)
                if(!delta.tilting){touchX=f.first;touchY=f.second}
                applyGesture();recordMotion(event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP->{
                val coast=motion.finish(event.eventTime)
                val continuation=if(event.pointerCount==2&&coast!=null)gestureAnchor?.let{MultiRelease(event.eventTime,coast,it,touchX,touchY,touchZoom,touchBearing,touchPitch)}else null
                val f=focusOf(event,event.actionIndex);multiTouch=event.pointerCount-1>1;beginGesture(f.first,f.second,event.eventTime)
                multiRelease=continuation
            }
        }
        gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){
            if(event.actionMasked==MotionEvent.ACTION_CANCEL){gestureEpoch++;anchorPending=false;motion.clear();pendingCoast=null}
            else if(!programmaticEase){
                val continuation=multiRelease;multiRelease=null
                if(continuation!=null&&event.eventTime-continuation.time<=60){
                    gestureEpoch++;anchorPending=false;gestureAnchor=continuation.anchor
                    touchX=continuation.x;touchY=continuation.y;touchZoom=continuation.zoom;touchBearing=continuation.bearing;touchPitch=continuation.pitch
                    pendingCoast=continuation.coast
                }else pendingCoast=motion.finish(event.eventTime)
            }
            endGesture();parent?.requestDisallowInterceptTouchEvent(false)}
        return true
    }
}
