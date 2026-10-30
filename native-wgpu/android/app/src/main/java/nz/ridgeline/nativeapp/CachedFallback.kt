package nz.ridgeline.nativeapp

internal object CachedFallback {
    /** Walk keys, not cached payloads: at most 22 lookups, no I/O or preparation. */
    fun <T:Any> nearest(target:GpuUploadQueue.Id,lookup:(GpuUploadQueue.Id)->T?):Pair<GpuUploadQueue.Id,T>? {
        var id=target
        while(id.z>0){
            id=GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2)
            lookup(id)?.let{return id to it}
        }
        return null
    }
}
