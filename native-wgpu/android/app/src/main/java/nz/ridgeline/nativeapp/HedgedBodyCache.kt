package nz.ridgeline.nativeapp

import java.io.*
import java.util.UUID
import java.util.zip.CRC32

/** Small share of the existing disposable disk allowance for cache-free HTTP
 * winners. Encoded bytes remain unchanged. All calls belong on storage workers;
 * source delivery never waits for persistence. No URLs are written to disk. */
internal class HedgedBodyCache(private val directory:File,private val limit:Long) {
 private val entries=LinkedHashMap<File,Long>(16,.75f,true)
 private var bytes=0L
 init{if(limit>0){
  directory.mkdirs()
  directory.listFiles()?.filter{it.name.endsWith(".tmp")}?.forEach{it.delete()}
  directory.listFiles()?.filter{it.name.endsWith(".body")}?.sortedBy{it.lastModified()}?.forEach{entries[it]=it.length();bytes+=it.length()}
  trim()
 }}
 private fun file(key:ResourceKey)=File(directory,PreparedTerrainCache.digest(key.url.toByteArray())+".body")
 private fun remove(file:File){entries.remove(file)?.let{bytes-=it};file.delete()}
 private fun trim(){while(bytes>limit&&entries.isNotEmpty())remove(entries.keys.first())}
 fun get(key:ResourceKey,now:Long=System.currentTimeMillis()):TileBody? {
  if(limit<=0)return null
  val file=file(key);synchronized(this){if(entries[file]==null)return null}
  return try{DataInputStream(file.inputStream().buffered()).use{input->
   require(input.readInt()==0x52484231)
   val status=input.readInt();require(status==200||status==404)
   val until=input.readLong();val size=input.readInt();require(size in 0..8*1024*1024)
   if(!key.offline&&until<=now)return null
   val payload=ByteArray(size);input.readFully(payload)
   require(input.readLong()==CRC32().apply{update(payload)}.value&&input.read()==-1)
   TileBody(payload,status,if(key.offline)Long.MAX_VALUE else until)
  }}catch(_:Exception){synchronized(this){remove(file)};null}
 }
 fun put(key:ResourceKey,body:TileBody,now:Long=System.currentTimeMillis()){
  if(limit<=0||body.until<=now||body.status !in listOf(200,404)||body.bytes.size>8*1024*1024||body.bytes.size+28L>limit)return
  val file=file(key);val temp=File(directory,file.name+"-"+UUID.randomUUID()+".tmp")
  try{
   DataOutputStream(temp.outputStream().buffered()).use{out->
    out.writeInt(0x52484231);out.writeInt(body.status);out.writeLong(body.until);out.writeInt(body.bytes.size)
    out.write(body.bytes);out.writeLong(CRC32().apply{update(body.bytes)}.value)
   }
   synchronized(this){if(temp.renameTo(file)){entries.remove(file)?.let{bytes-=it};entries[file]=file.length();bytes+=file.length();trim()}}
  }catch(_:IOException){/* Disposable storage cannot fail the source. */}finally{temp.delete()}
 }
}
