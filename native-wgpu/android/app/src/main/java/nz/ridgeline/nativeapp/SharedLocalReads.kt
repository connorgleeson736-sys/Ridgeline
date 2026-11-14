package nz.ridgeline.nativeapp

import java.util.concurrent.*

/** Original imagery I/O has its own bounded, prioritized workers. Consumers
 * suspend before decode, share immutable bytes, and release queued ownership.
 * A canceled running read keeps its permit until the real I/O exits. */
internal class SharedLocalReads(private val capacity:Int=128):AutoCloseable {
    init{require(capacity>0)}
    private val lock=Any()
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,PriorityBlockingQueue<Runnable>())
    private val jobs=HashMap<String,Work>()
    private var order=0L;private var outstanding=0;private var active=0;private var closed=false
    private var available=CompletableFuture<Unit>()
    private var completed=0L;private var shared=0L;private var lastMs=0L;private var maxMs=0L
    private inner class Work(val key:String,val read:()->ByteArray?):Runnable,Comparable<Work> {
        val result=CompletableFuture<ByteArray?>();val users=HashSet<Consumer>()
        val sequence=order++;val created=System.nanoTime()
        @Volatile var priority=Int.MAX_VALUE
        var running=false;var stopped=false;var permit=true
        override fun compareTo(other:Work)=compareValuesBy(this,other,{it.priority},{it.sequence})
        override fun run(){
            if(!synchronized(lock){if(stopped||closed)false else{running=true;active++;true}})return
            val started=System.nanoTime()
            try{result.complete(read())}catch(e:Exception){result.completeExceptionally(e)}
            finally{
                val wake=synchronized(lock){
                    running=false;active--;completed++
                    lastMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);maxMs=maxOf(maxMs,lastMs)
                    releasePermit(this)
                }
                wake?.complete(Unit)
            }
        }
    }
    // Caller owns lock. Completion/cancellation callbacks always run outside it.
    private fun releasePermit(work:Work):CompletableFuture<Unit>? {
        if(!work.permit)return null
        work.permit=false;outstanding--
        return available.also{available=CompletableFuture()}
    }
    private fun reprioritize(work:Work){
        work.priority=work.users.minOfOrNull{it.priority}?:Int.MAX_VALUE
        if(workers.remove(work))workers.execute(work)
    }
    inner class Consumer internal constructor(internal var priority:Int):AutoCloseable {
        private val owned=HashMap<String,Work>();private var released=false
        fun acquire(key:String,read:()->ByteArray?):CompletableFuture<ByteArray?> = synchronized(lock){
            check(!released&&!closed){"Local imagery consumer closed"}
            owned[key]?.let{return@synchronized it.result}
            var work=jobs[key]
            if(work==null){
                if(outstanding>=capacity)throw ResourcePending(available)
                work=Work(key,read);jobs[key]=work;outstanding++
                work.users.add(this);owned[key]=work;work.priority=priority
                workers.execute(work)
            }else{shared++;work.users.add(this);owned[key]=work;reprioritize(work)}
            work.result
        }
        fun promote(value:Int)=synchronized(lock){if(!released&&priority!=value){priority=value;for(work in owned.values)reprioritize(work)}}
        override fun close(){
            val abandoned=ArrayList<Work>();val wakes=ArrayList<CompletableFuture<Unit>>()
            synchronized(lock){
                if(released)return;released=true
                for(work in owned.values){
                    work.users.remove(this)
                    if(work.users.isEmpty()){
                        jobs.remove(work.key,work);work.stopped=true;abandoned+=work
                        if(workers.remove(work))releasePermit(work)?.let{wakes+=it}
                        // A worker may have dequeued but not entered run yet.
                        else if(!work.running&&!work.result.isDone)releasePermit(work)?.let{wakes+=it}
                    }else reprioritize(work)
                }
                owned.clear()
            }
            for(work in abandoned)work.result.completeExceptionally(PreparationAborted())
            for(wake in wakes)wake.complete(Unit)
        }
    }
    fun consumer(priority:Int)=Consumer(priority)
    fun stats():Map<String,Long> = synchronized(lock){mapOf(
        "active" to active.toLong(),"queued" to workers.queue.size.toLong(),"outstanding" to outstanding.toLong(),
        "completed" to completed,"shared" to shared,"lastMs" to lastMs,"maxMs" to maxMs,
        "oldestMs" to (jobs.values.filter{!it.result.isDone}.minOfOrNull{it.created}?.let{TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-it)}?:0L))}
    override fun close(){
        val abandoned:List<Work>;val wake:CompletableFuture<Unit>
        synchronized(lock){
            if(closed)return;closed=true;abandoned=jobs.values.toList();jobs.clear();wake=available
            for(work in abandoned){work.stopped=true;if(!work.running)releasePermit(work)}
            workers.shutdown() // Do not interrupt a positional read on a shared ZIP descriptor.
            workers.queue.clear()
        }
        for(work in abandoned)work.result.completeExceptionally(PreparationAborted())
        wake.complete(Unit)
    }
    fun awaitClosed(){workers.awaitTermination(5,TimeUnit.SECONDS)}
}
