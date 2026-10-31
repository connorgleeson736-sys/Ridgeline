package nz.ridgeline.nativeapp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Shares overlapping CPU decodes only, without retaining decoded cache entries.
 * The producer MUST NOT do I/O or wait for other source work. Readers own no
 * cancellation authority over another reader's immutable decode result. */
internal class SharedDecode<K:Any,V:Any> {
 private val active=ConcurrentHashMap<K,CompletableFuture<V>>()
 val shared=AtomicLong()
 fun get(key:K,decode:()->V):V {
  val mine=CompletableFuture<V>()
  val existing=active.putIfAbsent(key,mine)
  if(existing!=null){
   shared.incrementAndGet()
   try{return existing.get()}
   catch(error:ExecutionException){throw error.cause?:error}
   catch(error:InterruptedException){Thread.currentThread().interrupt();throw PreparationAborted()}
  }
  try{return decode().also{mine.complete(it)}}
  catch(error:Throwable){mine.completeExceptionally(error);throw error}
  finally{active.remove(key,mine)}
 }
}
