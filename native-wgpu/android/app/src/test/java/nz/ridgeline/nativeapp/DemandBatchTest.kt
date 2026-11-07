package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class DemandBatchTest {
 private fun waitUntil(test:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!test()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(1)}}
 @Test fun aCameraChangeCannotAdmitTheRestOfAnObsoleteQueueWhileCancelingIt(){
  fun replay(batch:Boolean):Int {
   val starts=AtomicInteger()
   val transport=TileTransport(object:TileBackend{
    override fun local(key:ResourceKey):TileBody?=null
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();return{}}
   },0)
   try{
    transport.movement(true)
    val owners=(0..7).map{i->transport.consumer().also{it.acquire(ResourceKey("active$i",false))}}.toMutableList()
    waitUntil{starts.get()==8}
    owners+=(0..63).map{i->transport.consumer().also{it.acquire(ResourceKey("obsolete$i",false))}}
    waitUntil{transport.stats()["networkQueued"]==64L}
    val cancel={owners.forEach{it.release(true)}}
    if(batch)transport.batchDemand(cancel)else cancel()
    val beforeFocus=starts.get();assertEquals(0L,transport.stats()["pending"]);assertEquals(0L,transport.stats()["networkActive"])
    val fresh=transport.consumer();fresh.acquire(ResourceKey("new-focus",false));waitUntil{starts.get()==beforeFocus+1};fresh.release(true)
    return beforeFocus
   }finally{transport.close()}
  }
  val before=replay(false);val after=replay(true)
  assertEquals(72,before);assertEquals(8,after)
  val report="""{"scope":"Production transport cancellation replay: 8 active plus 64 queued obsolete sources. Backend network admissions, not TCP requests, bytes or phone latency.","beforeAdmissions":$before,"afterAdmissions":$after,"unnecessaryAdmissionsBefore":${before-8},"unnecessaryAdmissionsAfter":${after-8},"freshFocusAdmitted":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"demand-batch.json").writeText(report)}
 }
 @Test fun nestedAndFailedUpdatesReleaseAdmissionWithoutHoldingTheMonitor(){
  val starts=AtomicInteger()
  val transport=TileTransport(object:TileBackend{
   override fun local(key:ResourceKey):TileBody?=null
   override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();return{}}
  },0)
  try{
   try{transport.batchDemand {
    transport.batchDemand{transport.consumer().acquire(ResourceKey("keep",false))}
    waitUntil{transport.stats()["networkQueued"]==1L};assertEquals(0,starts.get());error("Abort update")
   };fail()}catch(e:IllegalStateException){assertEquals("Abort update",e.message)}
   waitUntil{starts.get()==1};assertEquals(1L,transport.stats()["networkActive"])
  }finally{transport.close()}
 }
}
