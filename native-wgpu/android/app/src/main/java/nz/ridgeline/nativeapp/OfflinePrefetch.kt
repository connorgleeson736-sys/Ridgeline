package nz.ridgeline.nativeapp

/** Two tiles of original source futures overlap offline network latency. The
 * normal background cap and foreground priorities still own HTTP admission.
 * Reuse the lease when consuming bytes, including with a zero RAM-cache budget. */
internal class OfflinePrefetch(private val transport:TileTransport):AutoCloseable {
    private val leases=HashMap<Int,TileTransport.Consumer>()
    private var closed=false
    @Synchronized fun advance(index:Int,inputs:List<List<ResourceKey>>):TileTransport.Consumer {
        if(closed)throw PreparationAborted()
        require(inputs.isNotEmpty()&&inputs.size<=2)
        val obsolete=leases.keys.filter{it<index||it>=index+inputs.size}
        for(key in obsolete)leases.remove(key)?.release(true)
        for((offset,keys)in inputs.withIndex()){
            val at=index+offset;val priority=1000+offset*100
            val lease=leases.getOrPut(at){transport.consumer(priority,true)}
            lease.promote(priority,true)
            for(key in keys.distinct())lease.acquire(key)
        }
        return leases.getValue(index)
    }
    @Synchronized override fun close(){if(closed)return;closed=true;for(lease in leases.values)lease.release(true);leases.clear()}
}
