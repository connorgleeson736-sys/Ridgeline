package nz.ridgeline.nativeapp

import java.util.concurrent.atomic.AtomicReference

/** Publishing never waits for surface acquisition or the Rust renderer mutex. */
internal class LatestCamera<T>(initial:T,private val clock:()->Long=System::nanoTime) {
    data class Packet<T>(val pose:T,val revision:Long,val inputNanos:Long)
    private val value=AtomicReference(Packet(initial,0,clock()))
    private val publication=Any()
    fun publish(pose:T)=synchronized(publication){
        val packet=Packet(pose,value.get().revision+1,clock());value.set(packet)
    }
    fun read():Packet<T> = value.get()
}
