package nz.ridgeline.nativeapp

internal fun GpuUploadQueue.Id.contains(other:GpuUploadQueue.Id)=z<=other.z&&(other.x shr(other.z-z))==x&&(other.y shr(other.z-z))==y
internal object ResidentCoverage {
    /** Build once per residency revision, then answer without allocating child lists. */
    class Index(private val available:Set<GpuUploadQueue.Id>){
        private val full:Set<GpuUploadQueue.Id> by lazy {
            available.toMutableSet().apply {
                for(source in available){var node=source
                    while(node.z>0){
                        val parent=GpuUploadQueue.Id(node.z-1,node.x/2,node.y/2)
                        if(parent in this)break
                        if(!(0..3).all{q->GpuUploadQueue.Id(parent.z+1,parent.x*2+q%2,parent.y*2+q/2) in this})break
                        add(parent);node=parent
                    }
                }
            }
        }
        fun complete(target:GpuUploadQueue.Id,allowAncestors:Boolean=true):Boolean {
            if(target in available)return true
            if(allowAncestors){var id=target
                while(id.z>0){id=GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2);if(id in available)return true}}
            return target in full
        }
    }
    fun complete(target:GpuUploadQueue.Id,available:Set<GpuUploadQueue.Id>,allowAncestors:Boolean=true)=Index(available).complete(target,allowAncestors)
}
