package nz.ridgeline.nativeapp

import java.io.IOException
import java.io.InterruptedIOException

/** LINZ's aerial hillshading taken back out (NativeImagery.deshade). A block holds
 * keep*255 for 4x4 tiles (1024 px a side), keep=(1-a1)(1-a2) of LINZ's two shading
 * layers; a photo's own colour is its colour divided by keep. */
internal object ShadeCorrection {
    /** Divides [px] (one 256 px tile, ARGB) by the block's keep at tile offset
     * ([ox],[oy]) in place, rounding and capping at 255. False where nothing is shaded. */
    fun apply(px:IntArray,values:ByteArray,ox:Int,oy:Int):Boolean{
        require(px.size==65536&&values.size==SHADE_BLOCK*SHADE_BLOCK&&ox in 0..SHADE_BLOCK-256&&oy in 0..SHADE_BLOCK-256)
        var changed=false
        for(r in 0 until 256){val base=(oy+r)*SHADE_BLOCK+ox
            for(c in 0 until 256){val v=values[base+c].toInt() and 0xff;if(v==255)continue
                val k=maxOf(v,1);val h=k shr 1;val i=r*256+c;val p=px[i]
                val red=minOf(255,(((p shr 16) and 0xff)*255+h)/k);val green=minOf(255,(((p shr 8) and 0xff)*255+h)/k);val blue=minOf(255,((p and 0xff)*255+h)/k)
                px[i]=(p and -0x1000000) or (red shl 16) or (green shl 8) or blue;changed=true}}
        return changed
    }
    /** The correction, or null when it cannot be had (offline, not on the host): the
     * photo is then shown as LINZ shades it. Pending, cancelled or aborted work is
     * never turned into that fallback. */
    fun <T> orNone(read:()->T?,checkPreparation:()->Unit={}):T? =
        try{read()}
        catch(e:InterruptedIOException){throw e}
        catch(_:IOException){checkPreparation();null}
}
