package nz.ridgeline.nativeapp

/** One background writer for disposable derived files, bounded by pending
 * bytes rather than a job count. Earlier eight-slot queues discarded finished
 * work during bursts on slow storage, so the same tile was downloaded or
 * decoded again later. When full, the oldest pending writes give way. */
internal class BoundedWrites(name:String,private val byteLimit:Long):AutoCloseable {
    private class Write(val bytes:Long,val work:()->Unit)
    private val lock=Object()
    private val queue=ArrayDeque<Write>()
    private var pending=0L;private var closed=false;private var draining=false
    private var written=0L;private var dropped=0L
    private val thread=Thread({
        runCatching{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)}
        while(true){
            val next=synchronized(lock){
                while(queue.isEmpty()&&!closed&&!draining)lock.wait()
                if(closed||queue.isEmpty())return@Thread
                queue.removeFirst()
            }
            try{next.work()}catch(_:Exception){}
            synchronized(lock){pending-=next.bytes;written++}
        }
    },name).apply{isDaemon=true;start()}
    val isShutdown get()=synchronized(lock){closed||draining}
    /** False if refused (closed, or larger than the whole budget). */
    fun submit(bytes:Long,work:()->Unit):Boolean=synchronized(lock){
        val size=bytes.coerceAtLeast(1)
        if(closed||draining||size>byteLimit){dropped++;return false}
        while(pending+size>byteLimit&&queue.isNotEmpty()){pending-=queue.removeFirst().bytes;dropped++}
        queue.addLast(Write(size,work));pending+=size;lock.notifyAll();true
    }
    fun stats():Map<String,Long> = synchronized(lock){mapOf("queued" to queue.size.toLong(),"pendingBytes" to pending,"written" to written,"dropped" to dropped)}
    /** Pending writes are dropped; a running write finishes on its own. */
    override fun close(){synchronized(lock){closed=true;dropped+=queue.size;queue.clear();lock.notifyAll()}}
    /** Refuse new writes but let pending ones finish (the thread then exits). */
    fun drain(){synchronized(lock){draining=true;lock.notifyAll()}}
}
