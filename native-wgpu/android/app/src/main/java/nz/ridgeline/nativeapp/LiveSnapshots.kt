package nz.ridgeline.nativeapp

import java.io.File
import java.security.MessageDigest

/** The last copy of live data (the Te Araroa Trust's and DOC's alerts), kept on
 * the phone: shown, with its date, when the live request cannot be made
 * (offline on the trail). Saved whenever it loads, and for every section of a
 * Te Araroa offline download. One small file per address. */
internal class LiveSnapshots(dir:File){
    private val folder=File(dir,"live-snapshots")
    private fun file(url:String)=File(folder,MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}.take(32))
    @Synchronized fun save(url:String,body:String){runCatching{folder.mkdirs();val f=file(url);val tmp=File(folder,f.name+".tmp");tmp.writeText(body);if(!tmp.renameTo(f)){f.delete();tmp.renameTo(f)}}}
    /** The saved body and when it was saved (ms), or null. */
    @Synchronized fun load(url:String):Pair<String,Long>?=runCatching{val f=file(url);if(f.isFile)f.readText() to f.lastModified() else null}.getOrNull()
}
