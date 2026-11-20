package nz.ridgeline.nativeapp

/** Retained tile payloads only; active jobs, object overhead and GPU memory are separate. */
internal data class RamCacheBudget(val encoded:Long,val decoded:Long,val prepared:Long) {
    val total get()=encoded+decoded+prepared
    companion object {
        fun split(mib:Int):RamCacheBudget {
            val bytes=mib.coerceIn(0,1000)*1_048_576L
            return RamCacheBudget(bytes/4,bytes/4,bytes-bytes/2)
        }
        fun recommended(memoryClassMiB:Int,lowRam:Boolean)=if(lowRam)32 else (memoryClassMiB/4).coerceIn(32,96)
        /** The encoded and decoded caches are Java arrays: together at most this
         * share of the app's Java heap limit (Runtime.maxMemory, often 256 MB),
         * leaving the rest for map tiles, names and everything else. */
        const val HEAP_SHARE=.3
        /** The prepared cache is native memory: at most this share of the phone's RAM, and [PREPARED_MAX]. */
        const val RAM_SHARE=1.0/16
        const val PREPARED_MAX=512L*1_048_576
        /** [split] within what this phone can hold: a cache larger than the heap
         * (the slider went to 1000 MiB; half of it was Java arrays) filled the
         * heap in busy areas, so garbage collection stalled loading and the app
         * ran out of memory. */
        fun split(mib:Int,heapMaxBytes:Long,deviceRamBytes:Long):RamCacheBudget{
            val wanted=split(mib)
            val heap=(heapMaxBytes*HEAP_SHARE).toLong().coerceAtLeast(0)
            val javaWanted=wanted.encoded+wanted.decoded
            val f=if(javaWanted>heap&&javaWanted>0)heap.toDouble()/javaWanted else 1.0
            val prepared=minOf(wanted.prepared,(deviceRamBytes*RAM_SHARE).toLong(),PREPARED_MAX)
            return RamCacheBudget((wanted.encoded*f).toLong(),(wanted.decoded*f).toLong(),prepared.coerceAtLeast(0))
        }
        /** The largest setting [split] honours in full on this phone, in MiB. */
        fun maximumMiB(heapMaxBytes:Long,deviceRamBytes:Long):Int{
            val byHeap=(heapMaxBytes*HEAP_SHARE*2/1_048_576).toLong()
            val byRam=2*minOf((deviceRamBytes*RAM_SHARE).toLong(),PREPARED_MAX)/1_048_576
            return minOf(byHeap,byRam,1000).toInt().coerceAtLeast(32)
        }
    }
}
