package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Partial label-atlas updates convert exactly as the whole-atlas path. */
class PixelPackingTest {
    @Test fun rowsWrittenInPlaceMatchAWholeConversion(){
        val width=8;val rows=6
        val source=IntArray(width*rows){i->if(i%3==0)0x80ff4020.toInt() else 0xff000000.toInt() or (i*0x010203)}
        val whole=PixelPacking.rgbaOwned(source.copyOf())
        val partial=ByteBuffer.allocateDirect(width*rows*4)
        // Rows 0-1 first, then rows 2-5 later (as newly drawn names).
        PixelPacking.rgbaInto(source.copyOfRange(0,2*width),partial,0)
        PixelPacking.rgbaInto(source.copyOfRange(2*width,rows*width),partial,2*width*4)
        for(i in 0 until width*rows*4)assertEquals("byte $i",whole.get(i),partial.get(i))
        assertEquals(0,partial.position())
    }
}
