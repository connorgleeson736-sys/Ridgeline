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
    @Volatile private var presentedFrom:CameraPose?=null
    val planningPose:CameraPose get()=if(presentedFrom==cameraPose)presentedPose?:cameraPose else cameraPose
    val gestureInProgress get()=gestureActive
    @Volatile private var lastBacklog=0
    val canPrepareAhead get()=live&&active&&!gestureActive&&lastBacklog==0&&!uploads.isNotEmpty()
    fun checkPreparationRoom(callback:(Boolean)->Unit){gpu.post{
        if(live&&active&&handle!=0L&&!gestureActive&&presentationFailures==0){lastBacklog=NativeBridge.gpuBacklog(handle);callback(canPrepareAhead)}else callback(false)
    }}
    fun updateWarmDemand(geometry:Set<GpuUploadQueue.Id>,images:Set<GpuUploadQueue.Id>){uploads.updateWarm(geometry,images)}
    fun hasResidentCoverage(id:GpuUploadQueue.Id,geometry:Boolean,sharp:Boolean=false):Boolean {
        val current=residency
        return (if(geometry)current.geometryCoverage else current.imageCoverage).complete(id,!sharp)
    }
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
    private data class Residency(val geometry:LongArray,val images:LongArray,
        val geometryIds:Set<GpuUploadQueue.Id> = ids(geometry),val imageIds:Set<GpuUploadQueue.Id> = ids(images),
        val geometryCoverage:ResidentCoverage.Index = ResidentCoverage.Index(geometryIds),
        val imageCoverage:ResidentCoverage.Index = ResidentCoverage.Index(imageIds)){
        fun updated(ground:LongArray,photos:LongArray):Residency {
            val sameGround=geometry.contentEquals(ground);val samePhotos=images.contentEquals(photos)
            if(sameGround&&samePhotos)return this
            val g=if(sameGround)geometryIds else ids(ground);val i=if(samePhotos)imageIds else ids(photos)
            return Residency(ground,photos,g,i,if(sameGround)geometryCoverage else ResidentCoverage.Index(g),if(samePhotos)imageCoverage else ResidentCoverage.Index(i))
        }
        companion object {
            private fun ids(rows:LongArray)=rows.map{GpuUploadQueue.Id((it ushr 44).toInt(),((it ushr 22)and 0x3fffff).toInt(),(it and 0x3fffff).toInt())}.toSet()
        }
    }
    @Volatile private var residency=Residency(LongArray(0),LongArray(0))
    private val residencyBytes=ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
    fun isResident(id:GpuUploadQueue.Id,geometry:Boolean)=if(geometry)id in residency.geometryIds else id in residency.imageIds
    internal fun residentSourceCounts()=residency.let{it.geometry.size to it.images.size}
    private fun refreshResidency(){
        val token=generation.get();residencyBytes.rewind()
        if(NativeBridge.residency(handle,residencyBytes)){
            val g=residencyBytes.int;val i=residencyBytes.int
            val next=residency.updated(LongArray(g){residencyBytes.long},LongArray(i){residencyBytes.long})
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
    private val retryWake=Runnable{requestFrame()}
    private val uploadAdmission=UploadAdmission()
    private val clearanceBytes=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
    private fun drawFrame() {
        queued.set(false)
        if(!live||!active)return
        gpu.removeCallbacks(retryWake)
        if(handle==0L){recoverGpu();return}
        // Nonblocking completion polling measures real outstanding submissions.
        // Keep the newest camera; do not queue more old frames behind a busy GPU.
        val backlog=NativeBridge.gpuBacklog(handle);lastBacklog=backlog
        if(backlog>=2){requestFrame();return}
        val demand=demandPacket
        if(demand!=null&&demand!==appliedDemand&&NativeBridge.demand(handle,demand)){appliedDemand=demand;safetyPose=null}
        val started=System.nanoTime();val allowance=uploadAdmission.allowance(gestureActive)
        var count=0;var bytes=0;var accepted=false;var geometryChanged=false
        while(presentationFailures==0){
            val next=uploads.peek()?:break
            if(!uploadAdmission.allows(next.kind,System.nanoTime()-started,count,bytes,next.bytes,allowance,backlog))break
            val tile=uploads.take(1,650*1024).firstOrNull()?:break
            val id=tile.id;val uploadStarted=System.nanoTime()
            val ok=when(tile.kind){
                GpuUploadQueue.Kind.COMBINED->NativeBridge.upload(handle,id.z,id.x,id.y,tile.heights,tile.rgba)
                GpuUploadQueue.Kind.GEOMETRY->NativeBridge.uploadGeometry(handle,id.z,id.x,id.y,tile.heights)
                GpuUploadQueue.Kind.IMAGE->NativeBridge.uploadImage(handle,id.z,id.x,id.y,tile.rgba)
            }
            uploadAdmission.uploaded(tile.kind,System.nanoTime()-uploadStarted)
            count++;bytes+=tile.bytes;accepted=accepted||ok;geometryChanged=geometryChanged||(ok&&tile.kind!=GpuUploadQueue.Kind.IMAGE)
        }
        if(count>0)refreshResidency()
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
        val lift=if(floor.isFinite())max(0.0,(floor-requested.elevation).toDouble())else 0.0
        var pose=if(gestureActive){
            val held=max(gestureHeldElevation,if(floor.isFinite())floor else gestureHeldElevation)
            if(abs(held-requested.elevation)>.01f)requested.copy(elevation=held)else requested
        }else CameraMath.clearTerrain(requested,height,pixelScale,lift)
        if(!gestureActive&&pose!=requested){
            for(pass in 0 until 2){
                clearanceBytes.rewind()
                if(!NativeBridge.cameraSafety(handle,width,height,pose.longitude,pose.latitude,pose.zoom+log2(pixelScale),pose.bearing,pose.pitch,pose.elevation,clearanceBytes))break
                val nextFloor=clearanceBytes.float
                if(!nextFloor.isFinite()||nextFloor<=pose.elevation+.01f)break
                pose=CameraMath.clearTerrain(pose,height,pixelScale,(nextFloor-pose.elevation).toDouble())
            }
        }
        // Refined terrain changes the pivot, not the physical eye. No per-frame
        // height-follow animation, and no collision correction fed back at rest.
        val shouldRecenter=ground.isFinite()&&groundZoom>=0&&!gestureActive&&(geometryChanged||recenterPending)
        val corrected=pose
        if(gestureActive&&corrected!=requested)post{if(cameraPose==requested&&live&&active){
            cameraPose=corrected;applyGesture()
        }}
        val renderStarted=System.nanoTime()
        val shown=NativeBridge.render(handle,width,height,pose.longitude,pose.latitude,pose.zoom+ln(pixelScale)/ln(2.0),pose.zoom,pose.bearing,pose.pitch,pose.elevation)
        uploadAdmission.rendered(System.nanoTime()-renderStarted)
        if(shown>0){presentationFailures=0
            val previous=presentedPose;presentedPose=pose;presentedFrom=requested
            val rendered=pose
            if(previous!=pose&&pose!=requested)post{if(cameraPose==requested&&live&&active)onCamera()}
            if(shouldRecenter){
            recenterPending=false
            val token=gestureEpoch
            pick(width*.5,height*.5){point->if(point!=null&&token==gestureEpoch&&!gestureActive&&cameraPose==requested){
                val recentered=CameraMath.recenter(rendered,height,pixelScale,point[2])
                if(recentered!=null){cameraPose=recentered;groundedPose=recentered;changed()}
                else if(abs(point[2]-rendered.elevation)<.001)groundedPose=requested
            }}
        }
            val count=NativeBridge.waypoints(handle,projectedBytes)
            projectedBytes.rewind();projected=FloatArray(count*3){projectedBytes.float}
        }else {
            presentationFailures++
            // Stop feeding uploads while a busy GPU drains; transient failure
            // must never permanently stop presentation after an arbitrary count.
            if(shown==-6||(shown==-5&&presentationFailures>=3)||(shown==-4&&presentationFailures>=6)||shown==0&&presentationFailures>=6){
                recoverGpu()
            }else requestFrame(when(shown){-2->250L;-3->16L;else->minOf(250L,16L shl minOf(presentationFailures,4))})
        }
        gpu.removeCallbacks(adaptiveWake)
        if(shown>1)gpu.postDelayed(adaptiveWake,(shown-2).toLong())
        if(shown>0&&uploads.isNotEmpty())requestFrame()
    }
    private var lastRecovery=0L
    private fun recoverGpu(){
        val now=android.os.SystemClock.uptimeMillis()
        if(now-lastRecovery<2000){requestFrame(250);return}
        lastRecovery=now
        if(!live||!active)return
        if(handle!=0L)NativeBridge.destroy(handle)
        NativeBridge.textureBackend(context.getSharedPreferences("display",0).getInt("textureBackend",0))
        handle=NativeBridge.create(holder.surface,width,height)
        residency=Residency(LongArray(0),LongArray(0));safetyPose=null;safetyValid=false;appliedRoutes=null;appliedDemand=null
        if(handle!=0L){
            val am=context.getSystemService(android.app.ActivityManager::class.java)
            NativeBridge.tileBudget(handle,if(am.isLowRamDevice)32 else(am.memoryClass/2).coerceIn(64,128))
            presentationFailures=0;post{onStatus("");onData()};requestFrame()
        }else{post{onStatus("GPU recovering…")};requestFrame(250)}
    }
    private fun requestFrame(delay:Long=0) {
        if(delay>0){if(live&&active)gpu.post{gpu.removeCallbacks(retryWake);gpu.postDelayed(retryWake,delay)};return}
        if(live&&active&&queued.compareAndSet(false,true))gpu.post {
            if(live&&active)choreographer.postFrameCallbackDelayed(vsync,delay)else queued.set(false)
        }
    }
    private fun cancelFrames(){queued.set(false);gpu.post{gpu.removeCallbacks(adaptiveWake);gpu.removeCallbacks(retryWake);choreographer.removeFrameCallback(vsync)}}
    private fun changed(){requestFrame();onCamera()}
    init{holder.addCallback(this)}
    override fun surfaceCreated(holder:SurfaceHolder) {
        live=true;val token=generation.incrementAndGet();val surface=holder.surface
        gpu.post {
            if(!live||generation.get()!=token)return@post
            NativeBridge.textureBackend(context.getSharedPreferences("display",0).getInt("textureBackend",0))
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
        gpu.post{gpu.removeCallbacks(adaptiveWake);gpu.removeCallbacks(retryWake);choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0L;done.countDown()}
        done.await(5,TimeUnit.SECONDS)
    }
    fun setActive(value:Boolean){
        if(!value){stopMotion();gestureEpoch++;gestureActive=false;val pose=cameraPose;cameraPreferences.edit().putLong("lon",pose.longitude.toBits()).putLong("lat",pose.latitude.toBits()).putLong("zoom",pose.zoom.toBits()).putFloat("bearing",pose.bearing).putFloat("pitch",pose.pitch).putFloat("elevation",pose.elevation).apply()}
        active=value;cancelFrames();if(value&&live)requestFrame()}
    fun textureBackend(mode:Int){context.getSharedPreferences("display",0).edit().putInt("textureBackend",mode.coerceIn(0,2)).apply();gpu.post{lastRecovery=0L;recoverGpu()}}
    fun memoryPressure(){gpu.post{
        if(handle!=0L){NativeBridge.releaseUnused(handle);refreshResidency();safetyPose=null}
    }}
    fun close() {
        stopMotion();active=false;live=false;generation.incrementAndGet();uploads.clear();queued.set(false)
        gpu.post{gpu.removeCallbacks(adaptiveWake);gpu.removeCallbacks(retryWake);choreographer.removeFrameCallback(vsync);if(handle!=0L)NativeBridge.destroy(handle);handle=0;thread.quitSafely()}
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
    fun updateRankedUploadDemand(geometry:Map<GpuUploadQueue.Id,Int>,images:Map<GpuUploadQueue.Id,Int>){
        updateUploadDemand(geometry.keys,images.keys)
        // Camera-only changes can reorder a plan without changing membership.
        uploads.updateRankedDemand(geometry,images)
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
    @Volatile private var gestureActive=false
    @Volatile private var gestureHeldElevation=3724f
    @Volatile private var recenterPending=true
    // Used by controlled fixtures and explicit initialization, never terrain-follow.
    fun centerHeight(value:Float){if(value.isFinite()&&!gestureActive){cameraPose=cameraPose.copy(elevation=value);groundedPose=cameraPose;changed()}}
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
    private var tilting=false
    private fun beginGesture(x:Double,y:Double,time:Long=android.os.SystemClock.uptimeMillis(),keepHold:Boolean=false){
        stopMotion();gestureActive=true
        if(presentedFrom==cameraPose)presentedPose?.let{cameraPose=it}
        finishGesturePending=false
        val token=++gestureEpoch;touchX=x;touchY=y;touchZoom=zoom;touchBearing=bearing;touchPitch=pitch
        val pose=cameraPose
        if(!keepHold)gestureHeldElevation=pose.elevation
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
        recenterPending=true;requestFrame();onCamera()
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
                multiTouch=true;tilting=false
                val f=focusOf(event);beginGesture(f.first,f.second,event.eventTime,true)
                twoFingers.start(event.getX(0)/pixelScale,event.getY(0)/pixelScale,event.getX(1)/pixelScale,event.getY(1)/pixelScale)
            }
            MotionEvent.ACTION_MOVE->if(event.pointerCount==2){
                val f=focusOf(event)
                val delta=twoFingers.move(event.getX(0)/pixelScale,event.getY(0)/pixelScale,event.getX(1)/pixelScale,event.getY(1)/pixelScale,event.eventTime)
                if(delta.tilting&&!tilting){
                    // MapLibre touchPitch excludes pan/zoom/rotate and pivots about
                    // the map centre, not the arbitrary finger centroid.
                    tilting=true;gestureEpoch++;anchorPending=false;motion.clear()
                    touchX=width*.5;touchY=height*.5
                    gestureAnchor=CameraMath.Anchor((longitude+180)/360,CameraMath.mercatorY(latitude),centerElevation.toDouble())
                    recordMotion(event.eventTime)
                }
                touchZoom=(touchZoom+delta.zoom).coerceIn(4.0,19.0)
                touchBearing+=delta.bearing.toFloat();touchPitch=(touchPitch+delta.pitch.toFloat()).coerceIn(0f,85f)
                if(!delta.tilting){touchX=f.first;touchY=f.second}
                applyGesture();recordMotion(event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP->{
                val coast=motion.finish(event.eventTime)
                val continuation=if(event.pointerCount==2&&coast!=null)gestureAnchor?.let{MultiRelease(event.eventTime,coast,it,touchX,touchY,touchZoom,touchBearing,touchPitch)}else null
                val f=focusOf(event,event.actionIndex);multiTouch=event.pointerCount-1>1;beginGesture(f.first,f.second,event.eventTime,true)
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
