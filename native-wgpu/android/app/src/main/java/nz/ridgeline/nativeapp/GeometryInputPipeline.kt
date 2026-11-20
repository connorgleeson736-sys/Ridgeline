package nz.ridgeline.nativeapp

import java.util.concurrent.CompletableFuture

/** Bounded operation-local references, not another retained source cache.
 * Admit every independent input before doing CPU work, decode ready inputs,
 * and resume on the next arrival rather than the slowest arrival. Parent/hole
 * dependencies discovered by decoding can therefore start immediately. */
internal class GeometryInputPipeline<K:Any,V:Any>(private val capacity:Int=8,private val retain:()->Boolean={true}) {
    private val decoded=LinkedHashMap<K,V>()
    @Synchronized fun get(key:K)=decoded[key]
    @Synchronized fun remember(key:K,value:V):V {
        if(retain()&&(decoded.size<capacity||key in decoded))decoded[key]=value
        return value
    }
    @Synchronized fun clear()=decoded.clear()
    @Synchronized fun size()=decoded.size
    fun prepare(keys:Collection<K>,request:(K)->CompletableFuture<*>,decode:(K)->V,onProgress:(Int)->Unit={}) {
        val rows=keys.filter{get(it)==null}.map{it to request(it)}
        val pending=ArrayList<CompletableFuture<*>>()
        for((key,body)in rows){
            if(!body.isDone){pending+=body;continue}
            try{remember(key,decode(key))}catch(waiting:ResourcePending){pending+=waiting.ready}
        }
        onProgress(pending.size)
        if(pending.isNotEmpty())throw ResourcePending(CompletableFuture.anyOf(*pending.toTypedArray()))
    }
}
