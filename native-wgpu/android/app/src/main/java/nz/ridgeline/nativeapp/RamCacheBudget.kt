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
    }
}
