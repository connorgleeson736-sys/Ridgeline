package nz.ridgeline.nativeapp

/** Keep the whole visible region covered while closest detail starts immediately. */
internal object SourceDemand {
    private fun parent(id:GpuUploadQueue.Id)=GpuUploadQueue.Id(id.z-1,id.x/2,id.y/2)
    private fun contains(a:GpuUploadQueue.Id,b:GpuUploadQueue.Id)=a.z<=b.z&&
        (b.x shr(b.z-a.z))==a.x&&(b.y shr(b.z-a.z))==a.y
    fun coarseAsset(id:GpuUploadQueue.Id,available:(String)->Boolean):GpuUploadQueue.Id? {
        for(level in minOf(id.z,8) downTo 0){val shift=id.z-level;val x=id.x shr shift;val y=id.y shr shift
            if(available("$level/$x/$y"))return GpuUploadQueue.Id(level,x,y)}
        return null
    }
    /** One real coarse source establishes cold coverage. Preserve all ideal
     * leaves and the nearest normal parent; only a distant fallback may yield
     * its slot. This never expands the existing source budget. */
    fun bootstrap(rows:Map<GpuUploadQueue.Id,Int>,coarse:GpuUploadQueue.Id,budget:Int):Map<GpuUploadQueue.Id,Int> {
        if(rows.isEmpty()||rows.keys.none{contains(coarse,it)})return rows
        val next=LinkedHashMap(rows)
        if(coarse !in next&&next.size>=budget){
            val victim=next.keys.filter{id->next.getValue(id)>0&&next.keys.any{it!=id&&contains(id,it)}}.maxByOrNull{next.getValue(it)}?:return rows
            next.remove(victim)
        }
        for((id,rank)in next.toMap())next[id]=rank+1
        next[coarse]=0
        return next
    }
    fun bounded(input:List<GpuUploadQueue.Id>,budget:Int):Map<GpuUploadQueue.Id,Int> {
        require(budget>=16)
        val leaves=input.distinct().toMutableList()
        fun collapse(rows:MutableList<GpuUploadQueue.Id>){
            var selected=-1
            for(i in rows.indices)if(rows[i].z>0&&(selected<0||rows[i].z>=rows[selected].z))selected=i
            if(selected<0)return
            val p=parent(rows[selected]);val slot=rows.indexOfFirst{contains(p,it)}
            rows.removeAll{contains(p,it)};rows.add(slot,p)
        }
        while(leaves.size>budget)collapse(leaves)
        val spare=budget-leaves.size
        val fallback=leaves.filter{it.z>0}.map(::parent).distinct().filter{it !in leaves}.toMutableList()
        while(fallback.size>spare&&spare>0)collapse(fallback)
        if(spare==0)fallback.clear()
        val out=LinkedHashMap<GpuUploadQueue.Id,Int>()
        val focus=leaves.firstOrNull()
        for((index,id)in fallback.withIndex())out[id]=if(focus!=null&&contains(id,focus))0 else 8+index
        // A small focus neighbourhood starts before distant fallback work. One
        // focus parent still establishes coverage first; far leaves follow parents.
        for((index,id)in leaves.withIndex())out[id]=if(index<4)1+index else 64+index
        return out
    }
}
