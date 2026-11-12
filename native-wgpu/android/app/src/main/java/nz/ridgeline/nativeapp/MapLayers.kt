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
    internal companion object{
        /** Capacitor's topographic source: maxzoom 14, overzoomed beyond. */
        const val VECTOR_MAX_ZOOM=14
        const val PARALLEL_TILES=4
        /** Newly arrived overlay tiles appear within this time while moving. */
        const val GESTURE_PUBLISH_MILLIS=100L
        /** View selection runs at most this often, never postponed by movement. */
        const val SELECTION_MILLIS=50L
        /** Hunting permit area outline and name colours (blaze orange). */
        val HUNTING_LINE=0xF0FF6A13.toInt();val HUNTING_TEXT=0xFFFFA25F.toInt()
        /** Styled zoom bands kept per tile. */
        const val STYLES_PER_TILE=3
        /** Map tiles followed for a tapped track's line (zoom 14, ~1.7 km in NZ). */
        const val TRACK_TILES=160
        /** Start a request on a fresh consumer. Any refusal releases the consumer
         * and returns null: a closed transport throws IllegalStateException, but
         * its 512-request guard throws IllegalArgumentException, which used to
         * escape and leak the slot. Four leaks (after a busy area such as
         * Wanaka) stopped all overlay downloads for the rest of the session. */
        fun <T> startLeased(consumer:TileTransport.Consumer,start:()->T):T?=try{start()}catch(_:Exception){consumer.release(true);null}
        /** Retry a refused request after this long. */
        const val REFUSED_RETRY_MILLIS=750L
        /** An overlay download outstanding this long is released and retried:
         * a request whose result never arrives must not hold one of the four
         * slots while its tile stays in view (overlays then stopped until the
         * map was moved away or overlays were toggled). */
        const val STALE_LEASE_MILLIS=20_000L
        /** Leases older than [limit] at [now], given their start times. */
        /** One tile's pieces of the foot track [name], clipped to the tile, and
         * the neighbouring tiles the track continues into (a piece ending on an
         * edge or corner). */
        fun trackPieces(id:GpuUploadQueue.Id,features:List<VectorTile.Feature>,name:String):Pair<List<List<VectorTile.Point>>,List<GpuUploadQueue.Id>>{
            val b=id.bounds;val eps=(b[2]-b[0])*1e-6;val n=1 shl id.z
            val parts=ArrayList<List<VectorTile.Point>>();val next=LinkedHashSet<GpuUploadQueue.Id>()
            for(f in features)if(f.layer=="streets"&&f.properties["kind"]=="track"&&f.properties["name"]==name)for(path in f.paths)for(part in BoundaryIndex.clip(path,b))if(part.size>=2){
                parts.add(part)
                for(end in listOf(part.first(),part.last())){
                    val dx=when{abs(end.x-b[0])<eps->-1;abs(end.x-b[2])<eps->1;else->0};val dy=when{abs(end.y-b[1])<eps->-1;abs(end.y-b[3])<eps->1;else->0}
                    for((ox,oy) in listOf(dx to 0,0 to dy,dx to dy))if(ox!=0||oy!=0){
                        val t=GpuUploadQueue.Id(id.z,id.x+ox,id.y+oy);if(t.x in 0 until n&&t.y in 0 until n)next.add(t)}
                }
            }
            return parts to next.toList()
        }
        fun staleLeases(started:Map<GpuUploadQueue.Id,Long>,now:Long,limit:Long=STALE_LEASE_MILLIS)=started.filterValues{now-it>=limit}.keys
    }
    /** [mask]: source layers decoded so far. A zoom that enables another layer
     * decodes only that layer; nothing is decoded twice (previously the whole
     * tile was decoded again whenever the enabled set changed: 260 decodes for
     * 39 tiles). Styling filters layers by camera zoom itself. */
    private data class Tile(val body:TileBody,var mask:Set<String>,var features:List<VectorTile.Feature>){
        /** Styled output and packed groups (per display clip) for the most recent
         * (settings, quarter zoom) keys, so pinching back and forth reuses them. */
        val styles=object:LinkedHashMap<Any,Styled>(4,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Any,Styled>)=size>STYLES_PER_TILE}
    }
    private class Styled(val output:TopographyStyle.Output){val groups=HashMap<List<Double>,LineGroup>()}
    private val worker=Executors.newSingleThreadScheduledExecutor{run->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);run.run()},"RidgelineCartography")}
    private val main=Handler(Looper.getMainLooper())
    private val style=TopographyStyle(view.context.assets.open("topography-style.json").bufferedReader().use{it.readText()})
    private val tiles=LinkedHashMap<GpuUploadQueue.Id,Tile>()
    /** Prepared tiles kept after they leave the view (decoded, styled, packed),
     * like MapLibre's tile cache: panning back or zooming out and in reuses
     * them with no download, decode or styling. Sized to RAM. */
    private val cachedTiles=run{
        val am=view.context.getSystemService(android.app.ActivityManager::class.java);val info=android.app.ActivityManager.MemoryInfo();am.getMemoryInfo(info)
        val gb=info.totalMem/(1L shl 30).toDouble()
        when{am.isLowRamDevice->8;gb<=3.2->24;gb<=4.2->40;else->64}
    }
    /** Mark [keep] most recently used and drop the least recently used others
     * beyond the cache size. */
    private fun trimTiles(keep:Set<GpuUploadQueue.Id>){
        for(id in keep)tiles.remove(id)?.let{tiles[id]=it}
        var excess=tiles.size-tiles.keys.count{it in keep}-cachedTiles
        val it=tiles.keys.iterator()
        while(excess>0&&it.hasNext()){if(it.next() !in keep){it.remove();excess--}}
    }
    private val retry=HashMap<GpuUploadQueue.Id,Long>()
    private var current=emptyList<GpuUploadQueue.Id>()
    // Like MapLibre's vector source: several visible tiles load in parallel and
    // keep loading during gestures. (Previously one tile at a time, none while
    // moving, so tracks could take seconds to appear after stopping.)
    private val leases=ConcurrentHashMap<GpuUploadQueue.Id,TileTransport.Consumer>()
    private fun releaseAll(){for((id,owned)in leases.entries.toList())if(leases.remove(id,owned))owned.release(true);leaseStarted.clear();releaseHunting()}
    /** When each outstanding overlay download started (worker thread). */
    private val leaseStarted=ConcurrentHashMap<GpuUploadQueue.Id,Long>()
    private var staleReleases=0L;private var refused=0L
    private var leaseWatch:ScheduledFuture<*>?=null
    /** Release overlay downloads outstanding too long, then refill the slots. */
    private fun releaseStale(){
        val now=System.currentTimeMillis()
        leaseStarted.keys.retainAll(leases.keys)
        for(id in staleLeases(leaseStarted,now)){val owned=leases[id]?:continue
            if(leases.remove(id,owned)){owned.release(true);leaseStarted.remove(id);retry.remove(id);staleReleases++}}
    }
    private fun watchLeases(){
        if(closed||leases.isEmpty()||leaseWatch?.isDone==false)return
        leaseWatch=worker.schedule({leaseWatch=null;releaseStale();pump();watchLeases()},5,TimeUnit.SECONDS)
    }
    private var lastPublishAt=0L;private var lastSelectionAt=0L
    private var timer:ScheduledFuture<*>?=null;private var publication:ScheduledFuture<*>?=null;private var retryWake:ScheduledFuture<*>?=null
    @Volatile private var closed=false
    @Volatile private var active=true
    private var lastKey:Any?=null;private var lastLabels=emptyList<TopographyStyle.Label>();private var atlas=NativeLabelAtlas.empty()
    private var lastGroups=emptyList<LineGroup>();private val borderGroups=HashMap<GpuUploadQueue.Id,LineGroup>()
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
                    placedTracks=trackRects(next,selection)
                    main.post{if(!closed&&active&&projectionRevision.get()==next.revision)view.placeMapLabels(next.atlas,selection)}
                    diagnostics=diagnostics.substringBefore("\nLabel placement:")+"\nLabel placement: ${selection.getInt(0)}/${next.atlas.labels.size} · ${(System.nanoTime()-start)/1_000_000} ms · background"
                }
            }finally{placementQueued.set(false);if(projection.get()!=null)schedulePlacement()}
        }}.onFailure{placementQueued.set(false)}
    }
    fun snapshot()=diagnostics
    /** Screen boxes (left, top, right, bottom in view pixels) of the track
     * names last placed, for taps. */
    private class Placed(val box:FloatArray,val label:TopographyStyle.Label)
    @Volatile private var placedTracks=emptyList<Placed>()
    private fun trackRects(p:Projection,selection:ByteBuffer):List<Placed>{
        val out=ArrayList<Placed>();val b=selection.duplicate().order(ByteOrder.LITTLE_ENDIAN);val n=b.getInt(0)
        for(k in 0 until n){val i=b.getInt(4+k*12);val ox=b.getFloat(8+k*12);val oy=b.getFloat(12+k*12)
            val row=p.atlas.labels.getOrNull(i)?:continue
            if(!row.label.key.startsWith(TopographyStyle.TRACK_LABELS+":")||i*3+1>=p.positions.size)continue
            val x=p.positions[i*3]+ox*p.scale;val y=p.positions[i*3+1]+oy*p.scale
            out.add(Placed(floatArrayOf(x,y,x+row.width*p.scale,y+row.height*p.scale),row.label))}
        return out
    }
    /** The track name under a tap (with a finger's margin), nearest first. */
    fun trackLabelAt(x:Double,y:Double):TopographyStyle.Label?{
        val margin=10*view.pixelScale
        return placedTracks.filter{val r=it.box;x>=r[0]-margin&&x<=r[2]+margin&&y>=r[1]-margin&&y<=r[3]+margin}
            .minByOrNull{val r=it.box;hypot((r[0]+r[2])/2-x,(r[1]+r[3])/2-y)}?.label
    }
    /** The map's own line for the track named [name] through [at]: the same
     * LINZ data (zoom 14, about 0.4 m precision) the map draws, followed from
     * the tapped tile into each neighbour the track leaves through (loaded
     * tiles reused, others fetched; at most [TRACK_TILES]). Pieces are clipped
     * to their tiles, in longitude/latitude. */
    fun trackLine(name:String,at:Coordinate,callback:(List<List<Coordinate>>)->Unit){runCatching{worker.execute{
        val z=VECTOR_MAX_ZOOM;val n=1 shl z
        val start=GpuUploadQueue.Id(z,floor((at.longitude+180)/360*n).toInt().coerceIn(0,n-1),floor(CameraMath.mercatorY(at.latitude)*n).toInt().coerceIn(0,n-1))
        val queued=LinkedHashSet(listOf(start));val pending=ArrayDeque(listOf(start));val pieces=ArrayList<List<VectorTile.Point>>()
        var inFlight=0;var done=false
        fun finish(){if(done)return;done=true;val out=pieces.map{p->p.map{Coordinate(it.x*360-180,CameraMath.latitude(it.y))}};main.post{if(!closed)callback(out)}}
        fun take(id:GpuUploadQueue.Id,features:List<VectorTile.Feature>){
            val (parts,next)=trackPieces(id,features,name);pieces.addAll(parts)
            for(t in next)if(queued.size<TRACK_TILES&&queued.add(t))pending.addLast(t)
        }
        fun pumpLine(){
            if(done)return
            while(inFlight<3&&pending.isNotEmpty()){val id=pending.removeFirst()
                val cached=tiles[id]
                if(cached!=null&&"streets" in cached.mask){take(id,cached.features);continue}
                val owned=loader.layerConsumer(1)
                val body=startLeased(owned){loader.layerBody(owned,loader.vectorUrl(id.z,id.x,id.y))}?:continue
                inFlight++
                body.whenComplete{r,e->runCatching{worker.execute{
                    owned.close();inFlight--
                    if(!done&&e==null&&r!=null&&r.status in 200..299)runCatching{take(id,VectorTile.decode(r.bytes,id.z,id.x,id.y,setOf("streets")))}
                    pumpLine()
                }}.onFailure{owned.close()}}
            }
            if(inFlight==0&&pending.isEmpty())finish()
        }
        pumpLine()
        // A request that never answers must not hold the summary back.
        worker.schedule({finish()},15,TimeUnit.SECONDS)
    }}.onFailure{callback(emptyList())}}
    fun refresh(){if(closed)return;runCatching{worker.execute{
        if(closed)return@execute
        val state=loader.mapSettings.state
        if(state!=displayState){displayState=state;lastKey=null;schedulePublication()}
        // Overlays off and already cleared: no selection, fetching or publishing.
        if(current.isEmpty()&&tiles.isEmpty()&&leases.isEmpty()&&mask(state).isEmpty()&&!(state.borders&&state.base==MapSettings.Base.LINZ)&&!state.hunting&&huntingTiles.isEmpty())return@execute
        // Leading-edge throttle (was a 180 ms debounce that every camera frame
        // restarted, so tiles for new ground were not even selected until the
        // finger paused). A pending selection already reads the latest camera.
        if(timer?.isDone==false)return@execute
        val wait=(SELECTION_MILLIS-(android.os.SystemClock.uptimeMillis()-lastSelectionAt)).coerceIn(0,SELECTION_MILLIS)
        timer=worker.schedule({lastSelectionAt=android.os.SystemClock.uptimeMillis();try{update()}catch(_:Exception){diagnostics="Topographic view selection failed; imagery and routes remain available."}},wait,TimeUnit.MILLISECONDS)
    }}}
    private fun mask(state:MapSettings.State)=style.sourceLayers(state,view.planningPose.zoom)
    fun setActive(value:Boolean){active=value;if(value)refresh()else{releaseAll();timer?.cancel(false);publication?.cancel(false);retryWake?.cancel(false)}}
    private fun update(){
        if(closed||!active||view.width==0||view.height==0)return
        val state=loader.mapSettings.state;val pose=view.planningPose
        val hunting=state.hunting&&pose.zoom>=HuntingAreas.MIN_ZOOM
        if(mask(state).isEmpty()&&!(state.borders&&state.base==MapSettings.Base.LINZ&&pose.zoom>=8)&&!hunting){releaseAll();current=emptyList();tiles.clear();huntingWanted=emptyList();lastKey=null;schedulePublication();return}
        val flat=if(!state.threeD)FlatSelection.plan((view.width/view.pixelScale).roundToInt(),(view.height/view.pixelScale).roundToInt(),pose,512.0,24,VECTOR_MAX_ZOOM)else null
        val wanted=if(flat!=null)flat.map{GpuUploadQueue.Id(it.z,it.x,it.y)}else{
            plan.rewind();val focus=view.surfaceFocusFor(pose)
            val n=NativeBridge.planFocused((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),(view.height/view.pixelScale).roundToInt().coerceAtLeast(1),pose.longitude,pose.latitude,pose.zoom,pose.bearing,pose.pitch,pose.elevation,24,512.0,VECTOR_MAX_ZOOM,1.0,focus?.longitude?:Double.NaN,focus?.latitude?:Double.NaN,focus?.range?:Double.NaN,plan)
            plan.rewind();List(n){GpuUploadQueue.Id(plan.int,plan.int,plan.int)}
        }
        if(current.toSet()!=wanted.toSet()){current=wanted;trimTiles(wanted.toSet()+LayerCover.display(wanted,tiles.keys).map{it.source});retry.keys.retainAll(wanted.toSet());for((id,owned)in leases.entries.toList())if(id !in wanted&&leases.remove(id,owned))owned.release(true);lastKey=null}
        current=wanted
        if(mask(state).isEmpty()){for((id,owned)in leases.entries.toList())if(leases.remove(id,owned))owned.release(true);leaseStarted.clear();tiles.clear()}
        updateHunting(if(hunting)HuntingAreas.dataZoom(pose.zoom)else -1,wanted)
        schedulePublication();pump()
    }
    // ---- DOC hunting permit areas: served live by DOC (HuntingAreas), one
    // query per data tile, cached by the map transport like other tiles. ----
    private val huntingTiles=object:LinkedHashMap<GpuUploadQueue.Id,List<HuntingAreas.Area>>(16,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<GpuUploadQueue.Id,List<HuntingAreas.Area>>)=size>48}
    private val huntingLeases=HashMap<GpuUploadQueue.Id,TileTransport.Consumer>()
    private val huntingRetry=HashMap<GpuUploadQueue.Id,Long>()
    private var huntingWanted=emptyList<GpuUploadQueue.Id>()
    private val huntingGroups=HashMap<GpuUploadQueue.Id,LineGroup>()
    private var huntingRevision=0L;private var huntingFailures=0L
    private fun releaseHunting(){for((_,owned)in huntingLeases)owned.release(true);huntingLeases.clear()}
    /** The data tiles (at [dataZoom], or none) covering the visible [view] tiles. */
    private fun updateHunting(dataZoom:Int,view:List<GpuUploadQueue.Id>){
        val wanted=if(dataZoom<0)emptyList() else view.flatMap{id->
            if(id.z>=dataZoom)listOf(GpuUploadQueue.Id(dataZoom,id.x shr(id.z-dataZoom),id.y shr(id.z-dataZoom)))
            else{val f=1 shl(dataZoom-id.z);buildList{for(dy in 0 until f)for(dx in 0 until f)add(GpuUploadQueue.Id(dataZoom,id.x*f+dx,id.y*f+dy))}}
        }.distinct().take(32)
        if(wanted!=huntingWanted){huntingWanted=wanted;lastKey=null
            for((id,owned)in huntingLeases.entries.toList())if(id !in wanted){huntingLeases.remove(id);owned.release(true)}}
        pumpHunting()
    }
    private fun pumpHunting(){
        if(closed||!active)return
        val now=System.currentTimeMillis()
        for((rank,id)in huntingWanted.withIndex()){
            if(huntingLeases.size>=3)break
            if(id in huntingTiles||id in huntingLeases||(huntingRetry[id]?:0)>now)continue
            val owned=loader.layerConsumer(8+rank)
            val body=startLeased(owned){loader.layerBody(owned,HuntingAreas.url(id.z,id.x,id.y))}?:run{huntingRetry[id]=now+REFUSED_RETRY_MILLIS;null}?:continue
            huntingLeases[id]=owned
            body.whenComplete{result,error->runCatching{worker.execute{
                if(huntingLeases[id]!==owned){owned.close();return@execute};huntingLeases.remove(id);owned.close()
                if(error==null&&result!=null&&result.status in 200..299){
                    try{huntingTiles[id]=HuntingAreas.decode(result.bytes);huntingRevision++;lastKey=null;schedulePublication()}
                    catch(_:Exception){huntingFailures++;huntingRetry[id]=System.currentTimeMillis()+60_000}
                }else{huntingFailures++;huntingRetry[id]=System.currentTimeMillis()+30_000}
                pumpHunting()
            }}}
        }
    }
    /** Areas containing a place (from the tiles loaded), for the tap details. */
    fun huntingAt(longitude:Double,latitude:Double,callback:(List<HuntingAreas.Area>)->Unit){runCatching{worker.execute{
        val x=(longitude+180)/360;val y=CameraMath.mercatorY(latitude)
        val found=huntingTiles.values.asSequence().flatten().filter{it.contains(x,y)}.distinctBy{it.id}.sortedBy{it.hectares}.toList()
        main.post{if(!closed)callback(found)}
    }}}
    private fun pump(){
        if(closed||!active||mask(loader.mapSettings.state).isEmpty())return
        val now=System.currentTimeMillis()
        while(leases.size<PARALLEL_TILES){
            val id=current.firstOrNull{it !in tiles&&!leases.containsKey(it)&&(retry[it]?:0)<=now}?:break
            val owned=loader.layerConsumer(current.indexOf(id).coerceAtLeast(0));leases[id]=owned
            if(closed||!active){leases.remove(id,owned);owned.release(true);return}
            val bodyFuture=startLeased(owned){loader.layerBody(owned,loader.vectorUrl(id.z,id.x,id.y))}
            if(bodyFuture==null){leases.remove(id,owned);retry[id]=now+REFUSED_RETRY_MILLIS;refused++;break}
            leaseStarted[id]=now;watchLeases()
            bodyFuture.whenComplete{body,error->runCatching{worker.execute{
                if(!leases.remove(id,owned)){owned.close();return@execute};owned.close();leaseStarted.remove(id)
                if(closed||id !in current){pump();return@execute}
                if(error==null&&body!=null&&body.status in 200..299){
                    try{val start=System.nanoTime();val enabled=mask(loader.mapSettings.state);val features=VectorTile.decode(body.bytes,id.z,id.x,id.y,enabled);tiles[id]=Tile(body,enabled,features);decoded++;lastDecodeMs=(System.nanoTime()-start)/1_000_000;lastKey=null}
                    catch(_:Exception){retry[id]=System.currentTimeMillis()+30000;diagnostics="Map layer data could not be decoded; imagery and routes remain available."}
                }else{retry[id]=System.currentTimeMillis()+15000;diagnostics="Topographic data unavailable · check connection or LINZ access."}
                // Admit the next bodies before CPU layout/atlas work; never serialize HTTP behind painting.
                pump();schedulePublication()
            }}}
        }
        // A free slot with refused or failed tiles waiting: wake for the earliest retry.
        if(leases.size<PARALLEL_TILES){
            val wake=current.filter{it !in tiles&&!leases.containsKey(it)}.mapNotNull{retry[it]}.minOrNull()
            if(wake!=null&&(retryWake==null||retryWake!!.isDone))retryWake=worker.schedule({retryWake=null;pump()},(wake-now).coerceAtLeast(1),TimeUnit.MILLISECONDS)
        }
    }
    private fun schedulePublication(delay:Long=8){if(closed||publication!=null&&!publication!!.isDone)return;publication=worker.schedule({publication=null;try{publish()}catch(_:Exception){diagnostics="Visible topographic data exceeds the current layer budget or could not be prepared; imagery and routes remain available.";lastKey=null}},delay.coerceAtLeast(1),TimeUnit.MILLISECONDS)}
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
        val key=listOf(state,ordered,tiles.mapValues{it.value.body.stamp?.fingerprint?:it.value.body.bytes.hashCode()},zoom,huntingRevision,huntingWanted)
        if(key==lastKey)return;lastKey=key
        // Like MapLibre's buckets: each source tile is decoded, styled, clipped and
        // packed once per styling; a publication only concatenates cached groups,
        // and the renderer repaints only overlay textures whose groups changed.
        val start=System.nanoTime();val groups=ArrayList<LineGroup>();val labels=ArrayList<TopographyStyle.Label>();val enabled=mask(state)
        val patches=LayerCover.display(ordered,tiles.keys)
        trimTiles(current.toSet()+patches.map{it.source})
        if(state.overlays())for(patch in patches){val id=patch.source;val tile=tiles[id]?:continue
            val missing=enabled-tile.mask
            if(missing.isNotEmpty()){tile.features=tile.features+VectorTile.decode(tile.body.bytes,id.z,id.x,id.y,missing);tile.mask=tile.mask+missing;tile.styles.clear();decoded++}
            // MapLibre evaluates ["zoom"] in filters at the tile's own zoom, layer
            // ranges and paint at the camera zoom.
            val styled=tile.styles.getOrPut(listOf(state,zoom)){Styled(style.build(tile.features,state,zoom,id.z.toDouble()))}
            val output=styled.output;val b=patch.clip.bounds
            groups.add(styled.groups.getOrPut(b.toList()){
                val clipped=ArrayList<TopographyStyle.Line>()
                for(line in output.lines)for(path in clip(line.points,b))if(path.size>=2)clipped.add(line.copy(points=path))
                group(id.z,id.x,id.y,b,clipped)
            })
            labels.addAll(output.labels.filter{it.point.x>=b[0]&&it.point.x<b[2]&&it.point.y>=b[1]&&it.point.y<b[3]})
        }
        if(state.borders&&state.base==MapSettings.Base.LINZ&&zoom>=8){
            val parts=boundary?:BoundaryIndex(readBoundary()).also{boundary=it}
            borderGroups.keys.retainAll(ordered.toSet())
            for(id in ordered){
                val fragments=parts.fragments(id)
                if(fragments.isEmpty())continue
                val n=1.0/(1 shl id.z)
                groups.add(borderGroups.getOrPut(id){group(id.z+64,id.x,id.y,doubleArrayOf(id.x*n,id.y*n,(id.x+1)*n,(id.y+1)*n),fragments.map{TopographyStyle.Line(it,0x8c111111.toInt(),1f,.2f,3.8f)})})
                if(zoom>=11)for(clipped in fragments)if(clipped.size>1){val p=clipped[clipped.size/2];labels.add(TopographyStyle.Label(p,"LINZ imagery | Esri World Imagery",10f,0xffdddddd.toInt(),85.0,key="boundary:${p.x}:${p.y}"))}
            }
        }else borderGroups.clear()
        // Hunting permit areas: dashed blaze-orange outlines, names when closer.
        if(state.hunting&&zoom>=HuntingAreas.MIN_ZOOM){
            huntingGroups.keys.retainAll(huntingWanted.toSet());val named=HashSet<Long>()
            for(id in huntingWanted){val areas=huntingTiles[id]?:continue
                groups.add(huntingGroups.getOrPut(id){
                    val b=id.bounds;val lines=ArrayList<TopographyStyle.Line>()
                    for(area in areas)for(ring in area.rings)for(part in clip(ring,b))if(part.size>=2)lines.add(TopographyStyle.Line(part,HUNTING_LINE,2.2f,7f,4f))
                    group(id.z+128,id.x,id.y,b,lines)
                })
                if(zoom>=HuntingAreas.NAME_ZOOM)for(area in areas)if(area.name.isNotBlank()&&named.add(area.id))area.anchor?.let{p->
                    labels.add(TopographyStyle.Label(p,area.name,11f,HUNTING_TEXT,140.0,key=style.labelKey("hunting",area.name,p)))}
            }
        }else huntingGroups.clear()
        // A camera-dependent atlas order repainted/reuploaded the same strings on pans.
        // Fixed priority/key order keeps atlas slots stable; screen placement still follows camera.
        val chosen=labels.distinctBy{it.key}.sortedWith(compareBy<TopographyStyle.Label>{it.priority}.thenBy{it.key}).take(512)
        if(groups.size!=lastGroups.size||groups.indices.any{groups[it]!==lastGroups[it]})lastGroups=groups
        lastBuildMs=(System.nanoTime()-start)/1_000_000
        if(chosen!=lastLabels){val a=System.nanoTime();atlas=NativeLabels.atlas(chosen,view.context.assets);lastLabels=chosen;lastAtlasMs=(System.nanoTime()-a)/1_000_000}
        val batch=NativeLayerBatch(lastGroups,atlas)
        diagnostics="Topography tiles: ${tiles.size} prepared (${current.count{it in tiles}}/${current.size} in view) · downloading ${leases.size}/$PARALLEL_TILES · oldest ${leaseStarted.values.minOrNull()?.let{(System.currentTimeMillis()-it)/1000}?:0} s · stale releases $staleReleases · refused $refused · decoded $decoded · hunting ${huntingTiles.size}/${huntingWanted.size} tiles, ${huntingLeases.size} loading, $huntingFailures failed\nVector decode: $lastDecodeMs ms · layer build: $lastBuildMs ms · font atlas: $lastAtlasMs ms\nLine groups: ${groups.size} · lines ${groups.sumOf{it.lines}} · label candidates ${atlas.labels.size} · atlas ${atlas.width}×${atlas.height}"
        main.post{if(!closed)view.mapLayers(batch)}
    }
    private fun readBoundary():List<List<VectorTile.Point>>{
        val json=view.context.assets.open("imagery-boundary.geojson").bufferedReader().use{JSONObject(it.readText())};val result=ArrayList<List<VectorTile.Point>>()
        val features=json.getJSONArray("features");for(i in 0 until features.length()){val geo=features.getJSONObject(i).getJSONObject("geometry");val paths=geo.getJSONArray("coordinates");for(j in 0 until paths.length()){val path=paths.getJSONArray(j);result.add(List(path.length()){k->val p=path.getJSONArray(k);VectorTile.Point((p.getDouble(0)+180)/360,(1-asinh(tan(Math.toRadians(p.getDouble(1))))/PI)/2)})}}
        return result
    }
    private fun group(z:Int,x:Int,y:Int,clip:DoubleArray,lines:List<TopographyStyle.Line>)=LineGroup.pack(z,x,y,clip,lines)
    private fun clip(path:List<VectorTile.Point>,box:DoubleArray)=BoundaryIndex.clip(path,box)
    override fun close(){closed=true;timer?.cancel(false);publication?.cancel(false);retryWake?.cancel(false);releaseAll();worker.shutdownNow();view.onLayerProjection={_,_->}}
}
