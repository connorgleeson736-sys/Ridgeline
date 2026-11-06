package nz.ridgeline.nativeapp

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import kotlin.math.*

/** Visible source tiles only; one optional background transfer shares the existing transport. */
internal class MapLayers(private val view:TerrainView,private val loader:TileLoader):AutoCloseable {
    private companion object{
        /** Capacitor's topographic source: maxzoom 14, overzoomed beyond. */
        const val VECTOR_MAX_ZOOM=14
        const val PARALLEL_TILES=4
        const val GESTURE_PUBLISH_MILLIS=300L
    }
    private data class Tile(val body:TileBody,var mask:Set<String>,var features:List<VectorTile.Feature>)
    private val worker=Executors.newSingleThreadScheduledExecutor{run->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);run.run()},"RidgelineCartography")}
    private val main=Handler(Looper.getMainLooper())
    private val style=TopographyStyle(view.context.assets.open("topography-style.json").bufferedReader().use{it.readText()})
    private val tiles=LinkedHashMap<GpuUploadQueue.Id,Tile>()
    private val retry=HashMap<GpuUploadQueue.Id,Long>()
    private var current=emptyList<GpuUploadQueue.Id>()
    // Like MapLibre's vector source: several visible tiles load in parallel and
    // keep loading during gestures. (Previously one tile at a time, none while
    // moving, so tracks could take seconds to appear after stopping.)
    private val leases=ConcurrentHashMap<GpuUploadQueue.Id,TileTransport.Consumer>()
    private fun releaseAll(){for((id,owned)in leases.entries.toList())if(leases.remove(id,owned))owned.release(true)}
    private var lastPublishAt=0L
    private var timer:ScheduledFuture<*>?=null;private var publication:ScheduledFuture<*>?=null;private var retryWake:ScheduledFuture<*>?=null
    @Volatile private var closed=false
    @Volatile private var active=true
    private var lastKey:Any?=null;private var lastLabels=emptyList<TopographyStyle.Label>();private var atlas=NativeLabelAtlas.empty()
    private var lastLines=emptyList<TopographyStyle.Line>();private var linePacket=ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0);rewind()}
    private val plan=ByteBuffer.allocateDirect(32*12).order(ByteOrder.LITTLE_ENDIAN)
    private var boundary:BoundaryIndex?=null
    @Volatile private var diagnostics="Map layers: waiting for view"
    private var decoded=0L;private var lastDecodeMs=0L;private var lastBuildMs=0L;private var lastAtlasMs=0L
    private var displayState=loader.mapSettings.state
    private data class Projection(val revision:Long,val atlas:NativeLabelAtlas,val positions:FloatArray,val width:Int,val height:Int,val scale:Float)
    private val projectionRevision=java.util.concurrent.atomic.AtomicLong()
    private val projection=java.util.concurrent.atomic.AtomicReference<Projection?>()
    private val placementQueued=java.util.concurrent.atomic.AtomicBoolean()
    init{view.onLayerProjection={data,positions->
        projection.set(Projection(projectionRevision.incrementAndGet(),data,positions,view.width,view.height,view.pixelScale.toFloat()))
        schedulePlacement()
    }}
    private fun schedulePlacement(){
        if(closed||!placementQueued.compareAndSet(false,true))return
        runCatching{worker.execute{
            try{
                val next=projection.getAndSet(null)
                if(next!=null&&!closed&&active){val start=System.nanoTime();val selection=NativeLabels.place(next.atlas,next.positions,next.width,next.height,next.scale)
                    main.post{if(!closed&&active&&projectionRevision.get()==next.revision)view.placeMapLabels(next.atlas,selection)}
                    diagnostics=diagnostics.substringBefore("\nLabel placement:")+"\nLabel placement: ${selection.getInt(0)}/${next.atlas.labels.size} · ${(System.nanoTime()-start)/1_000_000} ms · background"
                }
            }finally{placementQueued.set(false);if(projection.get()!=null)schedulePlacement()}
        }}.onFailure{placementQueued.set(false)}
    }
    fun snapshot()=diagnostics
    fun refresh(){if(closed)return;runCatching{worker.execute{
        if(closed)return@execute
        timer?.cancel(false);val state=loader.mapSettings.state
        if(state!=displayState){displayState=state;lastKey=null;schedulePublication()}
        timer=worker.schedule({try{update()}catch(_:Exception){diagnostics="Topographic view selection failed; imagery and routes remain available."}},if(view.gestureInProgress)180 else 80,TimeUnit.MILLISECONDS)
    }}}
    private fun mask(state:MapSettings.State)=style.sourceLayers(state,view.planningPose.zoom)
    fun setActive(value:Boolean){active=value;if(value)refresh()else{releaseAll();timer?.cancel(false);publication?.cancel(false);retryWake?.cancel(false)}}
    private fun update(){
        if(closed||!active||view.width==0||view.height==0)return
        val state=loader.mapSettings.state;val pose=view.planningPose
        if(mask(state).isEmpty()&&!(state.borders&&state.base==MapSettings.Base.LINZ&&pose.zoom>=8)){releaseAll();current=emptyList();tiles.clear();lastKey=null;schedulePublication();return}
        val flat=if(!state.threeD)FlatSelection.plan((view.width/view.pixelScale).roundToInt(),(view.height/view.pixelScale).roundToInt(),pose,512.0,24,VECTOR_MAX_ZOOM)else null
        val wanted=if(flat!=null)flat.map{GpuUploadQueue.Id(it.z,it.x,it.y)}else{
            plan.rewind();val focus=view.surfaceFocusFor(pose)
            val n=NativeBridge.planFocused((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),(view.height/view.pixelScale).roundToInt().coerceAtLeast(1),pose.longitude,pose.latitude,pose.zoom,pose.bearing,pose.pitch,pose.elevation,24,512.0,VECTOR_MAX_ZOOM,1.0,focus?.longitude?:Double.NaN,focus?.latitude?:Double.NaN,focus?.range?:Double.NaN,plan)
            plan.rewind();List(n){GpuUploadQueue.Id(plan.int,plan.int,plan.int)}
        }
        if(current.toSet()!=wanted.toSet()){current=wanted;tiles.keys.retainAll(wanted.toSet()+LayerCover.display(wanted,tiles.keys).map{it.source});retry.keys.retainAll(wanted.toSet());for((id,owned)in leases.entries.toList())if(id !in wanted&&leases.remove(id,owned))owned.release(true);lastKey=null}
        current=wanted
        if(mask(state).isEmpty()){releaseAll();tiles.clear()}
        schedulePublication();pump()
    }
    private fun pump(){
        if(closed||!active||mask(loader.mapSettings.state).isEmpty())return
        val now=System.currentTimeMillis()
        while(leases.size<PARALLEL_TILES){
            val id=current.firstOrNull{it !in tiles&&!leases.containsKey(it)&&(retry[it]?:0)<=now}?:break
            val owned=loader.layerConsumer();leases[id]=owned
            if(closed||!active){leases.remove(id,owned);owned.release(true);return}
            val bodyFuture=try{loader.layerBody(owned,loader.vectorUrl(id.z,id.x,id.y))}catch(_:IllegalStateException){leases.remove(id,owned);owned.release(true);return}
            bodyFuture.whenComplete{body,error->runCatching{worker.execute{
                if(!leases.remove(id,owned)){owned.close();return@execute};owned.close()
                if(closed||id !in current){pump();return@execute}
                if(error==null&&body!=null&&body.status in 200..299){
                    try{val start=System.nanoTime();val enabled=mask(loader.mapSettings.state);val features=VectorTile.decode(body.bytes,id.z,id.x,id.y,enabled);tiles[id]=Tile(body,enabled,features);decoded++;lastDecodeMs=(System.nanoTime()-start)/1_000_000;lastKey=null}
                    catch(_:Exception){retry[id]=System.currentTimeMillis()+30000;diagnostics="Map layer data could not be decoded; imagery and routes remain available."}
                }else{retry[id]=System.currentTimeMillis()+15000;diagnostics="Topographic data unavailable · check connection or LINZ access."}
                // Admit the next bodies before CPU layout/atlas work; never serialize HTTP behind painting.
                pump();schedulePublication()
            }}}
        }
        if(leases.isEmpty()){
            val wake=current.filter{it !in tiles}.mapNotNull{retry[it]}.minOrNull()
            if(wake!=null&&(retryWake==null||retryWake!!.isDone))retryWake=worker.schedule({retryWake=null;pump()},(wake-now).coerceAtLeast(1),TimeUnit.MILLISECONDS)
        }
    }
    private fun schedulePublication(delay:Long=60){if(closed||publication!=null&&!publication!!.isDone)return;publication=worker.schedule({publication=null;try{publish()}catch(_:Exception){diagnostics="Visible topographic data exceeds the current layer budget or could not be prepared; imagery and routes remain available.";lastKey=null}},delay.coerceAtLeast(1),TimeUnit.MILLISECONDS)}
    private fun publish(){
        if(closed||!active)return
        // During gestures, publish newly arrived overlay tiles at a bounded rate
        // (draping runs on background workers), instead of freezing until the
        // camera stops as before.
        val now=android.os.SystemClock.uptimeMillis()
        if(view.gestureInProgress&&now-lastPublishAt<GESTURE_PUBLISH_MILLIS){schedulePublication(GESTURE_PUBLISH_MILLIS-(now-lastPublishAt));return}
        lastPublishAt=now
        val state=loader.mapSettings.state;val pose=view.planningPose;val zoom=floor(pose.zoom*4)/4
        val ordered=current.sortedWith(compareBy<GpuUploadQueue.Id>{it.z}.thenBy{it.x}.thenBy{it.y})
        val key=listOf(state,ordered,tiles.mapValues{it.value.body.stamp?.fingerprint?:it.value.body.bytes.hashCode()},zoom)
        if(key==lastKey)return;lastKey=key
        val start=System.nanoTime();val lines=ArrayList<TopographyStyle.Line>();val labels=ArrayList<TopographyStyle.Label>();val enabled=mask(state)
        val patches=LayerCover.display(ordered,tiles.keys);val styled=HashMap<GpuUploadQueue.Id,TopographyStyle.Output>()
        tiles.keys.retainAll(current.toSet()+patches.map{it.source})
        if(state.overlays())for(patch in patches){val id=patch.source;val tile=tiles[id]?:continue
            if(tile.mask!=enabled){tile.features=VectorTile.decode(tile.body.bytes,id.z,id.x,id.y,enabled);tile.mask=enabled;decoded++}
            // MapLibre evaluates ["zoom"] in filters at the tile's own zoom, layer
            // ranges and paint at the camera zoom.
            val output=styled.getOrPut(id){style.build(tile.features,state,zoom,id.z.toDouble())};val b=patch.clip.bounds
            for(line in output.lines)for(path in clip(line.points,b))lines.add(line.copy(points=path))
            labels.addAll(output.labels.filter{it.point.x>=b[0]&&it.point.x<b[2]&&it.point.y>=b[1]&&it.point.y<b[3]})
        }
        if(state.borders&&state.base==MapSettings.Base.LINZ&&zoom>=8){
            val parts=boundary?:BoundaryIndex(readBoundary()).also{boundary=it}
            for(id in ordered)for(clipped in parts.fragments(id)){
                lines.add(TopographyStyle.Line(clipped,0x8c111111.toInt(),1f,.2f,3.8f))
                if(zoom>=11&&clipped.size>1){val p=clipped[clipped.size/2];labels.add(TopographyStyle.Label(p,"LINZ imagery | Esri World Imagery",10f,0xffdddddd.toInt(),85.0,key="boundary:${p.x}:${p.y}"))}
            }
        }
        // A camera-dependent atlas order repainted/reuploaded the same strings on pans.
        // Fixed priority/key order keeps atlas slots stable; screen placement still follows camera.
        val chosen=labels.distinctBy{it.key}.sortedWith(compareBy<TopographyStyle.Label>{it.priority}.thenBy{it.key}).take(512)
        // Match MapLibre layer-major paint order across all visible tiles.
        lines.sortBy{it.order}
        if(lines!=lastLines){val packed=pack(lines);lastLines=lines;linePacket=packed}
        lastBuildMs=(System.nanoTime()-start)/1_000_000
        if(chosen!=lastLabels){val a=System.nanoTime();atlas=NativeLabels.atlas(chosen,view.context.assets);lastLabels=chosen;lastAtlasMs=(System.nanoTime()-a)/1_000_000}
        val batch=NativeLayerBatch(linePacket,atlas)
        diagnostics="Topography tiles: ${tiles.size}/${current.size} · decoded $decoded · one shared background transfer\nVector decode: $lastDecodeMs ms · layer build: $lastBuildMs ms · font atlas: $lastAtlasMs ms\nLines: ${lines.size} · label candidates ${atlas.labels.size} · atlas ${atlas.width}×${atlas.height}"
        main.post{if(!closed)view.mapLayers(batch)}
    }
    private fun readBoundary():List<List<VectorTile.Point>>{
        val json=view.context.assets.open("imagery-boundary.geojson").bufferedReader().use{JSONObject(it.readText())};val result=ArrayList<List<VectorTile.Point>>()
        val features=json.getJSONArray("features");for(i in 0 until features.length()){val geo=features.getJSONObject(i).getJSONObject("geometry");val paths=geo.getJSONArray("coordinates");for(j in 0 until paths.length()){val path=paths.getJSONArray(j);result.add(List(path.length()){k->val p=path.getJSONArray(k);VectorTile.Point((p.getDouble(0)+180)/360,(1-asinh(tan(Math.toRadians(p.getDouble(1))))/PI)/2)})}}
        return result
    }
    private fun pack(lines:List<TopographyStyle.Line>):ByteBuffer{
        val total=lines.sumOf{it.points.size};require(lines.size<=32768&&total<=500000){"Visible vector geometry exceeds safe upload budget"}
        return ByteBuffer.allocateDirect(4+lines.size*20+total*16).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(lines.size);for(line in lines){putInt(line.points.size);putInt(line.color);putFloat(line.width);putFloat(line.dash);putFloat(line.gap);for(p in line.points){putDouble(p.x);putDouble(p.y)}};rewind()}
    }
    private fun clip(path:List<VectorTile.Point>,box:DoubleArray)=BoundaryIndex.clip(path,box)
    override fun close(){closed=true;timer?.cancel(false);publication?.cancel(false);retryWake?.cancel(false);releaseAll();worker.shutdownNow();view.onLayerProjection={_,_->}}
}
