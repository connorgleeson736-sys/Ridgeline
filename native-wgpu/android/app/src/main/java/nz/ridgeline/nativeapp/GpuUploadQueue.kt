package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
/** Separate immutable sources coalesce independently, with a hard per-frame byte budget. */
class GpuUploadQueue(private val capacity:Int) {
    enum class Kind{COMBINED,GEOMETRY,IMAGE}
    data class Id(val z:Int,val x:Int,val y:Int){val packed get()=(z.toLong() shl 44)or(x.toLong() shl 22)or y.toLong()}
    data class Tile(val id:Id,val heights:ByteBuffer,val rgba:ByteBuffer,val kind:Kind=Kind.COMBINED){val bytes get()=heights.capacity()+rgba.capacity()}
    private val entries=LinkedHashMap<Pair<Id,Kind>,Tile>()
    private var warm:Pair<Set<Id>,Set<Id>> = emptySet<Id>() to emptySet()
    private var admitted:Pair<Set<Id>,Set<Id>>?=null
    private var rankedDemand:Pair<Map<Id,Int>,Map<Id,Int>>?=null
    private var ranks:Pair<Map<Id,Int>,Map<Id,Int>>?=null
    private var parents:Pair<Set<Id>,Set<Id>> = emptySet<Id>() to emptySet()
    private fun wanted(tile:Tile):Boolean {
        val current=admitted?:return true // Exact targets plus their useful ancestors.
        return when(tile.kind){
            Kind.GEOMETRY->tile.id in current.first||tile.id in warm.first
            Kind.IMAGE->tile.id in current.second||tile.id in warm.second
            Kind.COMBINED->tile.id in current.first||tile.id in current.second||tile.id in warm.first||tile.id in warm.second
        }
    }
    private fun foreground(tile:Tile):Boolean{
        val current=admitted?:return true
        return when(tile.kind){Kind.GEOMETRY->tile.id in current.first;Kind.IMAGE->tile.id in current.second;Kind.COMBINED->tile.id in current.first||tile.id in current.second}
    }
    /** Prune queued ownership only; resident fallback coverage remains intact. */
    @Synchronized fun updateDemand(geometry:Set<Id>,images:Set<Id>){
        fun ancestors(ids:Set<Id>):Set<Id> = buildSet {
            for(target in ids){var id=target;while(id.z>0){id=Id(id.z-1,id.x/2,id.y/2);if(!add(id))break}}
        }
        ranks=null;rankedDemand=null
        parents=ancestors(geometry) to ancestors(images)
        admitted=(geometry+parents.first) to (images+parents.second)
        entries.entries.removeAll{!wanted(it.value)}
    }
    /** Carry the same view rank through fetch, preparation and GPU admission. */
    @Synchronized fun updateRankedDemand(geometry:Map<Id,Int>,images:Map<Id,Int>){
        if(rankedDemand?.let{it.first==geometry&&it.second==images}==true)return
        updateDemand(geometry.keys,images.keys)
        fun priorities(rows:Map<Id,Int>):Map<Id,Int> = buildMap {
            // A restored cached ancestor inherits its closest dependent's rank.
            for((target,rank)in rows){var id=target
                while(id.z>0){id=Id(id.z-1,id.x/2,id.y/2);val old=get(id);if(old!=null&&old<=rank)break;put(id,rank)}}
            putAll(rows)
        }
        ranks=priorities(geometry) to priorities(images)
        rankedDemand=geometry.toMap() to images.toMap()
    }
    private fun rank(tile:Tile):Int {
        val current=ranks?:return 0
        return when(tile.kind){
            Kind.GEOMETRY->current.first[tile.id]
            Kind.IMAGE->current.second[tile.id]
            Kind.COMBINED->minOf(current.first[tile.id]?:Int.MAX_VALUE,current.second[tile.id]?:Int.MAX_VALUE)
        }?:Int.MAX_VALUE
    }
    @Synchronized fun updateWarm(geometry:Set<Id>,images:Set<Id>){warm=geometry.toSet() to images.toSet();entries.entries.removeAll{!wanted(it.value)}}
    @Synchronized fun offer(tile:Tile):Boolean {
        if(!wanted(tile))return false
        val key=tile.id to tile.kind;val old=entries[key]
        if(old!=null&&old.heights===tile.heights&&old.rgba===tile.rgba)return false
        if(old==null&&entries.size>=capacity&&ranks!=null){
            val worst=entries.entries.maxByOrNull{rank(it.value)}?:return false
            if(rank(tile)>=rank(worst.value))return false
            entries.remove(worst.key)
        }
        if(old==null&&entries.size>=capacity&&!foreground(tile)&&entries.values.all{foreground(it)})return false
        entries.remove(key);entries[key]=tile
        while(entries.size>capacity){val victim=entries.entries.firstOrNull{!foreground(it.value)}?.key?:entries.keys.first();entries.remove(victim)}
        return true
    }
    @Synchronized fun take(count:Int,byteBudget:Int=Int.MAX_VALUE):List<Tile>{
        val out=ArrayList<Tile>(count);var bytes=0
        while(out.size<count){val row=peek()?:break;if(bytes+row.bytes>byteBudget)break;out.add(row);bytes+=row.bytes;entries.remove(row.id to row.kind)};return out
    }
    @Synchronized fun peek():Tile? {
        if(ranks!=null)return entries.values.minByOrNull{rank(it)}
        return entries.values.firstOrNull{tile->when(tile.kind){
        Kind.GEOMETRY->tile.id in parents.first
        Kind.IMAGE->tile.id in parents.second
        Kind.COMBINED->tile.id in parents.first||tile.id in parents.second
    }}?:entries.values.firstOrNull{foreground(it)}?:entries.values.firstOrNull()
    }
    @Synchronized fun isNotEmpty()=entries.isNotEmpty()
    @Synchronized fun clear()=entries.clear()
    companion object{val EMPTY:ByteBuffer=ByteBuffer.allocateDirect(0)}
}
