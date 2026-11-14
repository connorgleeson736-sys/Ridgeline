package nz.ridgeline.nativeapp

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** The bundled compiled walks (WalkRecords), read once in the background;
 * [get] answers on the main thread, waiting for the first read if needed. */
internal class WalkRecordStore(private val assets:AssetManager){
    private val main=Handler(Looper.getMainLooper())
    private var records:Map<String,WalkRecords.Walk>?=null
    private val waiting=ArrayList<Pair<String,(WalkRecords.Walk?)->Unit>>()
    init{
        Executors.newSingleThreadExecutor{Thread(it,"ridgeline-walks").apply{isDaemon=true;priority=Thread.MIN_PRIORITY}}.execute{
            val read=runCatching{assets.open(WalkRecords.ASSET).use{it.readBytes()}.let{WalkRecords.parse(String(it,Charsets.UTF_8))}}.getOrElse{emptyMap()}
            main.post{records=read;val w=waiting.toList();waiting.clear();for((g,cb) in w)cb(read[g])}
        }
    }
    fun get(guid:String,callback:(WalkRecords.Walk?)->Unit){val r=records;if(r!=null)callback(r[guid])else waiting.add(guid to callback)}
}
