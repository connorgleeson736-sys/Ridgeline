package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.json.JSONObject
import org.json.JSONArray

/** Opt-in host microbenchmark of production sampler/cache/control/admission.
 * ImageIO stands in for Android PNG decoding. Never an Android frame/FPS claim. */
class PipelineBenchmarkTest {
 @Test fun measureRealTerrainRevisitAndCancellation(){
  val location=System.getenv("RIDGELINE_BENCH_FIXTURE");assumeTrue(location!=null)
  val directory=File(location!!);val files=directory.listFiles()!!.filter{it.name.startsWith("elevation-")&&it.extension=="png"}
  val id=GpuUploadQueue.Id(12,3983,2600)
  val sources=DemSampling.footprint(id.z,id.x,id.y,11)
  fun file(id:GpuUploadQueue.Id)=files.single{it.name=="elevation-${id.z}-${id.x}-${id.y}.png"}
  val readImage=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java)
  val getRgb=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
  fun decode(id:GpuUploadQueue.Id):DemSampling.Raster{
   val image=readImage.invoke(null,file(id));val colors=getRgb.invoke(image,0,0,256,256,null,0,256) as IntArray
   assertTrue(colors.all{it ushr 24==255})
   return DemSampling.Raster(id,FloatArray(colors.size){(-10000+(colors[it] and 0xffffff)*.1).toFloat()})
  }
  fun build(rasters:Map<GpuUploadQueue.Id,DemSampling.Raster>,check:()->Unit={}):ByteBuffer{
   val data=ByteBuffer.allocateDirect(66580).order(ByteOrder.LITTLE_ENDIAN)
   DemSampling.terrainMesh(id.z,id.x,id.y,data,check){rasters.getValue(it)}
   data.putDouble(66564,Double.NaN);data.putDouble(66572,Double.NaN);return data
  }
  val decoded=sources.associateWith(::decode);val expected=build(decoded)
  val temporary=Files.createTempDirectory("ridgeline-real-cache-bench").toFile()
  try{
   val cache=PreparedTerrainCache(temporary,4L*1024*1024,"bench-production-policy")
   val dependencies=sources.map{PreparedTerrainCache.Dependency(it,PreparedTerrainCache.digest(file(it).readBytes()))}
   val writeStart=System.nanoTime();cache.put(id,expected,dependencies);val writeNanos=System.nanoTime()-writeStart
   fun hit()=cache.get(id){PreparedTerrainCache.digest(file(it.id).readBytes())==it.fingerprint}!!
   val restored=hit();for(i in 0 until expected.capacity())assertEquals(expected.get(i),restored.get(i))
   val writes=ArrayList<Long>()
   val raw=ArrayList<Long>();val warm=ArrayList<Long>();val prepared=ArrayList<Long>();val aborted=ArrayList<Long>()
   fun time(block:()->Unit):Long {val t=System.nanoTime();block();return System.nanoTime()-t}
   val control=PreparationControl();var rows=0
   fun obsolete(){rows=0;control.enter{rows<8};try{build(decoded){rows++;control.checkpoint()};fail("Expected cancellation")}catch(_:PreparationAborted){}finally{control.leave()}}
   repeat(8){build(sources.associateWith(::decode));build(decoded);hit();obsolete()}
   repeat(40){iteration->
    // Alternate ordering to reduce fixed warm-up/order bias.
    if(iteration%2==0){prepared+=time{hit()};raw+=time{build(sources.associateWith(::decode))}}else{raw+=time{build(sources.associateWith(::decode))};prepared+=time{hit()}}
    warm+=time{build(decoded)};aborted+=time{obsolete()}
   }
   fun stats(values:List<Long>)=JSONObject().put("medianMs",values.sorted()[values.size/2]/1e6).put("p95Ms",values.sorted()[(values.size*.95).toInt().coerceAtMost(values.size-1)]/1e6).put("samplesNanos",JSONArray(values))
   repeat(16){writes+=time{cache.put(id,expected,dependencies)}}
   val report=JSONObject().put("scope","Host JVM, real LINZ DEM; production sampling/cache/checkpoints. ImageIO decoder, local source-file validation; HTTP lookup and adaptive-profile computation excluded. No A20/FPS claim.")
    .put("samples",40).put("sourceTiles",sources.size).put("rawDiskDecodeAndMesh",stats(raw)).put("decodedMemoryMesh",stats(warm)).put("validatedPreparedDiskHit",stats(prepared)).put("obsoleteAfter7Rows",stats(aborted))
    .put("fullRows",129).put("completedObsoleteRows",7).put("preparedBytes",cache.sizeBytes()).put("uncompressedBytes",66580).put("coldFirstWriteMs",writeNanos/1e6).put("preparedWrite",stats(writes)).put("restoredPayloadIdentical",true)
   val output=File(System.getenv("RIDGELINE_BENCH_OUTPUT")?:temporary.path,"preparation.json");output.parentFile.mkdirs();output.writeText(report.toString(2));println(report)
  }finally{temporary.deleteRecursively()}
 }
 @Test fun replayMeasuredUploadCosts(){
  val path=System.getenv("RIDGELINE_UPLOAD_TRACE");assumeTrue(path!=null)
  val trace=File(path!!).readLines().first{it.startsWith("{\"coordinated\":false")};val samples=JSONObject(trace).getJSONArray("uploadCpuNanos")
  val costs=List(samples.length()){samples.getLong(it)}
  fun replay(adaptive:Boolean):List<Long>{
   val policy=UploadAdmission();val frames=ArrayList<Long>();var index=0
   while(index<costs.size){var elapsed=0L;var count=0;var bytes=0
    while(index<costs.size&&if(adaptive)policy.allows(GpuUploadQueue.Kind.IMAGE,elapsed,count,bytes,262144,policy.allowance(false),0)else count<2){
     val cost=costs[index++];elapsed+=cost;count++;bytes+=262144;policy.uploaded(GpuUploadQueue.Kind.IMAGE,cost)
    };frames+=elapsed
   };return frames
  }
  val before=replay(false);val after=replay(true)
  fun data(rows:List<Long>)=JSONObject().put("frames",rows.size).put("maxUploadCpuMs",rows.max()/1e6).put("framesOver4ms",rows.count{it>4000000}).put("totalUploadCpuMs",rows.sum()/1e6).put("frameUploadNanos",JSONArray(rows))
  val result=JSONObject().put("scope","Production admission policy replay of actual host GPU upload CPU durations; no GPU/frame/phone timing claim. Same uploads, same total CPU cost; changes scheduling only.").put("fixedTwo",data(before)).put("costAware",data(after))
  File(System.getenv("RIDGELINE_BENCH_OUTPUT")!!,"upload-replay.json").writeText(result.toString(2));println(result)
 }
}
