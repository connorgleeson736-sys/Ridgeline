package nz.ridgeline.nativeapp

import android.app.Activity
import android.os.Bundle
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit debug-only fixture. Synthetic flat height, genuine unchanged overview
 * imagery. Not a terrain preview, source fallback or visual-parity claim. */
class ProofActivity: Activity() {
    private lateinit var terrain: TerrainView
    private val ready = CopyOnWriteArrayList<GpuUploadQueue.Tile>()
    @Volatile private var closed = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ProofState.activity.set(this);ProofState.sixPacksRead=false
        terrain = TerrainView(this)
        val label = TextView(this).apply {
            text="CONTROLLED TEST · flat synthetic height / original bundled overview imagery"
            setTextColor(Color.BLACK);setBackgroundColor(Color.WHITE);setPadding(16,40,16,16)
        }
        val root = FrameLayout(this);root.addView(terrain,FrameLayout.LayoutParams(-1,-1))
        root.addView(label,FrameLayout.LayoutParams(-1,-2));setContentView(root)
        terrain.onStatus = { label.text="CONTROLLED TEST · synthetic flat elevation / genuine imagery\n$it" }
        fun upload() {for(row in ready)terrain.upload(row.id.z,row.id.x,row.id.y,row.heights,row.rgba)}
        terrain.onCamera = { upload() }
        Thread({
            try {
                val index=assets.open("overview-index.json").use {JSONObject(it.readBytes().toString(Charsets.UTF_8))}
                val imagery=NativeImagery(this,{error("Fixture forbids network")},{_,_,_->error("Fixture forbids network")})
                // Exercise Android's real APK range reader, one image from every binary pack.
                val remaining=mutableSetOf<String>()
                for(i in 0 until index.getJSONArray("files").length())remaining.add(index.getJSONArray("files").getJSONObject(i).getString("sha256"))
                val tiles=index.getJSONObject("tiles");val keys=tiles.keys()
                while(keys.hasNext()&&remaining.isNotEmpty()){
                    val key=keys.next();val entry=tiles.getJSONObject(key);val row=entry.optJSONObject("natural")?:entry
                    if(row.getString("file") !in remaining)continue
                    val id=key.split('/').map{it.toInt()};val bytes=imagery.saved(id[0],id[1],id[2])?:error("Missing pack")
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Pack range decode")
                    check(bitmap.width==256&&bitmap.height==256);bitmap.recycle();remaining.remove(row.getString("file"))
                }
                check(remaining.isEmpty()){"Not all six packs exercised"}
                ProofState.sixPacksRead=true
                android.util.Log.i("RidgelineProof","All six APK pack ranges decoded")
                for(x in 3983..3984)for(y in 2599..2600){
                    val bytes=imagery.saved(12,x,y)?:error("Packaged reference tile missing")
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Overview decode")
                    val pixels=IntArray(65536);bitmap.getPixels(pixels,0,256,0,0,256,256);bitmap.recycle()
                    val heights=ByteBuffer.allocateDirect(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(129*129){heights.putFloat(3724f)};heights.rewind()
                    val rgba=ByteBuffer.allocateDirect(256*256*4)
                    for(p in pixels){rgba.put(Color.red(p).toByte());rgba.put(Color.green(p).toByte());rgba.put(Color.blue(p).toByte());rgba.put(255.toByte())};rgba.rewind()
                    ready.add(GpuUploadQueue.Tile(GpuUploadQueue.Id(12,x,y),heights,rgba))
                }
                if(!closed)runOnUiThread{upload();label.text="CONTROLLED TEST · synthetic flat elevation / genuine imagery\nSix APK pack range reads and bitmap decodes passed"}
            }catch(e:Exception){if(!closed)runOnUiThread{label.text="Controlled fixture failed: ${e.javaClass.simpleName}: ${e.message}"}}
        },"native-proof-fixture").start()
    }
    internal fun pickCenter(callback:(DoubleArray?)->Unit){terrain.pick(terrain.width/2.0,terrain.height/2.0,callback)}
    internal fun showSurface(value:Boolean){terrain.visibility=if(value)android.view.View.VISIBLE else android.view.View.GONE}
    override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true)}
    override fun onPause(){if(::terrain.isInitialized)terrain.setActive(false);super.onPause()}
    override fun onDestroy(){closed=true;ProofState.activity.compareAndSet(this,null);terrain.close();super.onDestroy()}
}

internal object ProofState {
 val activity=java.util.concurrent.atomic.AtomicReference<ProofActivity?>(null)
 @Volatile var sixPacksRead=false
}
