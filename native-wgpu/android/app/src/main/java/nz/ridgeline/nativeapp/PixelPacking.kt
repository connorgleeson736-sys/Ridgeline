package nz.ridgeline.nativeapp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Consumes an owned getPixels() array. Source bitmaps/encoded caches are never
 * mutated. Same integer premultiplication as before, one bulk direct-buffer copy. */
internal object PixelPacking {
    fun rgbaOwned(pixels:IntArray,checkpoint:()->Unit={}):ByteBuffer {
        for(index in pixels.indices){
            if(index%4096==0)checkpoint()
            val p=pixels[index];val a=p ushr 24
            pixels[index]=if(a==255){(p and 0xff00ff00.toInt())or((p ushr 16)and 255)or((p and 255)shl 16)}else{
                val r=(((p ushr 16)and 255)*a+127)/255
                val g=(((p ushr 8)and 255)*a+127)/255
                val b=((p and 255)*a+127)/255
                r or(g shl 8)or(b shl 16)or(a shl 24)
            }
        }
        checkpoint()
        return ByteBuffer.allocateDirect(pixels.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{asIntBuffer().put(pixels)}
    }
}
