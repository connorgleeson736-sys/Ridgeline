package nz.ridgeline.nativeapp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Shares overlapping CPU decodes only, without retaining decoded cache entries.
 * The producer MUST NOT do I/O or wait for other source work. Readers own no
 * cancellation authority over another reader's immutable decode result. */
internal class SharedDecode<K:Any,V:Any> {
 /** Bounded suspended decodes per consumer; keeps completed results across the
  * producer's removal from active, without a global decoded-retention cache. */
 class Waiter<K:Any,V:Any> {
  private val entries=LinkedHashMap<K,CompletableFuture<V>>()
  @Synchronized fun find(key:K)=entries[key]
  @Synchronized fun forget(key:K){entries.remove(key)}
  @Synchronized fun remember(key:K,value:CompletableFuture<V>){entries[key]=value;while(entries.size>8)entries.remove(entries.keys.first())}
  @Synchronized fun clear()=entries.clear()
 }
 private val active=ConcurrentHashMap<K,CompletableFuture<V>>()
 val shared=AtomicLong()
 fun get(key:K,waiter:Waiter<K,V>?=null,decode:()->V):V {
  waiter?.find(key)?.let{future->
   if(!future.isDone)throw ResourcePending(future)
   waiter.forget(key)
   try{return future.get()}catch(error:ExecutionException){throw error.cause?:error}
  }
  val mine=CompletableFuture<V>()
  val existing=active.putIfAbsent(key,mine)
  if(existing!=null){
   shared.incrementAndGet()
   if(waiter!=null&&!existing.isDone){waiter.remember(key,existing);throw ResourcePending(existing)}
   try{return existing.get()}
   catch(error:ExecutionException){throw error.cause?:error}
   catch(error:InterruptedException){Thread.currentThread().interrupt();throw PreparationAborted()}
  }
  try{return decode().also{mine.complete(it)}}
  catch(error:Throwable){mine.completeExceptionally(error);throw error}
  finally{active.remove(key,mine)}
 }
}
