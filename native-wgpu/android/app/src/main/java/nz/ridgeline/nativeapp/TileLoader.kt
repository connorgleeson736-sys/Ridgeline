package nz.ridgeline.nativeapp
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import kotlin.math.*
/** Prototype real-source tile path. Geographic gap routing/LOD acceptance is still open. */
class TileLoader(private val context:Context,private val view:TerrainView){
 private val workers=Executors.newFixedThreadPool(4)
 private val client=OkHttpClient.Builder().cache(Cache(File(context.cacheDir,"native-http-v1"),256_000_000)).connectTimeout(12,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).build()
 private val key=context.assets.open("map-defaults.json").use{JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")}
 private data class Id(val z:Int,val x:Int,val y:Int)
 private val jobs=ConcurrentHashMap<Id,Boolean>()
 private val ready=ConcurrentHashMap<Id,Pair<ByteBuffer,ByteBuffer>>()
 @Volatile private var dead=false
 private var timer:ScheduledFuture<*>?=null
 private val scheduler=Executors.newSingleThreadScheduledExecutor()
 fun cameraChanged(){synchronized(this){timer?.cancel(false);timer=scheduler.schedule({loadView()},120,TimeUnit.MILLISECONDS)}}
 private fun loadView(){if(dead)return;val z=(ceil(view.zoom).toInt()+1).coerceIn(5,17);val n=1 shl z;val x=floor((view.longitude+180)/360*n).toInt();val y=floor((1-asinh(tan(Math.toRadians(view.latitude)))/PI)/2*n).toInt()
  val wanted=(-1..1).flatMap{dy->(-1..1).map{dx->Id(z,x+dx,y+dy)}}.filter{it.x in 0 until n&&it.y in 0 until n}.sortedBy{(it.x-x).pow2()+(it.y-y).pow2()}
  for(id in wanted){val old=ready[id];if(old!=null){view.upload(id.z,id.x,id.y,old.first,old.second);continue};if(jobs.putIfAbsent(id,true)!=null)continue;workers.submit{try{val tile=load(id);if(!dead){ready[id]=tile;view.upload(id.z,id.x,id.y,tile.first,tile.second);while(ready.size>64)ready.keys.firstOrNull{it !in wanted}?.let{ready.remove(it)}?:break}}catch(e:Exception){if(!dead)view.post{view.onStatus("Terrain load failed: "+(e.message?:"network unavailable"))}}finally{jobs.remove(id)}}}
 }
 private fun Int.pow2()=this*this
 private fun bytes(kind:String,id:Id,ext:String):ByteArray{val request=Request.Builder().url("https://basemaps.linz.govt.nz/v1/tiles/$kind/WebMercatorQuad/${id.z}/${id.x}/${id.y}.$ext?api=$key").header("User-Agent","Ridgeline-Native/0.1.0").build();client.newCall(request).execute().use{r->check(r.isSuccessful){"Provider HTTP ${r.code}"};return r.body?.bytes()?.also{require(it.size<=8*1024*1024)}?:error("Empty tile")}}
 private fun pixels(data:ByteArray):IntArray{val b=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888})?:error("Image decode failed");try{require(b.width==256&&b.height==256);return IntArray(65536).also{b.getPixels(it,0,256,0,0,256,256)}}finally{b.recycle()}}
 private fun load(id:Id):Pair<ByteBuffer,ByteBuffer>{
  val image=pixels(bytes("aerial",id,"webp"));val dem=pixels(bytes("elevation",id,"png"));val heights=ByteBuffer.allocateDirect(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
  fun h(x:Int,y:Int):Float{val p=dem[y.coerceIn(0,255)*256+x.coerceIn(0,255)];check(Color.alpha(p)>0){"Missing elevation sample"};return (-10000+(p and 0xffffff)*0.1).toFloat()}
  // Pixel-centre bilinear sampling. Adjacent-tile halos are a pending parity gate.
  for(y in 0..128)for(x in 0..128){val px=x*2.0-.5;val py=y*2.0-.5;val ix=floor(px).toInt();val iy=floor(py).toInt();val fx=(px-ix).toFloat();val fy=(py-iy).toFloat();heights.putFloat(h(ix,iy)*(1-fx)*(1-fy)+h(ix+1,iy)*fx*(1-fy)+h(ix,iy+1)*(1-fx)*fy+h(ix+1,iy+1)*fx*fy)}
  val rgba=ByteBuffer.allocateDirect(256*256*4);for(p in image){rgba.put(Color.red(p).toByte());rgba.put(Color.green(p).toByte());rgba.put(Color.blue(p).toByte());rgba.put(Color.alpha(p).toByte())};heights.rewind();rgba.rewind();return heights to rgba
 }
 fun close(){dead=true;timer?.cancel(true);scheduler.shutdownNow();workers.shutdownNow();client.dispatcher.cancelAll();ready.clear()}
}
