package nz.ridgeline.nativeapp

import java.nio.ByteBuffer

/** Coalesced by tile identity; a bounded queue owns direct buffers until upload. */
class GpuUploadQueue(private val capacity: Int) {
    data class Id(val z: Int,val x: Int,val y: Int)
    data class Tile(val id: Id,val heights: ByteBuffer,val rgba: ByteBuffer)
    private val entries = LinkedHashMap<Id,Tile>()
    @Synchronized fun offer(tile: Tile) {
        entries.remove(tile.id); entries[tile.id] = tile
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun take(count: Int): List<Tile> {
        val out = ArrayList<Tile>(count)
        val iter = entries.entries.iterator()
        while (out.size < count && iter.hasNext()) { out.add(iter.next().value); iter.remove() }
        return out
    }
    @Synchronized fun isNotEmpty() = entries.isNotEmpty()
    @Synchronized fun clear() = entries.clear()
}
