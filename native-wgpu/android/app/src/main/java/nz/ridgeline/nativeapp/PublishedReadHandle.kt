package nz.ridgeline.nativeapp

/** Once a writable WAL database is established, readers must not re-enter
 * SQLiteOpenHelper's synchronized getter behind an application writer lock.
 * Read-only fallback handles are not retained: a later writable reopen may
 * replace and close them. Initialization/close still use the helper monitor. */
internal class PublishedReadHandle<T:Any>(private val lock:Any,private val open:()->T,private val retain:(T)->Boolean){
    @Volatile private var current:T?=null
    fun get():T=current?:synchronized(lock){current?:open().also{if(retain(it))current=it}}
    fun publish(value:T){if(current===value)return;synchronized(lock){if(retain(value))current=value}}
    fun clear()=synchronized(lock){current=null}
}
