package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.util.Random
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

class PixelPackingTest {
 private fun reference(pixels:IntArray):ByteBuffer=ByteBuffer.allocateDirect(pixels.size*4).apply{
  for(p in pixels){val a=p ushr 24;put(((((p ushr 16)and 255)*a+127)/255).toByte());put(((((p ushr 8)and 255)*a+127)/255).toByte());put((((p and 255)*a+127)/255).toByte());put(a.toByte())};rewind()
 }
 @Test fun allAlphaValuesAndMixedPixelsMatchOriginalExactly(){
  val random=Random(731);val input=IntArray(256*1024){i->(i%256 shl 24)or(random.nextInt()and 0xffffff)}
  val before=reference(input);val after=PixelPacking.rgbaOwned(input.clone())
  assertEquals(0,after.position());assertEquals(before.capacity(),after.capacity())
  for(i in 0 until before.capacity())assertEquals("byte $i",before.get(i),after.get(i))
  val opaque=IntArray(65536){0xff000000.toInt()or random.nextInt(1 shl 24)}
  val a=reference(opaque);val b=PixelPacking.rgbaOwned(opaque.clone());for(i in 0 until a.capacity())assertEquals(a.get(i),b.get(i))
 }
 @Test fun cancellationStopsBeforeBufferPublication(){
  var calls=0
  try{PixelPacking.rgbaOwned(IntArray(65536){-1}){if(++calls==3)throw PreparationAborted()};fail("cancel expected")}catch(_:PreparationAborted){}
  assertEquals(3,calls)
 }
 @Test fun optInMatchedPackingBenchmark(){
  val output=System.getenv("RIDGELINE_PACK_BENCH")?:return
  val fixture=File(System.getenv("RIDGELINE_BENCH_FIXTURE")!!,"tile0.rgba").readBytes()
  val original=IntArray(fixture.size/4){i->val p=i*4;((fixture[p+3].toInt()and 255)shl 24)or((fixture[p].toInt()and 255)shl 16)or((fixture[p+1].toInt()and 255)shl 8)or(fixture[p+2].toInt()and 255)}
  repeat(20){reference(original);PixelPacking.rgbaOwned(original.clone())}
  val before=ArrayList<Long>();val after=ArrayList<Long>()
  fun measure(block:()->Unit):Long{val t=System.nanoTime();block();return System.nanoTime()-t}
  // Give each path an owned input outside timed conversion. Production input is
  // already owned: NativeImagery.load returns a fresh array, not a cached bitmap.
  repeat(60){round->val a=original.clone();val b=original.clone()
   if(round%2==0){before+=measure{reference(a)};after+=measure{PixelPacking.rgbaOwned(b)}}else{after+=measure{PixelPacking.rgbaOwned(b)};before+=measure{reference(a)}}
  }
  fun stats(rows:List<Long>)=JSONObject().put("medianMs",rows.sorted()[rows.size/2]/1e6).put("p95Ms",rows.sorted()[(rows.size*.95).toInt()]/1e6).put("samplesNanos",JSONArray(rows))
  val report=JSONObject().put("scope","Host JVM, genuine LINZ RGBA input; conversion/direct-buffer allocation only, no decode/GPU/phone FPS claim").put("before",stats(before)).put("after",stats(after)).put("pixels",original.size).put("beforeBufferWrites",original.size*4).put("afterBufferWrites",1)
  File(output).apply{parentFile.mkdirs();writeText(report.toString(2))};println(report)
 }
}
