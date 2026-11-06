package nz.ridgeline.nativeapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PreparedCacheReadTest {
 @Test fun validatorSuspensionDoesNotDeleteOrAcceptPayload() {
  val dir=Files.createTempDirectory("header-validation").toFile()
  try{
   val id=GpuUploadQueue.Id(12,3983,2600);val cache=PreparedTerrainCache(dir,1000000,"test",262144)
   cache.put(id,ByteBuffer.allocateDirect(262144),emptyList())
   val waiting=CompletableFuture<Unit>()
   try{cache.getChecked(id){throw ResourcePending(waiting)};fail()}catch(pending:ResourcePending){assertSame(waiting,pending.ready)}
   assertNull(cache.getChecked(id){false});assertNotNull(cache.getChecked(id){true})
   // A fresh dependency does not bypass payload corruption/CRC checks.
   val file=dir.listFiles()!!.single();val bytes=file.readBytes();bytes[bytes.size-5]=(bytes[bytes.size-5].toInt()xor 1).toByte();file.writeBytes(bytes)
   assertNull(cache.getChecked(id){true})
  }finally{dir.deleteRecursively()}
 }

 @Test fun benchmarkRejectedAndAcceptedRealImagePayloads() {
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE");assumeTrue(fixture!=null)
  val payload=File(fixture!!,"tile0.rgba").readBytes();assertEquals(262144,payload.size)
  val dir=Files.createTempDirectory("derived-read-bench").toFile()
  try{
   val id=GpuUploadQueue.Id(12,3983,2600)
   val old=BaselinePreparedTerrainCache(File(dir,"old"),1000000,"test",payload.size)
   val current=PreparedTerrainCache(File(dir,"current"),1000000,"test",payload.size)
   val buffer=ByteBuffer.allocateDirect(payload.size).apply{put(payload);rewind()}
   old.put(id,buffer,emptyList());current.put(id,buffer,emptyList())
   val expected=old.getChecked(id){true}!!;val actual=current.getChecked(id){true}!!
   assertEquals(expected,actual)
   fun time(block:()->Unit):Long{val start=System.nanoTime();block();return System.nanoTime()-start}
   val result=StringBuilder("{\"scope\":\"Host JVM derived RGBA disk-cache reads, genuine LINZ pixels, source validation returns immediately; no HTTP or GPU\"")
   for(fresh in listOf(false,true)){
    repeat(40){old.getChecked(id){fresh};current.getChecked(id){fresh}}
    val a=ArrayList<Long>();val b=ArrayList<Long>()
    repeat(100){i->if(i%2==0){a+=time{old.getChecked(id){fresh}};b+=time{current.getChecked(id){fresh}}}else{b+=time{current.getChecked(id){fresh}};a+=time{old.getChecked(id){fresh}}}}
    result.append(",\"${if(fresh)"fresh" else "stale"}\":{\"beforeMedianNs\":${a.sorted()[50]},\"afterMedianNs\":${b.sorted()[50]},\"beforeNs\":$a,\"afterNs\":$b}")
   }
   result.append("}");println(result)
   System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"derived-cache-read.json").writeText(result.toString())}
  }finally{dir.deleteRecursively()}
 }
}
