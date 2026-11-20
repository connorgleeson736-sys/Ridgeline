package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteOrder

/** A tile with no lines must be sent as an empty body, never as a reference
 * (0.1.27-0.1.32 sent both as length 0, so any empty tile in view made the
 * renderer reject every packet and no overlays were drawn). */
class LinePacketTest {
    private val clip=doubleArrayOf(.1,.1,.2,.2)
    private fun lengths(groups:List<LineGroup>,held:Set<Long>):List<Int>{
        val p=LineGroup.packet(groups,held).order(ByteOrder.LITTLE_ENDIAN);val n=p.getInt(0);var at=4;val out=ArrayList<Int>()
        repeat(n){val len=p.getInt(at+56);out.add(len);at+=60+if(len==LineGroup.REFERENCE)0 else len};assertEquals(p.capacity(),at);return out
    }
    @Test fun emptyTilesAreEmptyBodiesAndHeldTilesAreReferences(){
        val empty=LineGroup.pack(14,1,2,clip,emptyList())
        val line=LineGroup.pack(14,1,3,clip,listOf(TopographyStyle.Line(listOf(VectorTile.Point(.12,.12),VectorTile.Point(.18,.18)),0xff00ff00.toInt(),2f,0f,0f,0)))
        assertEquals(listOf(0,line.bytes.size-48),lengths(listOf(empty,line),emptySet()))
        assertEquals(listOf(LineGroup.REFERENCE,LineGroup.REFERENCE),lengths(listOf(empty,line),setOf(empty.id,line.id)))
    }
}
