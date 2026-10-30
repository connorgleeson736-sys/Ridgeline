package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
/** Separate immutable sources coalesce independently, with a hard per-frame byte budget. */
class GpuUploadQueue(private val capacity:Int) {
    enum class Kind{COMBINED,GEOMETRY,IMAGE}
    data class Id(val z:Int,val x:Int,val y:Int){val packed get()=(z.toLong() shl 44)or(x.toLong() shl 22)or y.toLong()}
    data class Tile(val id:Id,val heights:ByteBuffer,val rgba:ByteBuffer,val kind:Kind=Kind.COMBINED){val bytes get()=heights.capacity()+rgba.capacity()}
    private val entries=LinkedHashMap<Pair<Id,Kind>,Tile>()
    private var demand:Pair<Set<Id>,Set<Id>>?=null
    private fun wanted(tile:Tile):Boolean {
        val current=demand?:return true // Controlled fixtures need no viewport demand.
        return when(tile.kind){
            Kind.GEOMETRY->tile.id in current.first
            Kind.IMAGE->tile.id in current.second
            Kind.COMBINED->tile.id in current.first||tile.id in current.second
        }
    }
    /** Prune queued ownership only; resident fallback coverage remains intact. */
    @Synchronized fun updateDemand(geometry:Set<Id>,images:Set<Id>){
        demand=geometry.toSet() to images.toSet()
        entries.entries.removeAll{!wanted(it.value)}
    }
    @Synchronized fun offer(tile:Tile):Boolean {
        if(!wanted(tile))return false
        val key=tile.id to tile.kind;val old=entries[key]
        if(old!=null&&old.heights===tile.heights&&old.rgba===tile.rgba)return false
        entries.remove(key);entries[key]=tile
        while(entries.size>capacity)entries.remove(entries.keys.first())
        return true
    }
    @Synchronized fun take(count:Int,byteBudget:Int=Int.MAX_VALUE):List<Tile>{
        val out=ArrayList<Tile>(count);val iter=entries.entries.iterator();var bytes=0
        while(out.size<count&&iter.hasNext()){val row=iter.next().value;if(bytes+row.bytes>byteBudget)break;out.add(row);bytes+=row.bytes;iter.remove()};return out
    }
    @Synchronized fun peek():Tile?=entries.values.firstOrNull()
    @Synchronized fun isNotEmpty()=entries.isNotEmpty()
    @Synchronized fun clear()=entries.clear()
    companion object{val EMPTY:ByteBuffer=ByteBuffer.allocateDirect(0)}
}
