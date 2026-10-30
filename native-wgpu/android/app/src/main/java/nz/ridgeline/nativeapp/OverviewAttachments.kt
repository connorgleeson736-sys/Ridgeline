package nz.ridgeline.nativeapp

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Original ZIPs stay intact. Validated STORED payloads are read by byte range;
 * no extraction, bitmap resampling or duplicate imagery files. */
class OverviewAttachments(private val context:Context):AutoCloseable {
    data class Pack(val payload:String,val name:String,val uri:String,val offset:Long,val bytes:Long)
    private class Handle(val input:ParcelFileDescriptor.AutoCloseInputStream):AutoCloseable{
        override fun close(){input.close()}
    }
    private val preferences=context.getSharedPreferences("overview-attachments-v1",0)
    private val expected by lazy{context.assets.open("overview-archives.json").use{JSONObject(it.readBytes().toString(Charsets.UTF_8)).getJSONArray("files")}}
    private val handles=ConcurrentHashMap<String,Handle>()
    @Volatile private var packs=readPacks()
    private fun readPacks():Map<String,Pack> = runCatching{
        val saved=JSONObject(preferences.getString("packs","{}")!!)
        saved.keys().asSequence().map{key->val row=saved.getJSONObject(key);key to Pack(key,row.getString("name"),row.getString("uri"),row.getLong("offset"),row.getLong("bytes"))}.toMap()
    }.getOrDefault(emptyMap())
    fun list()=packs.values.sortedBy{it.name}
    @Synchronized private fun persist(next:Map<String,Pack>){
        val json=JSONObject()
        for((key,p)in next)json.put(key,JSONObject().put("name",p.name).put("uri",p.uri).put("offset",p.offset).put("bytes",p.bytes))
        check(preferences.edit().putString("packs",json.toString()).commit()){"Could not save imagery attachment."}
        packs=next
    }
    /** Runs on the source/storage executor, never the UI or GPU thread. */
    fun attach(uri:Uri):Pack{
        val digest=MessageDigest.getInstance("SHA-256");var total=0L
        context.contentResolver.openInputStream(uri).use{input->
            requireNotNull(input){"Cannot open that ZIP."};val buffer=ByteArray(128*1024)
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=63_000_000){"Choose an original Ridgeline overview ZIP."};digest.update(buffer,0,n)}
        }
        val hash=digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
        val record=(0 until expected.length()).map{expected.getJSONObject(it)}.firstOrNull{it.getLong("bytes")==total&&it.getString("sha256")==hash}
            ?:throw IllegalArgumentException("This is not one of the six original Ridgeline overview ZIPs, or it is incomplete.")
        val payload=record.getString("payload")
        val descriptor=context.contentResolver.openFileDescriptor(uri,"r")?:error("Cannot open imagery attachment.")
        val handle=Handle(ParcelFileDescriptor.AutoCloseInputStream(descriptor))
        try{
            val header=ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            read(handle,header,0)
            require(header.getInt(0)==0x04034b50&&header.getShort(8).toInt()==0&&(header.getShort(6).toInt()and 1)==0){"Unsupported overview ZIP layout."}
            val nameLength=header.getShort(26).toInt()and 65535;val extraLength=header.getShort(28).toInt()and 65535
            val filename=ByteBuffer.allocate(nameLength);read(handle,filename,30)
            require(String(filename.array(),Charsets.UTF_8)=="$payload.bin"){"Unexpected overview payload."}
            val length=header.getInt(22).toLong()and 0xffffffffL
            val pack=Pack(payload,record.getString("name"),uri.toString(),30L+nameLength+extraLength,length)
            require(length>0&&pack.offset+length<=total){"Incomplete overview payload."}
            synchronized(this){
                val previous=packs[payload];persist(packs+(payload to pack));runCatching{handles.put(payload,handle)?.close()}
                if(previous!=null&&previous.uri!=pack.uri&&packs.values.none{it.uri==previous.uri})runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(previous.uri),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            }
            return pack
        }catch(e:Exception){handle.close();throw IllegalArgumentException("Could not attach this file. Choose a ZIP stored locally on this phone.",e)}
    }
    private fun read(handle:Handle,target:ByteBuffer,offset:Long){
        while(target.hasRemaining()){check(handle.input.channel.read(target,offset+target.position())>0){"Incomplete imagery attachment."}}
    }
    private fun open(pack:Pack):Handle{
        handles[pack.payload]?.let{return it}
        synchronized(this){
            handles[pack.payload]?.let{return it}
            val descriptor=context.contentResolver.openFileDescriptor(Uri.parse(pack.uri),"r")?:error("Imagery attachment is no longer available.")
            return Handle(ParcelFileDescriptor.AutoCloseInputStream(descriptor)).also{handles[pack.payload]=it}
        }
    }
    fun read(payload:String,offset:Long,length:Int):ByteArray?{
        val pack=packs[payload]?:return null
        require(offset>=0&&length in 20..1048576&&offset+length<=pack.bytes)
        return try{val data=ByteArray(length);read(open(pack),ByteBuffer.wrap(data),pack.offset+offset);data}
        catch(_:Exception){null} // Unavailable attachment falls back to the normal source chain.
    }
    @Synchronized fun detach(payload:String){
        val previous=packs[payload]?:return;persist(packs-payload);handles.remove(payload)?.close()
        if(packs.values.none{it.uri==previous.uri})runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(previous.uri),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
    @Synchronized override fun close(){for(handle in handles.values)handle.close();handles.clear()}
}
