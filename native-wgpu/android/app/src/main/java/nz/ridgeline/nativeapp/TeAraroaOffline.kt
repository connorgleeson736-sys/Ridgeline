package nz.ridgeline.nativeapp

/** A Te Araroa offline download: the map along chosen sections, as a painted
 * region (PaintedArea cells). A band [DETAIL_HALF_METERS] either side of each
 * section's line (and the bypasses beside them) at the chosen detail, and a wider
 * band [OVERVIEW_HALF_METERS] at overview detail around it, for the towns, roads
 * and side trips near the trail. Pure Kotlin, unit tested. */
internal object TeAraroaOffline {
    const val DETAIL_HALF_METERS=1_000.0
    const val OVERVIEW_HALF_METERS=4_000.0
    /** The sections chosen and the bypasses (alternatives) alongside them. */
    fun withBypasses(d:TeAraroa,sections:List<TeAraroa.Section>):List<TeAraroa.Section>{
        val mains=sections.filter{!it.alternative}
        val beside=d.options.filter{o->mains.any{s->minOf(o.to,s.to)-maxOf(o.from,s.from)>.3}}
        return (sections+beside).distinctBy{it.slug}
    }
    /** Cells to download: overview band first, the detail band over it. */
    fun cells(d:TeAraroa,sections:List<TeAraroa.Section>,detail:Int):Map<Int,Int>{
        val area=PaintedArea()
        val lines=sections.flatMap{d.lines(it)}.filter{it.size>=2}
        for(l in lines)area.corridor(l,OVERVIEW_HALF_METERS,0)
        for(l in lines)area.corridor(l,DETAIL_HALF_METERS,detail.coerceIn(1,PaintedArea.DETAILS.size-1))
        return area.snapshot()
    }
    /** Walking order [from] a section: it and the next [count]-1 main sections. */
    fun following(d:TeAraroa,from:TeAraroa.Section,count:Int,nobo:Boolean):List<TeAraroa.Section>{
        val order=d.walking(d.mains.sortedBy{it.from},nobo)
        val start=order.indexOfFirst{it.slug==from.slug}
        if(start<0)return listOf(from)
        return order.subList(start,minOf(order.size,start+count))
    }
    fun island(d:TeAraroa,island:String)=d.mains.filter{it.island.equals(island,true)}.sortedBy{it.from}
}
