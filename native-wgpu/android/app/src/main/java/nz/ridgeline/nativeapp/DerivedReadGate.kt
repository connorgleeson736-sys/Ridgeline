package nz.ridgeline.nativeapp

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Disposable derived files are an optional shortcut, never a source barrier.
 * A bounded pair of readers owns all file/index/decompression waits. Expiry
 * starts fallback work; opt-in races may adopt a revalidated late result.
 * Running I/O retains its permit until it really exits, even after timeout. */
internal class DerivedReadGate(private val waitMs:Long=12,capacity:Int=16):AutoCloseable {
    data class Lookup<V>(val ready:CompletableFuture<V?>,val late:CompletableFuture<V?>)
    private val permits=Semaphore(capacity)
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private val clock=ScheduledThreadPoolExecutor(1).apply{removeOnCancelPolicy=true}
    private val pending=ConcurrentHashMap.newKeySet<Work<*>>()
    private val order=AtomicLong()
    private val closed=AtomicBoolean()
    private inner class Work<V>(val priority:Int,val allowLate:Boolean,val read:()->V?):Runnable,Comparable<Work<*>> {
        val result=CompletableFuture<V?>()
        val late=CompletableFuture<V?>()
        private val sequence=order.getAndIncrement()
        private val claimed=AtomicBoolean()
        private fun release(){pending.remove(this);permits.release()}
        override fun compareTo(other:Work<*>)=compareValuesBy(this,other,{it.priority},{it.sequence})
        fun discard(){if(claimed.compareAndSet(false,true))release()}
        override fun run(){
            if(!claimed.compareAndSet(false,true))return
            try{if(!result.isDone||allowLate&&!late.isDone){val value=read();result.complete(value);late.complete(value)}else late.complete(null)}
            catch(_:Exception){result.complete(null);late.complete(null)} // Corrupt/unavailable shortcut: use sources.
            finally{release()}
        }
    }
    fun <V> read(priority:Int,read:()->V?):CompletableFuture<V?> = submit(priority,false,read).ready
    fun <V> race(priority:Int,read:()->V?):Lookup<V> = submit(priority,true,read)
    private fun <V> submit(priority:Int,allowLate:Boolean,read:()->V?):Lookup<V> {
        if(closed.get()||!permits.tryAcquire())return Lookup(CompletableFuture.completedFuture(null),CompletableFuture.completedFuture(null))
        val work=Work(priority,allowLate,read);pending.add(work)
        val lookup=Lookup(work.result,work.late)
        val lifetime=if(allowLate)work.late else work.result
        lifetime.whenComplete{_,_->if(workers.remove(work))work.discard()}
        try{
            if(closed.get()){work.result.complete(null);work.late.complete(null);work.discard();return lookup}
            val timeout=clock.schedule({work.result.complete(null)},waitMs,TimeUnit.MILLISECONDS)
            work.result.whenComplete{_,_->timeout.cancel(false)}
            workers.execute(work)
            // Timeout/cancellation may have happened before execute().
            if(lifetime.isDone&&workers.remove(work))work.discard()
        }catch(_:RejectedExecutionException){work.result.complete(null);work.late.complete(null);work.discard()}
        return lookup
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        for(work in pending){work.result.complete(null);work.late.complete(null)}
        for(work in workers.shutdownNow())(work as DerivedReadGate.Work<*>).discard()
        clock.shutdownNow()
    }
}
