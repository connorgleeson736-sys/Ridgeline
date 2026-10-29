package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
/** Separate immutable sources coalesce independently, with a hard per-frame byte budget. */
class GpuUploadQueue(private val capacity:Int) {
    enum class Kind{COMBINED,GEOMETRY,IMAGE}
    data class Id(val z:Int,val x:Int,val y:Int){val packed get()=(z.toLong() shl 44)or(x.toLong() shl 22)or y.toLong()}
    data class Tile(val id:Id,val heights:ByteBuffer,val rgba:ByteBuffer,val kind:Kind=Kind.COMBINED){val bytes get()=heights.capacity()+rgba.capacity()}
    private val entries=LinkedHashMap<Pair<Id,Kind>,Tile>()
    @Synchronized fun offer(tile:Tile){val key=tile.id to tile.kind;entries.remove(key);entries[key]=tile;while(entries.size>capacity)entries.remove(entries.keys.first())}
    @Synchronized fun take(count:Int,byteBudget:Int=Int.MAX_VALUE):List<Tile>{
        val out=ArrayList<Tile>(count);val iter=entries.entries.iterator();var bytes=0
        while(out.size<count&&iter.hasNext()){val row=iter.next().value;if(bytes+row.bytes>byteBudget)break;out.add(row);bytes+=row.bytes;iter.remove()};return out
    }
    @Synchronized fun isNotEmpty()=entries.isNotEmpty()
    @Synchronized fun clear()=entries.clear()
    companion object{val EMPTY:ByteBuffer=ByteBuffer.allocateDirect(0)}
}
