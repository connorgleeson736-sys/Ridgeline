package nz.ridgeline.nativeapp

/** Per-consumer checkpoints do not cancel shared source I/O/futures. */
internal class PreparationAborted:RuntimeException(null,null,false,false)
internal class PreparationControl {
    private val current=ThreadLocal<()->Boolean>()
    fun enter(wanted:()->Boolean){current.set(wanted)}
    fun leave(){current.remove()}
    fun checkpoint(){if(current.get()?.invoke()==false||Thread.currentThread().isInterrupted)throw PreparationAborted()}
}
