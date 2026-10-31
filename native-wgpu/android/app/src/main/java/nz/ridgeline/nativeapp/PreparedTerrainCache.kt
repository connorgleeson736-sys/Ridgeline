package nz.ridgeline.nativeapp

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Deflater

/** Lossless derived payloads with source version/expiry metadata. Compression
 * and file I/O never own the LRU monitor; replacements are atomic. */
internal class PreparedTerrainCache(private val directory:File,private val limit:Long,namespace:String,private val payloadBytes:Int=129*129*4+16) {
    data class Dependency(val id:GpuUploadQueue.Id,val fingerprint:String,val until:Long=0,val resourceKey:String="")
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
    fun get(id:GpuUploadQueue.Id,validate:(Dependency)->Boolean):ByteBuffer?=getChecked(id){it.all(validate)}
    fun getChecked(id:GpuUploadQueue.Id,validate:(List<Dependency>)->Boolean):ByteBuffer? {
        val file=path(id)
        synchronized(this){if(entries[file]==null)return null}
        val raw=try{file.inputStream().buffered()}
            catch(_:Exception){synchronized(this){remove(file)};return null}
        val input=try{DataInputStream(GZIPInputStream(raw))}
            catch(_:Exception){runCatching{raw.close()};synchronized(this){remove(file)};return null}
        try{
            val dependencies=try{
                require(input.readInt()==0x52475433)
                val count=input.readInt();require(count in 0..128)
                List(count){Dependency(GpuUploadQueue.Id(input.readInt(),input.readInt(),input.readInt()),input.readUTF(),input.readLong(),input.readUTF())}
            }catch(_:Exception){synchronized(this){remove(file)};return null}
            // Validate outside I/O catches and the LRU lock. A suspended or
            // cancelled reader must not invalidate an intact derived payload.
            if(!validate(dependencies))return null
            val payload=try{
                val size=input.readInt();require(size==payloadBytes)
                ByteArray(size).also{input.readFully(it);require(input.read()==-1)}
            }catch(_:Exception){synchronized(this){remove(file)};return null}
            return ByteBuffer.allocateDirect(payload.size).order(ByteOrder.LITTLE_ENDIAN).apply{put(payload);rewind()}
        }finally{runCatching{input.close()}}
    }

    fun put(id:GpuUploadQueue.Id,payload:ByteBuffer,dependencies:List<Dependency>){
        if(dependencies.size>128||limit<=0)return
        require(payload.capacity()==payloadBytes)
        val file=path(id);val temp=File(directory,file.name+"-"+java.util.UUID.randomUUID().toString()+".tmp")
        try{
            DataOutputStream(object:GZIPOutputStream(temp.outputStream().buffered()){init{def.setLevel(Deflater.BEST_SPEED)}}).use{out->
                out.writeInt(0x52475433);out.writeInt(dependencies.size)
                for(dep in dependencies){out.writeInt(dep.id.z);out.writeInt(dep.id.x);out.writeInt(dep.id.y);out.writeUTF(dep.fingerprint);out.writeLong(dep.until);out.writeUTF(dep.resourceKey)}
                val data=ByteArray(payload.capacity());payload.duplicate().apply{clear();get(data)}
                out.writeInt(data.size);out.write(data)
            }
            if(temp.length()>limit){temp.delete();return}
            synchronized(this){
                if(!temp.renameTo(file))return
                entries.remove(file)?.let{bytes-=it};entries[file]=file.length();bytes+=file.length();trim()
            }
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
