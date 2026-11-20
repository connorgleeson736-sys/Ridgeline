package nz.ridgeline.nativeapp

import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OfflinePrefetchTest {
 @Test fun twoTileWindowOverlapsSourcesWithoutDuplicateDownloadsWithZeroRam(){
  fun run(prefetch:Boolean):Double {
   val timer=Executors.newSingleThreadScheduledExecutor();val requests=AtomicInteger();val active=AtomicInteger();val maximum=AtomicInteger()
   val backend=object:TileBackend{
    override fun local(key:ResourceKey):TileBody?=null
    override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{
     requests.incrementAndGet();val running=active.incrementAndGet();maximum.updateAndGet{maxOf(it,running)}
     val task=timer.schedule({active.decrementAndGet();done(Result.success(TileBody(key.url.toByteArray(),200,Long.MAX_VALUE)))},25,TimeUnit.MILLISECONDS)
     return{if(task.cancel(false))active.decrementAndGet()}
    }
   }
   val transport=TileTransport(backend,0);val window=OfflinePrefetch(transport)
   val tiles=List(4){i->List(3){ResourceKey("https://fixture.invalid/tile/$i/source/$it",false)}}
   try{
    val start=System.nanoTime()
    for(i in tiles.indices){
     val lease=if(prefetch)window.advance(i,tiles.subList(i,minOf(i+2,tiles.size)))else transport.consumer(1000,true)
     for(key in tiles[i])assertArrayEquals(key.url.toByteArray(),lease.acquire(key).get(1,TimeUnit.SECONDS).bytes)
     if(!prefetch)lease.release(true)
    }
    val elapsed=(System.nanoTime()-start)/1e6
    assertEquals(12,requests.get());assertEquals(if(prefetch)2 else 1,maximum.get())
    window.close();assertEquals(0L,transport.stats()["backgroundActive"])
    try{window.advance(0,tiles.take(2));fail("Canceled download window cannot restart")}catch(_:PreparationAborted){}
    return elapsed
   }finally{window.close();transport.close();timer.shutdownNow()}
  }
  val before=ArrayList<Double>();val after=ArrayList<Double>()
  repeat(3){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
  val report="""{"scope":"Production OfflinePrefetch and TileTransport, four tiles with three independent original sources each, injected 25 ms response service, two background slots, zero encoded RAM. Not phone/offline-disk throughput.","beforeMs":$before,"afterMs":$after,"beforeMedianMs":${before.sorted()[1]},"afterMedianMs":${after.sorted()[1]},"requestsEach":12,"backgroundMaximum":2,"sameBytes":true}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"offline-prefetch.json").writeText(report)}
 }
}
