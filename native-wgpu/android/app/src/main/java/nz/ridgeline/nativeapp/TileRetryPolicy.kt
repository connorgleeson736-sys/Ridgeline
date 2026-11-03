package nz.ridgeline.nativeapp

import java.io.IOException

/** A retained blurry parent is not a successful fine-tile load. Recover a still
 * wanted tile after transient outages without requiring another camera gesture.
 * Keep the established finite retries for permanent HTTP/decode failures. */
internal object TileRetryPolicy {
    fun delayMillis(attempt:Int):Long=when(attempt){1->1000L;2->4000L;else->15000L}
    fun shouldWake(attempt:Int,error:Exception):Boolean = attempt<=3||when(error){
        is TileHttpException->error.code==408||error.code==429||error.code in 500..599
        is IOException->true
        else->false
    }
}
