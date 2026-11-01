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
    private data class Id(val z:Int,val x:Int,val y:Int){val bounds get()=doubleArrayOf(x.toDouble()/(1 shl z),y.toDouble()/(1 shl z),(x+1.0)/(1 shl z),(y+1.0)/(1 shl z))}
    private data class Tile(val body:TileBody,var mask:Set<String>,var features:List<VectorTile.Feature>)
    private val worker=Executors.newSingleThreadScheduledExecutor{run->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);run.run()},"RidgelineCartography")}
    private val main=Handler(Looper.getMainLooper())
    private val style=TopographyStyle(view.context.assets.open("topography-style.json").bufferedReader().use{it.readText()})
    private val tiles=LinkedHashMap<Id,Tile>()
    private val retry=HashMap<Id,Long>()
    private var current=emptyList<Id>();@Volatile private var lease:Pair<Id,TileTransport.Consumer>?=null
    private var timer:ScheduledFuture<*>?=null;private var publication:ScheduledFuture<*>?=null
    @Volatile private var closed=false
    @Volatile private var active=true
    private var lastKey:Any?=null;private var lastLabels=emptyList<TopographyStyle.Label>();private var atlas=NativeLabelAtlas.empty()
    private var lastLines=emptyList<TopographyStyle.Line>();private var linePacket=ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0);rewind()}
    private val plan=ByteBuffer.allocateDirect(32*12).order(ByteOrder.LITTLE_ENDIAN)
    private var boundary:List<List<VectorTile.Point>>?=null
    @Volatile private var diagnostics="Map layers: waiting for view"
    private var decoded=0L;private var lastDecodeMs=0L;private var lastBuildMs=0L;private var lastAtlasMs=0L
    private var displayState=loader.mapSettings.state
    init{view.onLayerProjection={data,positions->val start=System.nanoTime();val selection=NativeLabels.place(data,positions,view.width,view.height,view.pixelScale.toFloat());view.placeMapLabels(data,selection);val count=selection.getInt(0);diagnostics=diagnostics.substringBefore("\nLabel placement:")+"\nLabel placement: $count/${data.labels.size} · ${(System.nanoTime()-start)/1_000_000} ms"}}
    fun snapshot()=diagnostics
    fun refresh(){if(closed)return;runCatching{worker.execute{
        if(closed)return@execute
        timer?.cancel(false);val state=loader.mapSettings.state
        if(view.gestureInProgress){lease?.let{it.second.release(true)};lease=null}
        if(state!=displayState){displayState=state;lastKey=null;schedulePublication()}
        timer=worker.schedule({update()},if(view.gestureInProgress)180 else 80,TimeUnit.MILLISECONDS)
    }}}
    private fun mask(state:MapSettings.State)=style.sourceLayers(state,view.planningPose.zoom)
    fun setActive(value:Boolean){active=value;if(value)refresh()else{lease?.second?.release(true);lease=null;timer?.cancel(false);publication?.cancel(false)}}
    private fun update(){
        if(closed||!active||view.width==0||view.height==0)return
        val state=loader.mapSettings.state;val pose=view.planningPose
        if(mask(state).isEmpty()&&!(state.borders&&state.base==MapSettings.Base.LINZ&&pose.zoom>=8)){lease?.second?.release(true);lease=null;current=emptyList();tiles.clear();lastKey=null;schedulePublication();return}
        plan.rewind();val focus=view.surfaceFocusFor(pose)
        val n=NativeBridge.planFocused((view.width/view.pixelScale).roundToInt().coerceAtLeast(1),(view.height/view.pixelScale).roundToInt().coerceAtLeast(1),pose.longitude,pose.latitude,pose.zoom,pose.bearing,pose.pitch,pose.elevation,24,512.0,15,1.0,focus?.longitude?:Double.NaN,focus?.latitude?:Double.NaN,focus?.range?:Double.NaN,plan)
        plan.rewind();val wanted=List(n){Id(plan.int,plan.int,plan.int)}
        if(current!=wanted){current=wanted;tiles.keys.retainAll(wanted.toSet());retry.keys.retainAll(wanted.toSet());lease?.takeIf{it.first !in wanted}?.let{it.second.release(true);lease=null};lastKey=null}
        if(mask(state).isEmpty()){lease?.second?.release(true);lease=null;tiles.clear()}
        schedulePublication();pump()
    }
    private fun pump(){
        if(closed||!active||lease!=null||view.gestureInProgress||mask(loader.mapSettings.state).isEmpty())return
        val now=System.currentTimeMillis();val id=current.firstOrNull{it !in tiles&&(retry[it]?:0)<=now}?:return
        val owned=loader.layerConsumer();lease=id to owned
        loader.layerBody(owned,loader.vectorUrl(id.z,id.x,id.y)).whenComplete{body,error->runCatching{worker.execute{
            if(lease?.second!==owned){owned.close();return@execute};lease=null;owned.close()
            if(closed||id !in current)return@execute
            if(error==null&&body!=null&&body.status in 200..299){
                try{val start=System.nanoTime();val enabled=mask(loader.mapSettings.state);val features=VectorTile.decode(body.bytes,id.z,id.x,id.y,enabled);tiles[id]=Tile(body,enabled,features);decoded++;lastDecodeMs=(System.nanoTime()-start)/1_000_000;lastKey=null}
                catch(_:Exception){retry[id]=now+30000;diagnostics="Map layer data could not be decoded; imagery and routes remain available."}
            }else{retry[id]=System.currentTimeMillis()+15000;diagnostics="Topographic data unavailable · check connection or LINZ access."}
            // Admit the next body before CPU layout/atlas work; never serialize HTTP behind painting.
            pump();schedulePublication()
        }}}
    }
    private fun schedulePublication(){if(closed||publication!=null&&!publication!!.isDone)return;publication=worker.schedule({publication=null;publish()},60,TimeUnit.MILLISECONDS)}
    private fun publish(){
        if(closed)return;val state=loader.mapSettings.state;val pose=view.planningPose;val zoom=floor(pose.zoom*4)/4
        val key=listOf(state,current,tiles.mapValues{it.value.body.stamp?.fingerprint?:it.value.body.bytes.hashCode()},zoom)
        if(key==lastKey)return;lastKey=key
        val start=System.nanoTime();val lines=ArrayList<TopographyStyle.Line>();val labels=ArrayList<TopographyStyle.Label>();val enabled=mask(state)
        if(state.overlays())for(id in current){val tile=tiles[id]?:continue
            if(tile.mask!=enabled){tile.features=VectorTile.decode(tile.body.bytes,id.z,id.x,id.y,enabled);tile.mask=enabled;decoded++}
            val output=style.build(tile.features,state,zoom);val b=id.bounds
            for(line in output.lines)for(path in clip(line.points,b))lines.add(line.copy(points=path))
            labels.addAll(output.labels.filter{it.point.x>=b[0]&&it.point.x<b[2]&&it.point.y>=b[1]&&it.point.y<b[3]})
        }
        if(state.borders&&state.base==MapSettings.Base.LINZ&&zoom>=8){
            val parts=boundary?:readBoundary().also{boundary=it}
            for(id in current)for(path in parts)for(clipped in clip(path,id.bounds)){
                lines.add(TopographyStyle.Line(clipped,0x8c111111.toInt(),1f,.2f,3.8f))
                if(zoom>=11&&clipped.size>1){val p=clipped[clipped.size/2];labels.add(TopographyStyle.Label(p,"LINZ imagery | Esri World Imagery",10f,0xffdddddd.toInt(),85.0,key="boundary:${p.x}:${p.y}"))}
            }
        }
        val mx=(pose.longitude+180)/360;val my=(1-asinh(tan(Math.toRadians(pose.latitude)))/PI)/2
        val chosen=labels.distinctBy{it.key}.sortedWith(compareBy<TopographyStyle.Label>{it.priority}.thenBy{(it.point.x-mx).pow(2)+(it.point.y-my).pow(2)}).take(512)
        if(lines!=lastLines){lastLines=lines;linePacket=pack(lines)}
        lastBuildMs=(System.nanoTime()-start)/1_000_000
        if(chosen!=lastLabels){val a=System.nanoTime();atlas=NativeLabels.atlas(chosen);lastLabels=chosen;lastAtlasMs=(System.nanoTime()-a)/1_000_000}
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
    private fun clip(path:List<VectorTile.Point>,box:DoubleArray):List<List<VectorTile.Point>>{
        val result=ArrayList<List<VectorTile.Point>>();var part=ArrayList<VectorTile.Point>()
        for(i in 1 until path.size){val a=path[i-1];val b=path[i];val dx=b.x-a.x;val dy=b.y-a.y;var t0=0.0;var t1=1.0
            fun edge(p:Double,q:Double):Boolean{if(p==0.0)return q>=0;val t=q/p;if(p<0){if(t>t1)return false;t0=max(t0,t)}else{if(t<t0)return false;t1=min(t1,t)};return true}
            if(edge(-dx,a.x-box[0])&&edge(dx,box[2]-a.x)&&edge(-dy,a.y-box[1])&&edge(dy,box[3]-a.y)){
                val p=VectorTile.Point(a.x+dx*t0,a.y+dy*t0);val q=VectorTile.Point(a.x+dx*t1,a.y+dy*t1)
                if(part.isNotEmpty()&&part.last()!=p){if(part.size>1)result.add(part);part=ArrayList()};if(part.isEmpty())part.add(p);part.add(q)
            }else if(part.isNotEmpty()){if(part.size>1)result.add(part);part=ArrayList()}
        };if(part.size>1)result.add(part);return result
    }
    override fun close(){closed=true;timer?.cancel(false);publication?.cancel(false);lease?.second?.release(true);lease=null;worker.shutdownNow();view.onLayerProjection={_,_->}}
}
