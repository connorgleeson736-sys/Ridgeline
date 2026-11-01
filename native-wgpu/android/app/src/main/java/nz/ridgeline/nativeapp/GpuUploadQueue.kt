package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
/** Separate immutable sources coalesce independently, with a hard per-frame byte budget. */
class GpuUploadQueue(private val capacity:Int) {
    enum class Kind{COMBINED,GEOMETRY,IMAGE}
    data class Id(val z:Int,val x:Int,val y:Int){val packed get()=(z.toLong() shl 44)or(x.toLong() shl 22)or y.toLong()}
    data class Tile(val id:Id,val heights:ByteBuffer,val rgba:ByteBuffer,val kind:Kind=Kind.COMBINED){val bytes get()=heights.capacity()+rgba.capacity()}
    private val entries=LinkedHashMap<Pair<Id,Kind>,Tile>()
    private data class Retry(val at:Long,val attempts:Int)
    private val retries=HashMap<Pair<Id,Kind>,Retry>()
    private var accepted=0L;private var deferred=0L;private var invalid=0L
    private fun pruneRetries(){retries.keys.retainAll(entries.keys)}
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
        retries.clear() // Changed demand may release native residency capacity.
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
    @Synchronized fun updateWarm(geometry:Set<Id>,images:Set<Id>){warm=geometry.toSet() to images.toSet();entries.entries.removeAll{!wanted(it.value)};pruneRetries()}
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
        retries.remove(key)
        while(entries.size>capacity){val victim=entries.entries.firstOrNull{!foreground(it.value)}?.key?:entries.keys.first();entries.remove(victim)}
        pruneRetries()
        return true
    }
    /** A prepared payload stays owned until JNI acknowledges admission. A late
     * acknowledgement cannot remove a newer payload for the same source. */
    @Synchronized fun acknowledge(tile:Tile,result:Int,now:Long=System.nanoTime()){
        val key=tile.id to tile.kind
        if(entries[key]!==tile)return
        when(result){
            ACCEPTED,INVALID->{entries.remove(key);retries.remove(key);if(result==ACCEPTED)accepted++ else invalid++}
            else->{val attempt=(retries[key]?.attempts?:0)+1
                val delay=if(result==DEVICE_UNAVAILABLE)250L else minOf(512L,16L shl minOf(attempt-1,5))
                retries[key]=Retry(now+delay*1_000_000,attempt);deferred++}
        }
    }
    @Synchronized fun nextDelayMillis(now:Long=System.nanoTime()):Long? {
        if(entries.isEmpty())return null
        val at=entries.keys.minOf{retries[it]?.at?:now}
        return if(at<=now)0 else (at-now+999_999)/1_000_000
    }
    @Synchronized fun wakeRetries(){retries.clear()}
    @Synchronized fun outcomeStats()="accepted $accepted · capacity/device retries $deferred · invalid $invalid"
    @Synchronized fun take(count:Int,byteBudget:Int=Int.MAX_VALUE):List<Tile>{
        val out=ArrayList<Tile>(count);var bytes=0
        while(out.size<count){val row=peek()?:break;if(bytes+row.bytes>byteBudget)break;out.add(row);bytes+=row.bytes;val key=row.id to row.kind;entries.remove(key);retries.remove(key)};return out
    }
    @Synchronized fun peek(now:Long=System.nanoTime()):Tile? {
        var best:Tile?=null;var bestRank=Int.MAX_VALUE
        for((key,tile)in entries){
            if((retries[key]?.at?:now)>now)continue
            val parent=when(tile.kind){Kind.GEOMETRY->tile.id in parents.first;Kind.IMAGE->tile.id in parents.second;Kind.COMBINED->tile.id in parents.first||tile.id in parents.second}
            val score=if(ranks!=null)rank(tile)else if(parent)0 else if(foreground(tile))1 else 2
            if(best==null||score<bestRank){best=tile;bestRank=score}
        }
        return best
    }
    @Synchronized fun isNotEmpty()=entries.isNotEmpty()
    @Synchronized fun size()=entries.size
    @Synchronized fun clear(){entries.clear();retries.clear()}
    companion object{
        const val ACCEPTED=1;const val RETRY=0;const val INVALID=-1;const val DEVICE_UNAVAILABLE=-2
        val EMPTY:ByteBuffer=ByteBuffer.allocateDirect(0)
    }
}
