package nz.ridgeline.nativeapp

import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SharedLocalReadsTest {
 @Test fun benchmarkReadyImageCpuDoesNotQueueBehindOriginalFileReads(){
  fun run(separate:Boolean):Double {
   val cpu=Executors.newFixedThreadPool(2);val timer=Executors.newSingleThreadScheduledExecutor()
   val local=SharedLocalReads();val entered=CountDownLatch(2);val release=CountDownLatch(1)
   val owners=(0..1).map{local.consumer(it)};val results=List(2){CompletableFuture<ByteArray>()}
   val original=byteArrayOf(11,23,41,127)
   try{
    for(i in 0..1){
     val read={entered.countDown();release.await();original as ByteArray?}
     lateinit var work:Runnable
     work=Runnable{
      try{
       val bytes=if(separate){val f=owners[i].acquire("local/$i",read);if(!f.isDone)throw ResourcePending(f);f.get()}else read()
       results[i].complete(bytes!!)
      }catch(pending:ResourcePending){pending.ready.whenComplete{_,_->cpu.execute(work)}}
      catch(e:Exception){results[i].completeExceptionally(e)}
     }
     cpu.execute(work)
    }
    assertTrue(entered.await(1,TimeUnit.SECONDS))
    timer.schedule({release.countDown()},250,TimeUnit.MILLISECONDS)
    val start=System.nanoTime()
    assertArrayEquals(original,cpu.submit<ByteArray>{original.copyOf()}.get(1,TimeUnit.SECONDS))
    val elapsed=(System.nanoTime()-start)/1e6
    results.forEach{assertArrayEquals(original,it.get(1,TimeUnit.SECONDS))}
    return elapsed
   }finally{release.countDown();owners.forEach{it.close()};local.close();cpu.shutdownNow();timer.shutdownNow()}
  }
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
  val report="""{"scope":"Host JVM, production SharedLocalReads, two CPU workers and two original-image reads held for 250 ms. Measures ready-image CPU queue latency, not phone speed or storage throughput.","trials":3,"beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"sameBytes":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"original-io.json").writeText(report)}
 }
 @Test fun sharedOwnershipPriorityCancellationAndRealIoCapacityAreBounded(){
  val local=SharedLocalReads(3);val blocked=CountDownLatch(2);val release=CountDownLatch(1)
  val a=local.consumer(50);val b=local.consumer(20);val urgent=local.consumer(0)
  val calls=AtomicInteger();val bytes=byteArrayOf(5,7)
  try{
   val first=a.acquire("shared"){calls.incrementAndGet();blocked.countDown();release.await();bytes}
   val shared=b.acquire("shared"){error("Must deduplicate")};assertSame(first,shared)
   val second=a.acquire("second"){blocked.countDown();release.await();bytes}
   assertTrue(blocked.await(1,TimeUnit.SECONDS))
   var obsoleteRan=false
   a.acquire("obsolete"){obsoleteRan=true;bytes}
   val space=try{urgent.acquire("urgent"){bytes};error("Must yield at real I/O capacity")}catch(e:ResourcePending){e.ready}
   a.close();assertFalse(first.isDone);assertTrue(second.isCompletedExceptionally)
   space.get(1,TimeUnit.SECONDS)
   // The canceled running second read still occupies its permit; only the
   // canceled queued read's slot is reusable before actual I/O completion.
   val current=urgent.acquire("urgent"){bytes}
   try{urgent.acquire("excess"){error("Over capacity")};fail("Must retain running permit")}catch(_:ResourcePending){}
   assertFalse(current.isDone);assertFalse(obsoleteRan)
   release.countDown();assertArrayEquals(bytes,shared.get(1,TimeUnit.SECONDS));assertArrayEquals(bytes,current.get(1,TimeUnit.SECONDS))
   assertEquals(1,calls.get());assertFalse(obsoleteRan)
  }finally{release.countDown();a.close();b.close();urgent.close();local.close();local.awaitClosed()}
 }
}
