package nz.ridgeline.nativeapp

import kotlin.math.pow
import kotlin.math.round

/** Same seven 0–1.5 zoom-bias choices as the Capacitor detail controls. */
internal object DetailPolicy {
    const val DEFAULT=1 // 0.25 levels, the existing native targets.
    const val MAX=6
    fun target(geometry:Boolean,step:Int):Double {
        val bias=step.coerceIn(0,MAX)*.25
        return if(geometry)round(512.0/2.0.pow(bias)) else 256.0/2.0.pow(bias)
    }
    fun multiplier(step:Int)=2.0.pow(step.coerceIn(0,MAX)*.25)
}
