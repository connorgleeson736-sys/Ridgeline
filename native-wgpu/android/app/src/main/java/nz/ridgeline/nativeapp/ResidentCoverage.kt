package nz.ridgeline.nativeapp

internal fun GpuUploadQueue.Id.contains(other:GpuUploadQueue.Id)=z<=other.z&&(other.x shr(other.z-z))==x&&(other.y shr(other.z-z))==y
internal object ResidentCoverage {
    fun complete(target:GpuUploadQueue.Id,available:Set<GpuUploadQueue.Id>,allowAncestors:Boolean=true):Boolean{
        if(if(allowAncestors)available.any{it.contains(target)}else target in available)return true
        fun children(id:GpuUploadQueue.Id,rows:List<GpuUploadQueue.Id>):Boolean{
            if(id in available)return true
            if(rows.isEmpty()||id.z>=22)return false
            return (0..3).all{q->val child=GpuUploadQueue.Id(id.z+1,id.x*2+q%2,id.y*2+q/2);children(child,rows.filter{child.contains(it)})}
        }
        return children(target,available.filter{target.contains(it)})
    }
}
