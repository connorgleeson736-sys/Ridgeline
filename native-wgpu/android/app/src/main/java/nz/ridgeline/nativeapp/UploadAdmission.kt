package nz.ridgeline.nativeapp

/** Actual JNI upload cost feeds the next decision; backlog gates GPU submission
 * independently because submission time is not GPU execution time. */
internal class UploadAdmission {
    private val costs=doubleArrayOf(2_000_000.0,250_000.0,1_750_000.0)
    private var renderCost=2_000_000.0
    /** While the camera is still, a frame only presents refinement: there is no
     * motion whose cadence an upload could disturb. Use the full idle allowance
     * instead of the frame slack, which reaches zero on 90/120 Hz displays or
     * heavy frames and left refinement waiting on the 250 ms starvation path. */
    fun allowance(gesturing:Boolean,frameNanos:Long=16_666_667L,lateness:Long=0,stationary:Boolean=false):Long =
        if(stationary&&!gesturing)IDLE_ALLOWANCE
        else minOf(if(gesturing)2_000_000L else IDLE_ALLOWANCE,maxOf(0L,(frameNanos-renderCost-lateness.coerceAtLeast(0)-2_000_000).toLong()))
    fun allows(kind:GpuUploadQueue.Kind,elapsed:Long,count:Int,bytes:Int,nextBytes:Int,allowance:Long,backlog:Int,urgent:Boolean=false,starvedIdle:Boolean=false):Boolean =
        backlog<2&&count<4&&bytes+nextBytes<=650*1024&&(count==0&&(urgent||starvedIdle)||allowance>0&&elapsed+costs[kind.ordinal]<=allowance)
    fun uploaded(kind:GpuUploadQueue.Kind,nanos:Long){costs[kind.ordinal]=costs[kind.ordinal]*.5+nanos.coerceAtLeast(1)*.5}
    fun rendered(nanos:Long){renderCost=renderCost*.8+nanos.coerceAtLeast(1)*.2}
    companion object{const val IDLE_ALLOWANCE=4_000_000L}
}
