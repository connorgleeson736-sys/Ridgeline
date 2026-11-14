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
    private var lastMovingImage=Long.MIN_VALUE/2
    /** Minimum refinement while the camera moves or a gesture is active. Spare
     * frame time is often zero on 90/120 Hz displays, and camera-first deferral
     * repeats throughout a gesture, so refinement otherwise waited until the
     * camera stopped. Bounded so movement frames stay smooth:
     *  - terrain-only uploads (66 KiB height grids): up to 2 per frame within
     *    2 ms of measured upload time this frame;
     *  - imagery: one per frame within 4 ms; where a single upload is known to
     *    cost more, at most one every 100 ms.
     * GPU backlog, the 4-upload and 650 KiB per-frame limits always apply. */
    fun movementMinimum(kind:GpuUploadQueue.Kind,elapsed:Long,count:Int,bytes:Int,nextBytes:Int,backlog:Int,geometryThisFrame:Int,imagesThisFrame:Int,now:Long):Boolean{
        if(backlog>=2||count>=4||bytes+nextBytes>650*1024)return false
        val cost=costs[kind.ordinal]
        return if(kind==GpuUploadQueue.Kind.GEOMETRY)geometryThisFrame<2&&elapsed+cost<=2_000_000
        else imagesThisFrame==0&&(elapsed+cost<=4_000_000||now-lastMovingImage>=100_000_000L)
    }
    fun movementUploaded(kind:GpuUploadQueue.Kind,now:Long){if(kind!=GpuUploadQueue.Kind.GEOMETRY)lastMovingImage=now}
    companion object{const val IDLE_ALLOWANCE=4_000_000L}
}
