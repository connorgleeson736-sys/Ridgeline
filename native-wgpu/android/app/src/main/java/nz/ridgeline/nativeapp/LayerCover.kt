package nz.ridgeline.nativeapp

/** Cached, still-visible vector parents/children bridge source zoom changes without overdraw. */
internal object LayerCover {
    data class Patch(val source:GpuUploadQueue.Id,val clip:GpuUploadQueue.Id)
    private fun contains(a:GpuUploadQueue.Id,b:GpuUploadQueue.Id)=a.z<=b.z&&a.x==b.x.shr(b.z-a.z)&&a.y==b.y.shr(b.z-a.z)
    fun display(wanted:List<GpuUploadQueue.Id>,available:Set<GpuUploadQueue.Id>):List<Patch>{
        val result=ArrayList<Patch>()
        for(target in wanted){
            if(target in available){result.add(Patch(target,target));continue}
            val parent=available.filter{contains(it,target)}.maxByOrNull{it.z}
            if(parent!=null){result.add(Patch(parent,target));continue}
            val children=available.filter{contains(target,it)}.sortedBy{it.z};val selected=ArrayList<GpuUploadQueue.Id>()
            for(child in children)if(selected.none{contains(it,child)}){selected.add(child);result.add(Patch(child,child))}
        };return result
    }
}
