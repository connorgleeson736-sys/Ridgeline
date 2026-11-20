package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class PreparedAvailable(val data:ByteBuffer):RuntimeException(null,null,false,false)

/** A deadline releases source work, not the value of a late valid cache hit.
 * Single CPU-consumer state; producer callbacks publish only immutable futures.
 * Adoption always checks current dependencies and operation generation again. */
internal class PreparedShortcut(
    private val lookup:DerivedReadGate.Lookup<PreparedTerrainCache.Entry>,
    private val valid:(PreparedTerrainCache.Entry)->Boolean,
    private val onLate:()->Unit={},
):AutoCloseable {
    private val hit=CompletableFuture<PreparedTerrainCache.Entry>()
    private val closed=AtomicBoolean()
    private var fallback=false;private var adopted=false;private var rejected=false
    init{lookup.late.whenComplete{value,_->if(value!=null&&!closed.get())hit.complete(value)}}
    fun ready():ByteBuffer? {
        if(closed.get()||lookup.ready.isCancelled)throw PreparationAborted()
        if(!lookup.ready.isDone)throw ResourcePending(lookup.ready)
        val early=lookup.ready.getNow(null)
        if(early!=null&&valid(early)){adopted=true;return early.payload}
        fallback=true
        return takeLate()
    }
    fun takeLate():ByteBuffer? {
        if(!fallback||adopted||rejected||closed.get()||!hit.isDone||hit.isCompletedExceptionally)return null
        val entry=hit.getNow(null)?:return null
        if(!valid(entry)){rejected=true;return null}
        adopted=true;onLate();return entry.payload
    }
    fun resumeOn(waiting:CompletableFuture<*>):CompletableFuture<*> =
        if(fallback&&!adopted&&!rejected&&!closed.get())CompletableFuture.anyOf(waiting,hit)else waiting
    override fun close(){if(closed.compareAndSet(false,true)){lookup.ready.cancel(false);lookup.late.cancel(false);hit.cancel(false)}}
}
