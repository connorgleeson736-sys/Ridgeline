package nz.ridgeline.nativeapp

import okhttp3.*
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Monotonic HTTP spans, without URLs/query strings. Body probes measure raw
 * source reads before cache writes/decompression. The 20 traffic buckets are
 * bounded; their observed payload rate is not a whole-device Wi-Fi measurement. */
internal class HttpMetrics {
    data class Record(val host:String,val kind:String,val totalMs:Long,val waitMs:Long,val dnsMs:Long,val connectMs:Long,val tlsMs:Long,val headersMs:Long,val wireReadMs:Long,val processingMs:Long,val bodyBytes:Long,val attempts:Int,val protocol:String,val outcome:String){
        fun text()="$host $kind · $totalMs ms · wait/cache $waitMs · DNS $dnsMs · connect incl TLS $connectMs (TLS $tlsMs) · headers $headersMs · wire reads $wireReadMs · processing $processingMs · $bodyBytes B · $attempts attempts · $protocol · $outcome"
    }
    private inner class Trace(val host:String,val kind:String){
        val start=System.nanoTime();val first=AtomicLong()
        val dnsStart=AtomicLong();val dns=AtomicLong();val connectStart=AtomicLong();val connect=AtomicLong();val tlsStart=AtomicLong();val tls=AtomicLong()
        val sent=AtomicLong();val headers=AtomicLong();val wire=AtomicLong();val processing=AtomicLong();val bytes=AtomicLong();val attempts=AtomicInteger();val reading=AtomicInteger()
        /** When OkHttp finished the call; a span never delivered is pruned later. */
        @Volatile var endedAt=0L
        @Volatile var headersAt=0L;@Volatile var protocol="pending";@Volatile var status=0;@Volatile var probe:Probe?=null;@Volatile var cacheBody=false
        fun begin(){first.compareAndSet(0,System.nanoTime())}
        fun phase():String=when{
            dnsStart.get()!=0L->"DNS";tlsStart.get()!=0L->"TLS";connectStart.get()!=0L->"connection"
            reading.get()>0->"wire body read";cacheBody->"cache body read";sent.get()>headersAt->"provider headers"
            headersAt>0->"response/cache processing";else->"dispatch/cache lookup"
        }
        fun record(now:Long,outcome:String):Record {
            fun ms(n:Long)=n.coerceAtLeast(0)/1_000_000
            fun open(start:AtomicLong)=start.get().let{if(it>0)now-it else 0L}
            val cacheNanos=if(cacheBody)now-(first.get().takeIf{it>0}?:now)else 0L
            return Record(host,kind,ms(now-start),ms((first.get().takeIf{it>0}?:now)-start),ms(dns.get()+open(dnsStart)),ms(connect.get()+open(connectStart)),ms(tls.get()+open(tlsStart)),ms(headers.get()),ms(wire.get()),ms(processing.get()+(probe?.tail(now)?:0)+cacheNanos),bytes.get(),attempts.get(),protocol,outcome)
        }
    }
    private inner class Probe(val trace:Trace,val source:ResponseBody):ForwardingSource(source.source()){
        @Volatile private var previous=0L;@Volatile private var closedAt=0L
        override fun read(sink:Buffer,byteCount:Long):Long {
            val start=System.nanoTime()
            trace.processing.addAndGet((start-(previous.takeIf{it>0}?:trace.headersAt)).coerceAtLeast(0))
            trace.reading.incrementAndGet()
            try{return super.read(sink,byteCount).also{n->if(n>0){trace.bytes.addAndGet(n);traffic(n)}}}
            finally{val end=System.nanoTime();trace.wire.addAndGet(end-start);previous=end;trace.reading.decrementAndGet()}
        }
        fun tail(now:Long)=if(trace.reading.get()!=0)0L else if(closedAt>0)(now-closedAt).coerceAtLeast(0)else if(previous>0)(now-previous).coerceAtLeast(0)else 0L
        override fun close(){val now=System.nanoTime();trace.processing.addAndGet(tail(now));closedAt=now;super.close()}
    }
    private val active=ConcurrentHashMap<Call,Trace>()
    private val history=Any();private var latest:Record?=null;private var longest:Record?=null
    private val meter=Any();private val bucket=LongArray(20);private val epochs=LongArray(20){Long.MIN_VALUE}
    private fun traffic(bytes:Long){val epoch=System.nanoTime()/250_000_000;val index=(epoch%20).toInt();synchronized(meter){if(epochs[index]!=epoch){epochs[index]=epoch;bucket[index]=0};bucket[index]+=bytes}}
    fun listener(call:Call):EventListener {
        if(call.request().cacheControl.onlyIfCached)return EventListener.NONE
        val trace=Trace(call.request().url.host,when{ "/elevation/" in call.request().url.encodedPath->"terrain";"/topographic-v2/" in call.request().url.encodedPath->"topography";"/topo-raster/" in call.request().url.encodedPath->"topo";else->"imagery"})
        active[call]=trace
        return object:EventListener(){
            override fun dnsStart(call:Call,domainName:String){trace.begin();trace.probe=null;trace.dnsStart.set(System.nanoTime())}
            override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>){val start=trace.dnsStart.getAndSet(0);if(start>0)trace.dns.addAndGet(System.nanoTime()-start)}
            override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy){trace.begin();trace.attempts.incrementAndGet();trace.connectStart.compareAndSet(0,System.nanoTime())}
            override fun secureConnectStart(call:Call){trace.tlsStart.set(System.nanoTime())}
            override fun secureConnectEnd(call:Call,handshake:Handshake?){val start=trace.tlsStart.getAndSet(0);if(start>0)trace.tls.addAndGet(System.nanoTime()-start)}
            override fun connectFailed(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?,ioe:IOException){val start=trace.tlsStart.getAndSet(0);if(start>0)trace.tls.addAndGet(System.nanoTime()-start)}
            override fun connectionAcquired(call:Call,connection:Connection){trace.begin();val start=trace.connectStart.getAndSet(0);if(start>0)trace.connect.addAndGet(System.nanoTime()-start);trace.protocol=connection.protocol().toString()}
            override fun requestHeadersEnd(call:Call,request:Request){trace.begin();trace.probe=null;trace.cacheBody=false;trace.sent.set(System.nanoTime())}
            override fun responseHeadersEnd(call:Call,response:Response){val now=System.nanoTime();trace.headersAt=now;val sent=trace.sent.get();if(sent>0)trace.headers.addAndGet(now-sent);trace.status=response.code}
            override fun cacheHit(call:Call,response:Response){trace.begin();trace.status=response.code;if(trace.headersAt==0L){trace.cacheBody=true;trace.protocol="cache"}}
            override fun cacheMiss(call:Call){trace.begin()}
            override fun cacheConditionalHit(call:Call,cachedResponse:Response){trace.begin()}
            override fun callEnd(call:Call){trace.endedAt=System.nanoTime()}
            override fun callFailed(call:Call,ioe:IOException){trace.endedAt=System.nanoTime()}
            override fun canceled(call:Call){if(trace.endedAt==0L)trace.endedAt=System.nanoTime()}
        }
    }
    fun intercept(chain:Interceptor.Chain):Response {
        val response=chain.proceed(chain.request());val trace=active[chain.call()]?:return response
        val original=response.body;val probe=Probe(trace,original);trace.probe=probe;val source=probe.buffer()
        return response.newBuilder().body(object:ResponseBody(){override fun contentType()=original.contentType();override fun contentLength()=original.contentLength();override fun source()=source}).build()
    }
    /** Drop a span that is not a tile delivery. */
    fun forget(call:Call){active.remove(call)}
    /** Spans whose call ended long ago but were never delivered (a bounded
     * leak, and a misleading "oldest call" in diagnostics). */
    private fun prune(now:Long){active.entries.removeIf{val ended=it.value.endedAt;ended!=0L&&now-ended>ENDED_RETENTION_NANOS}}
    fun delivered(call:Call,error:Throwable?=null){
        val trace=active.remove(call)?:return
        val record=trace.record(System.nanoTime(),(if(call.isCanceled())"canceled "else"")+(error?.javaClass?.simpleName?:"HTTP ${trace.status}"))
        synchronized(history){latest=record;if(longest==null||record.totalMs>longest!!.totalMs)longest=record}
    }
    fun last():Record?=synchronized(history){latest}
    fun snapshot():String {
        val now=System.nanoTime();val epoch=now/250_000_000
        val rate=synchronized(meter){bucket.indices.sumOf{if(epochs[it] in epoch-19..epoch)bucket[it]else 0L}/5120}
        prune(now)
        val slow=active.entries.asSequence().filter{!it.key.isCanceled()&&it.value.endedAt==0L}.minByOrNull{it.value.start}?.value
        val retired=active.keys.count{it.isCanceled()}
        val (last,max)=synchronized(history){latest to longest}
        return "Observed HTTP body rate (last 5s): $rate KiB/s\n"+
            "Oldest uncanceled HTTP call: "+(slow?.let{"${it.host} ${it.kind} · ${it.phase()} · ${(now-it.start)/1_000_000} ms"}?:"none")+" · retired $retired\n"+
            "Last HTTP phases: "+(last?.text()?:"none")+"\nLongest HTTP phases: "+(max?.text()?:"none")
    }
    private companion object{const val ENDED_RETENTION_NANOS=30_000_000_000L}
}
