package nz.ridgeline.nativeapp

import kotlin.math.log2
import kotlin.math.roundToInt

/** NZ Topo is LINZ's two printed maps: Topo250 (1:250,000) and Topo50
 * (1:50,000). LINZ's combined topo-raster switches at its zoom-13 tiles; each map
 * is also served on its own at every zoom, so the map zoom where Topo50 takes
 * over can be chosen ([MapSettings.State.topoSwitch]). Pure Kotlin, unit tested. */
internal object TopoLayers {
    const val TOPO50="nz-topo50-new-zealand-mainland-gridless"
    const val TOPO250="nz-topo250-new-zealand-mainland-gridless"
    /** LINZ's combined map: what regions saved before the choice hold. */
    const val COMBINED="topo-raster"
    /** LINZ's own switch: Topo50 from its zoom-13 tiles. */
    const val LINZ_FIRST_TOPO50=13
    const val MIN_SWITCH=8f
    const val MAX_SWITCH=15f
    /** The tile zoom the app asks for at map zoom [zoom]: one map pixel per screen
     * pixel (256-pixel tiles at [density]), as TileLoader plans NZ Topo. */
    private fun tileZoom(zoom:Double,density:Double)=zoom+1+log2(density.coerceAtLeast(1.0))
    /** The map zoom where LINZ's own switch falls on this screen (one decimal). */
    fun defaultSwitch(density:Double)=((LINZ_FIRST_TOPO50-.5-1-log2(density.coerceAtLeast(1.0)))*10).roundToInt()/10f
    /** First tile zoom drawn from Topo50 for a chosen switch (NaN: LINZ's own). */
    fun firstTopo50(switch:Float,density:Double):Int=
        if(!switch.isFinite())LINZ_FIRST_TOPO50 else Math.round(tileZoom(switch.toDouble(),density)).toInt()
    /** The LINZ tile set for a Topo tile at zoom [z]. */
    fun layer(z:Int,switch:Float,density:Double)=if(z>=firstTopo50(switch,density))TOPO50 else TOPO250
}
