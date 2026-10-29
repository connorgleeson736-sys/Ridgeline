package nz.ridgeline.nativeapp

/** Count fallback ancestors inside the same hard source budget as visible leaves.
 * Coarsening preserves coverage; useful off-screen sources stay in the GPU LRU. */
internal object SourceDemand {
    private fun parent(id:GpuUploadQueue.Id)=GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2)
    private fun contains(a:GpuUploadQueue.Id,b:GpuUploadQueue.Id)=a.z<=b.z&&
        (b.x shr(b.z-a.z))==a.x&&(b.y shr(b.z-a.z))==a.y
    fun bounded(input:List<GpuUploadQueue.Id>,budget:Int):Map<GpuUploadQueue.Id,Int> {
        require(budget>=16)
        val leaves=input.distinct().toMutableList()
        fun rank():LinkedHashMap<GpuUploadQueue.Id,Int> {
            val out=LinkedHashMap<GpuUploadQueue.Id,Int>()
            for(leaf in leaves){var id=leaf;while(id.z>minOf(8,leaf.z)){
                id=parent(id);out.putIfAbsent(id,id.z-8)
            }}
            for((index,id)in leaves.withIndex())out[id]=20+index
            return out
        }
        var result=rank()
        while(result.size>budget){
            // Retain the closer leaves' detail where possible. Collapsing a
            // subtree cannot remove any region of the requested visible cover.
            val leaf=leaves.lastOrNull{it.z>0}?:break
            val p=parent(leaf);val slot=leaves.indexOfFirst{contains(p,it)}
            leaves.removeAll{contains(p,it)};leaves.add(slot,p)
            result=rank()
        }
        return result
    }
}
