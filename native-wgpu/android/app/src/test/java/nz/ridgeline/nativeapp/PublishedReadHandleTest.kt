package nz.ridgeline.nativeapp

import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PublishedReadHandleTest {
 @Test fun benchmarkEstablishedReadsDoNotReenterTheHelperWriterMonitor(){
  fun run(published:Boolean):Double {
   val lock=Any();val expected=Any();val entered=CountDownLatch(1);val release=CountDownLatch(1)
   val timer=Executors.newSingleThreadScheduledExecutor();val calls=AtomicInteger()
   val open={synchronized(lock){calls.incrementAndGet();expected}}
   val handle=PublishedReadHandle(lock,open){true};assertSame(expected,handle.get())
   val writer=Thread{synchronized(lock){entered.countDown();release.await()}}
   try{
    writer.start();assertTrue(entered.await(1,TimeUnit.SECONDS));timer.schedule({release.countDown()},250,TimeUnit.MILLISECONDS)
    val start=System.nanoTime();assertSame(expected,if(published)handle.get()else open())
    val elapsed=(System.nanoTime()-start)/1e6
    assertEquals(if(published)1 else 2,calls.get())
    return elapsed
   }finally{release.countDown();writer.join(1000);timer.shutdownNow();handle.clear()}
  }
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
  val saved=before.sorted()[1]-after.sorted()[1];assertTrue(saved>200)
  val report="""{"scope":"Production PublishedReadHandle used by OfflineStore. Established writable handle, helper-style synchronized getter, writer holds the same application monitor for 250 ms. Measures removed monitor wait, not Android SQLite query or disk throughput.","beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"savedMedianMs":$saved,"sameHandle":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"saved-reader-monitor.json").writeText(report)}
 }
 @Test fun readonlyReopensAndCloseCannotReuseAReplacedHandle(){
  val lock=Any();val reads=AtomicInteger();var writable=false
  val handle=PublishedReadHandle(lock,{reads.incrementAndGet() to writable}){it.second}
  assertEquals(1,handle.get().first);assertEquals(2,handle.get().first)
  writable=true;val published=handle.get();assertEquals(3,published.first);assertSame(published,handle.get())
  handle.clear();val reopened=handle.get();assertEquals(4,reopened.first);assertNotSame(published,reopened)
  val writerOpened=99 to true;handle.publish(writerOpened);assertSame(writerOpened,handle.get());assertEquals(4,reads.get())
  handle.publish(100 to false);assertSame("Read-only fallback cannot replace an established writable handle",writerOpened,handle.get())
 }
}
