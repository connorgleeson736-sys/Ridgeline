package nz.ridgeline.nativeapp

/** Ideal detail owns the budget first; spare slots progressively load fallback.
 * Never collapse a visible leaf merely to fit its complete ancestor chain. */
internal object SourceDemand {
    private fun parent(id:GpuUploadQueue.Id)=GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2)
    private fun contains(a:GpuUploadQueue.Id,b:GpuUploadQueue.Id)=a.z<=b.z&&
        (b.x shr(b.z-a.z))==a.x&&(b.y shr(b.z-a.z))==a.y
    fun bounded(input:List<GpuUploadQueue.Id>,budget:Int):Map<GpuUploadQueue.Id,Int> {
        require(budget>=16)
        val leaves=input.distinct().toMutableList()
        while(leaves.size>budget){
            val leaf=leaves.lastOrNull{it.z>0}?:break
            val p=parent(leaf);val slot=leaves.indexOfFirst{contains(p,it)}
            leaves.removeAll{contains(p,it)};leaves.add(slot,p)
        }
        val spare=budget-leaves.size
        val ancestors=LinkedHashSet<GpuUploadQueue.Id>()
        for(leaf in leaves){var id=leaf;while(id.z>minOf(8,leaf.z)){id=parent(id);ancestors.add(id)}}
        val available=ancestors.filter{it !in leaves}
        val coarse=available.minOfOrNull{it.z}
        // Broad coverage plus nearest real parents give useful progressive detail;
        // filling the spare pool with every intermediate level strands it at z8.
        val fallback=(available.filter{it.z==coarse}+available.sortedByDescending{it.z}).distinct().take(spare)
        val out=LinkedHashMap<GpuUploadQueue.Id,Int>()
        val focusParent=leaves.firstOrNull()?.takeIf{it.z>0}?.let(::parent)
        for(id in fallback)out[id]=when{ id.z==coarse->0;id==focusParent->2;else->40+id.z}
        // Request the closest full-detail leaf immediately after coarse coverage,
        // rather than waiting for every intermediate ancestor to download first.
        for((index,id)in leaves.withIndex())out[id]=4+index
        return out
    }
}
