package nz.ridgeline.nativeapp

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class PriorityPipelineTest {
    private fun waitUntil(test:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!test()){if(System.nanoTime()>end)fail("Timed out");Thread.sleep(1)}}
    @Test fun queuedDiskReadsFollowPromotionInsteadOfFifo(){
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val order=CopyOnWriteArrayList<String>()
        val backend=object:TileBackend{
            override fun local(key:ResourceKey):TileBody?{
                if(key.url.startsWith("block")){entered.countDown();release.await(3,TimeUnit.SECONDS)}
                order+=key.url;return TileBody(key.url.toByteArray(),200,Long.MAX_VALUE)
            }
            override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=error("Local hit")
        }
        val transport=TileTransport(backend,0)
        try{
            repeat(2){transport.consumer().acquire(ResourceKey("block$it",false))}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(64){transport.consumer(1000,true).acquire(ResourceKey("background$it",false))}
            val close=transport.consumer(1000,true);val ready=close.acquire(ResourceKey("close",false))
            close.promote(0);release.countDown();ready.get(2,TimeUnit.SECONDS)
            assertTrue(order.indexOf("close") in 1..2);assertTrue(order.takeWhile{it!="close"}.all{it.startsWith("block")});close.close()
        }finally{release.countDown();transport.close()}
    }
    @Test fun cancelledWorkReleasesForegroundCapacityImmediately(){
        val starts=CopyOnWriteArrayList<ResourceKey>();val stops=AtomicInteger()
        val backend=object:TileBackend{
            override fun local(key:ResourceKey):TileBody?=null
            override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts+=key;return{stops.incrementAndGet()}}
        }
        val transport=TileTransport(backend,0)
        try{
            transport.movement(true)
            val old=(0..7).map{transport.consumer().also{c->c.acquire(ResourceKey("old$it",false))}}
            waitUntil{starts.size==8}
            old.take(2).forEach{it.promote(1000,true)}
            old.drop(2).forEach{it.release(true)}
            assertEquals(2L,transport.stats()["networkActive"]);waitUntil{stops.get()==6}
            assertEquals(2L,transport.stats()["backgroundActive"])
            val focus=transport.consumer(0);focus.acquire(ResourceKey("new-focus",false))
            waitUntil{starts.size==9};assertEquals("new-focus",starts.last().url)
        }finally{transport.close()}
    }
    @Test fun parkingCountsDemDependenciesRatherThanOnlyOuterJobs(){
        val starts=AtomicInteger()
        val transport=TileTransport(object:TileBackend{
            override fun local(key:ResourceKey):TileBody?=null
            override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit{starts.incrementAndGet();return{}}
        },0)
        try{
            val halo=transport.consumer(0)
            repeat(4){halo.acquire(ResourceKey("halo$it",false))};waitUntil{starts.get()==4}
            assertFalse(halo.park());assertEquals(0L,transport.stats()["backgroundActive"])
            val one=transport.consumer();one.acquire(ResourceKey("one",false));waitUntil{starts.get()==5}
            assertTrue(one.park());assertEquals(1L,transport.stats()["backgroundActive"])
        }finally{transport.close()}
    }
    @Test fun speculativeCacheFillCannotEvictUsefulForegroundBytes(){
        val transport=TileTransport(object:TileBackend{
            override fun local(key:ResourceKey)=TileBody(ByteArray(8),200,Long.MAX_VALUE)
            override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=error("No network")
        },8)
        try{
            val hot=ResourceKey("hot",false);val foreground=transport.consumer();foreground.acquire(hot).get();foreground.close()
            val background=transport.consumer(1000,true);background.acquire(ResourceKey("speculation",false)).get();background.close()
            assertTrue(transport.contains(hot))
        }finally{transport.close()}
    }
    @Test fun benchmarkCacheQueueLatency(){
        if(System.getenv("RIDGELINE_PRIORITY_BENCH")==null)return
        fun run(current:Boolean):Double{
            val entered=CountDownLatch(2);val release=CountDownLatch(1)
            val backend=object:TileBackend{
                override fun local(key:ResourceKey):TileBody?{
                    if(key.url.startsWith("block")){entered.countDown();release.await(3,TimeUnit.SECONDS)}
                    else Thread.sleep(2) // Controlled storage service time, no network/GPU.
                    return TileBody(byteArrayOf(1),200,Long.MAX_VALUE)
                }
                override fun network(key:ResourceKey,done:(Result<TileBody>)->Unit):()->Unit=error("No network")
            }
            val now=if(current)TileTransport(backend,0)else null
            val old=if(!current)BaselineTileTransport(backend,0)else null
            fun request(key:String,priority:Int)=now?.consumer(priority)?.acquire(ResourceKey(key,false))?:old!!.consumer(priority).acquire(ResourceKey(key,false))
            try{
                repeat(2){request("block$it",0)};assertTrue(entered.await(2,TimeUnit.SECONDS))
                repeat(64){request("background$it",1000)}
                val focus=request("focus",0);val start=System.nanoTime();release.countDown();focus.get(2,TimeUnit.SECONDS)
                return (System.nanoTime()-start)/1e6
            }finally{release.countDown();now?.close();old?.close()}
        }
        val before=ArrayList<Double>();val after=ArrayList<Double>()
        repeat(16){i->if(i%2==0){before+=run(false);after+=run(true)}else{after+=run(true);before+=run(false)}}
        val report="""{"scope":"Host JVM cache queue; two busy readers, 64 queued low-priority hits with controlled 2ms service time. First foreground hit latency; no network, Android disk or GPU claim.","beforeMedianMs":${before.sorted()[8]},"afterMedianMs":${after.sorted()[8]},"beforeMs":$before,"afterMs":$after}"""
        println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{File(it,"cache-queue.json").writeText(report)}
    }
}
