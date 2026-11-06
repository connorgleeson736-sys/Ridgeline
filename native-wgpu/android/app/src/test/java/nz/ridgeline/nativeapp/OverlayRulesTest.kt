package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Capacitor/MapLibre overlay rules: layer ranges use the camera zoom, while
 * ["zoom"] in filters uses each source tile's own zoom. */
class OverlayRulesTest {
    private val fixtures get()=File(requireNotNull(System.getProperty("ridgeline.fixtures")))
    private val style get()=TopographyStyle(File(fixtures,"../../main/assets/topography-style.json").readText())
    private fun peak(name:String,rank:Int)=VectorTile.Feature("place_labels",name.hashCode().toLong(),1,
        mapOf("natural" to "peak","label" to name,"admin_level" to rank),listOf(listOf(VectorTile.Point(.5,.5))))
    private fun names(out:TopographyStyle.Output)=out.labels.map{it.text}.toSet()

    @Test fun minorPeaksFollowTheTileZoomLikeMapLibre(){
        val features=listOf(peak("Major",3),peak("Mid",8),peak("Minor",10))
        val s=MapSettings.State()
        // Camera z15 over a z15-equivalent near tile: every tier is shown.
        assertEquals(setOf("Major","Mid","Minor"),names(style.build(features,s,15.0,15.0)))
        // Same camera, distant z12 tile in a pitched view: minor peaks wait for
        // tile zoom 14 (as in Capacitor); rank<=8 secondary peaks show from 12.
        assertEquals(setOf("Major","Mid"),names(style.build(features,s,15.0,12.0)))
        // Below the secondary layer's minzoom (camera 11.5) only major peaks remain.
        assertEquals(setOf("Major"),names(style.build(features,s,11.5,11.0)))
    }
    @Test fun peaksFollowThePeakLabelsSwitchAndTopoHidesOverlays(){
        val features=listOf(peak("Major",3))
        assertTrue(names(style.build(features,MapSettings.State(peaks=false),15.0,15.0)).isEmpty())
        assertFalse(MapSettings.State(base=MapSettings.Base.TOPO).overlays())
    }
}
