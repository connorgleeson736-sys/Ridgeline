package nz.ridgeline.nativeapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class PreparedShortcutTest {
 @Test fun benchmarkValidLateFileBeatsLongSourceRebuildAndKeepsExactBytes(){
  val directory=Files.createTempDirectory("late-prepared").toFile()
  val cache=PreparedTerrainCache(directory,1_048_576,"fixture")
  val id=GpuUploadQueue.Id(12,3983,2600)
  val expected=ByteArray(129*129*4+16){(it*13).toByte()}
  val dependency=PreparedTerrainCache.Dependency(id,"original-version",Long.MAX_VALUE,"original-resource")
  cache.put(id,ByteBuffer.wrap(expected),listOf(dependency))
  fun bytes(buffer:ByteBuffer)=ByteArray(buffer.capacity()).also{buffer.duplicate().apply{clear();get(it)}}
  fun run(race:Boolean):Double {
   val gate=DerivedReadGate();val timer=Executors.newScheduledThreadPool(2);val cpu=Executors.newSingleThreadExecutor()
   val release=CountDownLatch(1);val result=CompletableFuture<ByteBuffer>();var shortcut:PreparedShortcut?=null
   var early:CompletableFuture<ByteBuffer?>?=null;var rebuilt:CompletableFuture<ByteBuffer>?=null
   var rebuildEvent:ScheduledFuture<*>?=null
   val start=System.nanoTime()
   timer.schedule({release.countDown()},40,TimeUnit.MILLISECONDS)
   lateinit var task:Runnable
   task=Runnable{
    var waiting:CompletableFuture<*>?=null
    try{
     val cached=if(race){
      val probe=shortcut?:PreparedShortcut(gate.race(0){release.await();cache.getEntry(id){it.fingerprint=="original-version"}},{it.dependencies.all{d->d.fingerprint=="original-version"}}).also{shortcut=it}
      probe.takeLate()?:probe.ready()
     }else{
      val probe=early?:gate.read(0){release.await();cache.get(id){true}}.also{early=it}
      if(!probe.isDone)throw ResourcePending(probe);probe.getNow(null)
     }
     if(cached!=null)result.complete(cached)
     else{
      val source=rebuilt?:CompletableFuture<ByteBuffer>().also{rebuilt=it;rebuildEvent=timer.schedule({it.complete(ByteBuffer.wrap(expected))},350,TimeUnit.MILLISECONDS)}
      if(!source.isDone)throw ResourcePending(source)
      result.complete(source.getNow(null))
     }
    }catch(pending:ResourcePending){waiting=pending.ready}
    catch(error:Exception){result.completeExceptionally(error)}
    finally{waiting?.let{(shortcut?.resumeOn(it)?:it).whenComplete{_,_->if(!result.isDone)cpu.execute(task)}}}
   }
   try{
    cpu.execute(task);assertArrayEquals(expected,bytes(result.get(2,TimeUnit.SECONDS)))
    return (System.nanoTime()-start)/1e6
   }finally{shortcut?.close();early?.cancel(false);release.countDown();rebuildEvent?.cancel(false);gate.close();cpu.shutdownNow();timer.shutdownNow()}
  }
  try{
   val before=ArrayList<Double>();val after=ArrayList<Double>()
   repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
   val saved=before.sorted()[1]-after.sorted()[1]
   assertTrue("A valid late file should avoid more than 200ms of this injected source wait",saved>200)
   val report="""{"scope":"Production PreparedShortcut, DerivedReadGate and real lossless PreparedTerrainCache file. File released at 40 ms, ordinary cache deadline 12 ms, injected fallback-source service 350 ms. Host critical-path fixture, not phone latency.","beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"savedMedianMs":$saved,"samePreparedBytes":true,"dependenciesRechecked":true}"""
   println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"late-prepared.json").writeText(report)}
  }finally{directory.deleteRecursively()}
 }
 @Test fun adoptionRejectsChangedProvenanceAndGenerationAndCancellationKeepsRealReadCaps(){
  val entry=PreparedTerrainCache.Entry(ByteBuffer.allocate(16),emptyList())
  for(validBefore in listOf(true,false)){
   val valid=AtomicBoolean(validBefore);val late=CompletableFuture<PreparedTerrainCache.Entry?>();val source=CompletableFuture<Unit>()
   val shortcut=PreparedShortcut(DerivedReadGate.Lookup(CompletableFuture.completedFuture(null),late),{valid.get()})
   try{
    assertNull(shortcut.ready());val wake=shortcut.resumeOn(source)
    valid.set(false);late.complete(entry);wake.get(1,TimeUnit.SECONDS)
    assertNull(shortcut.takeLate());assertSame("Rejected late hit must not cause a busy retry loop",source,shortcut.resumeOn(source))
    valid.set(true);assertNull("A rejected version cannot become eligible again",shortcut.takeLate())
   }finally{shortcut.close()}
  }
  val gate=DerivedReadGate(12,2);val entered=CountDownLatch(2);val release=CountDownLatch(1)
  try{
   val lookups=List(2){gate.race(0){entered.countDown();release.await();entry}}
   assertTrue(entered.await(1,TimeUnit.SECONDS));lookups.forEach{assertNull(it.ready.get(1,TimeUnit.SECONDS))}
   lookups[0].late.cancel(false)
   var extraRead=false
   val full=gate.race(0){extraRead=true;entry};assertNull(full.ready.get());assertNull(full.late.get());assertFalse(extraRead)
   release.countDown();assertSame(entry,lookups[1].late.get(1,TimeUnit.SECONDS));assertTrue(lookups[0].late.isCancelled)
  }finally{release.countDown();gate.close()}
 }
}
