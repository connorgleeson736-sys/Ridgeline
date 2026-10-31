package nz.ridgeline.nativeapp

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SharedDecodeTest {
 @Test fun concurrentReadersShareOnlyIdenticalInFlightSources() {
  val gate=SharedDecode<String,FloatArray>();val pool=Executors.newFixedThreadPool(2)
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
  try{
   val first=pool.submit<FloatArray>{gate.get("one"){calls.incrementAndGet();entered.countDown();check(release.await(2,TimeUnit.SECONDS));floatArrayOf(1f)}}
   assertTrue(entered.await(2,TimeUnit.SECONDS))
   val second=pool.submit<FloatArray>{gate.get("one"){calls.incrementAndGet();floatArrayOf(2f)}}
   val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
   while(gate.shared.get()==0L&&System.nanoTime()<until)Thread.yield()
   assertEquals(1L,gate.shared.get());release.countDown()
   assertSame(first.get(2,TimeUnit.SECONDS),second.get(2,TimeUnit.SECONDS));assertEquals(1,calls.get())
   assertArrayEquals(floatArrayOf(3f),gate.get("new-fingerprint"){floatArrayOf(3f)},0f)
   // Completed work is not an extra persistent cache, even with a zero RAM limit.
   assertArrayEquals(floatArrayOf(4f),gate.get("one"){floatArrayOf(4f)},0f)
   try{gate.get("failure"){throw IllegalArgumentException("bad PNG")};fail()}catch(_:IllegalArgumentException){}
   assertArrayEquals(floatArrayOf(5f),gate.get("failure"){floatArrayOf(5f)},0f)
  }finally{release.countDown();pool.shutdownNow()}
 }

 @Test fun benchmarkRealConcurrentLocalDemLoading() {
  val fixture=System.getenv("RIDGELINE_PIPELINE_FIXTURE");assumeTrue(fixture!=null)
  val source=File(fixture!!,"elevation-11-1991-1300.png")
  val read=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java)
  val getRGB=Class.forName("java.awt.image.BufferedImage").getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,IntArray::class.java,Integer.TYPE,Integer.TYPE)
  val gate=SharedDecode<String,FloatArray>();val pool=Executors.newFixedThreadPool(2)
  val decodes=AtomicInteger();var round=0
  fun decode(bytes:ByteArray):FloatArray {
   decodes.incrementAndGet()
   val image=read.invoke(null,ByteArrayInputStream(bytes))
   val colors=getRGB.invoke(image,0,0,256,256,null,0,256)as IntArray
   return FloatArray(colors.size){(-10000+(colors[it]and 0xffffff)*.1).toFloat()}
  }
  fun load(shared:Boolean):Long {
   val barrier=CyclicBarrier(2);val key="source-${round++}";val started=System.nanoTime()
   val jobs=List(2){pool.submit<FloatArray>{val bytes=source.readBytes();barrier.await(5,TimeUnit.SECONDS)
    if(shared)gate.get(key){decode(bytes)}else decode(bytes)}}
   val a=jobs[0].get(5,TimeUnit.SECONDS);val b=jobs[1].get(5,TimeUnit.SECONDS)
   assertArrayEquals(a,b,0f)
   return System.nanoTime()-started
  }
  try{
   repeat(16){load(false);load(true)};decodes.set(0);val initialShares=gate.shared.get()
   val before=ArrayList<Long>();val after=ArrayList<Long>()
   repeat(60){i->if(i%2==0){before+=load(false);after+=load(true)}else{after+=load(true);before+=load(false)}}
   val sharedCalls=gate.shared.get()-initialShares
   val report="{\"scope\":\"Host JVM two overlapping local-file readers, genuine LINZ PNG, ImageIO decode and height conversion; excludes Android BitmapFactory, HTTP and GPU\",\"beforeMedianNs\":${before.sorted()[30]},\"afterMedianNs\":${after.sorted()[30]},\"baselineDecodes\":120,\"sharedDecodes\":${120-sharedCalls},\"beforeNs\":$before,\"afterNs\":$after}"
   println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"shared-decode.json").writeText(report)}
  }finally{pool.shutdownNow()}
 }
}
