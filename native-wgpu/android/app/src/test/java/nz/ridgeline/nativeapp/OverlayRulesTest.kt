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
    private fun river(name:String,id:Long)=VectorTile.Feature("water_lines",id,2,mapOf("kind" to "river","name" to name),listOf(listOf(VectorTile.Point(.1,.1+id*.01),VectorTile.Point(.4,.1+id*.01))))
    private fun track(name:String,id:Long)=VectorTile.Feature("streets",id,2,mapOf("kind" to "track","track_use" to "foot","name" to name),listOf(listOf(VectorTile.Point(.2,.5+id*.01),VectorTile.Point(.6,.5+id*.01))))
    @Test fun riverAndTrackNamesComeLaterAndOncePerTile(){
        val s=MapSettings.State()
        val rivers=listOf(river("Hooker River",1),river("Hooker River",2),river("Tasman River",3))
        assertTrue("rivers wait for 15.5",names(style.build(rivers,s,15.0,14.0)).isEmpty())
        val shown=style.build(rivers,s,15.6,14.0).labels.map{it.text}
        assertEquals(listOf("Hooker River","Tasman River"),shown.sorted())
        val tracks=listOf(track("Hooker Valley Track",1),track("Local Spur Track",2))
        assertTrue("no track names before 13.5",names(style.build(tracks,s,13.2,13.0)).isEmpty())
        assertEquals(setOf("Hooker Valley Track"),names(style.build(tracks,s,14.0,14.0)))
        assertEquals(setOf("Hooker Valley Track","Local Spur Track"),names(style.build(tracks,s,15.6,14.0)))
    }
}
