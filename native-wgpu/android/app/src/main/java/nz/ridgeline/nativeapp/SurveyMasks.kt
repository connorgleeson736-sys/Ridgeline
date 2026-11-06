package nz.ridgeline.nativeapp

import java.io.IOException
import java.io.InterruptedIOException

/** Seam vetoes use only survey alpha. LINZ WebP alpha is lossless and was
 * identical to PNG on 1,064 live tiles across all 46 catalogue surveys
 * (zooms 13-19), with 89% fewer bytes. The original PNG remains the fallback,
 * including for regions saved before this change. Photos and elevation are
 * unchanged. Pending/cancelled work is never converted into a fallback. */
internal object SurveyMasks {
    /** [aerial] is the tile's LINZ aerial address (".webp?api=..."). */
    fun url(aerial:String,name:String,png:Boolean=false):String =
        aerial.replace("/aerial/","/$name/").let{if(png)it.replace(".webp?",".png?")else it}
    fun fetch(aerial:String,name:String,fetch:(String)->ByteArray,checkPreparation:()->Unit={}):ByteArray =
        try{fetch(url(aerial,name))}
        catch(e:InterruptedIOException){throw e}
        catch(e:IOException){checkPreparation();fetch(url(aerial,name,png=true))}
}
