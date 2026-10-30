package nz.ridgeline.nativeapp

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Deflater

/** Lossless, disposable derived data. Every dependency is checked through the
 * normal HTTP/offline policy before a hit; no independent stale-data TTL. */
internal class PreparedTerrainCache(private val directory:File,private val limit:Long,namespace:String) {
    data class Dependency(val id:GpuUploadQueue.Id,val fingerprint:String)
    private val prefix=digest(namespace.toByteArray()).take(16)
    private val entries=LinkedHashMap<File,Long>(16,.75f,true)
    private var bytes=0L
    init {
        directory.mkdirs()
        directory.listFiles()?.filter{it.name.endsWith(".tmp")}?.forEach{it.delete()}
        directory.listFiles()?.filter{it.name.endsWith(".terrain") }?.sortedBy{it.lastModified()}?.forEach{entries[it]=it.length();bytes+=it.length()}
        trim()
    }
    private fun path(id:GpuUploadQueue.Id)=File(directory,"$prefix-${id.z}-${id.x}-${id.y}.terrain")
    private fun remove(file:File){entries.remove(file)?.let{bytes-=it};file.delete()}
    private fun trim(){while(bytes>limit&&entries.isNotEmpty())remove(entries.keys.first())}
    fun get(id:GpuUploadQueue.Id,validate:(Dependency)->Boolean):ByteBuffer? {
        val file=path(id)
        val record=synchronized(this){
            if(entries[file]==null)return null
            try{DataInputStream(GZIPInputStream(file.inputStream().buffered())).use{input->
                require(input.readInt()==0x52475432)
                val count=input.readInt();require(count in 1..128)
                val dependencies=List(count){Dependency(GpuUploadQueue.Id(input.readInt(),input.readInt(),input.readInt()),input.readUTF())}
                val size=input.readInt();require(size==129*129*4+16)
                val payload=ByteArray(size);input.readFully(payload);require(input.read()==-1)
                dependencies to payload
            }}catch(_:Exception){remove(file);return null}
        }
        // Network/cache validation is intentionally outside the file lock.
        if(!record.first.all(validate)){synchronized(this){remove(file)};return null}
        return ByteBuffer.allocateDirect(record.second.size).order(ByteOrder.LITTLE_ENDIAN).apply{put(record.second);rewind()}
    }
    @Synchronized fun put(id:GpuUploadQueue.Id,payload:ByteBuffer,dependencies:List<Dependency>){
        if(dependencies.isEmpty()||dependencies.size>128||limit<=0)return
        require(payload.capacity()==129*129*4+16)
        val file=path(id);val temp=File(directory,file.name+".tmp")
        try{
            DataOutputStream(object:GZIPOutputStream(temp.outputStream().buffered()){init{def.setLevel(Deflater.BEST_SPEED)}}).use{out->
                out.writeInt(0x52475432);out.writeInt(dependencies.size)
                for(dep in dependencies){out.writeInt(dep.id.z);out.writeInt(dep.id.x);out.writeInt(dep.id.y);out.writeUTF(dep.fingerprint)}
                val data=ByteArray(payload.capacity());payload.duplicate().apply{clear();get(data)}
                out.writeInt(data.size);out.write(data)
            }
            if(temp.length()>limit){temp.delete();return}
            if(!temp.renameTo(file))return
            entries.remove(file)?.let{bytes-=it};entries[file]=file.length();bytes+=file.length();trim()
        }catch(_:IOException){/* Disposable cache must not prevent rendering. */}
        finally{temp.delete()}
    }
    @Synchronized fun sizeBytes()=bytes
    companion object {
        fun digest(bytes:ByteArray):String{
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes);val hex="0123456789abcdef"
            return buildString(64){for(byte in digest){val n=byte.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15])}}
        }
    }
}
