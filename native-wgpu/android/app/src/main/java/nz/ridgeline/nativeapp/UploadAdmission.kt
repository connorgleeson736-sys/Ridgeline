package nz.ridgeline.nativeapp

/** Actual JNI upload cost feeds the next decision; backlog gates GPU submission
 * independently because submission time is not GPU execution time. */
internal class UploadAdmission {
    private val costs=doubleArrayOf(2_000_000.0,250_000.0,1_750_000.0)
    private var renderCost=2_000_000.0
    fun allowance(gesturing:Boolean,frameNanos:Long=16_666_667L):Long =
        minOf(if(gesturing)2_000_000L else 4_000_000L,maxOf(0L,(frameNanos-renderCost-2_000_000).toLong()))
    fun allows(kind:GpuUploadQueue.Kind,elapsed:Long,count:Int,bytes:Int,nextBytes:Int,allowance:Long,backlog:Int):Boolean =
        backlog<2&&count<4&&bytes+nextBytes<=650*1024&&(count==0||elapsed+costs[kind.ordinal]<=allowance)
    fun uploaded(kind:GpuUploadQueue.Kind,nanos:Long){costs[kind.ordinal]=costs[kind.ordinal]*.5+nanos.coerceAtLeast(1)*.5}
    fun rendered(nanos:Long){renderCost=renderCost*.8+nanos.coerceAtLeast(1)*.2}
}
