package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Collision placement: importance first, hidden names take no space. */
class LabelPlacementTest {
    private fun label(key:String,priority:Double,circle:Boolean=false,text:String=key,spacing:Float=0f)=
        NativeLabelAtlas.Row(TopographyStyle.Label(VectorTile.Point(0.0,0.0),if(circle)"" else text,12f,0,priority,circle=circle,key=key,spacing=spacing),80f,16f)
    private fun atlas(vararg rows:NativeLabelAtlas.Row)=NativeLabelAtlas(rows.toList(),1,1,ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(0))
    private fun placed(a:NativeLabelAtlas,positions:FloatArray):Set<String>{
        val out=NativeLabels.place(a,positions,1000,1000,1f);val n=out.int
        return (0 until n).map{val i=out.int;out.float;out.float;a.labels[i].label.key}.toSet()
    }
    @Test fun aHutNameBeatsARiverNameShownBeforeIt(){
        val a=atlas(label("hut",10.0),label("river",65.0))
        // The river name is on screen first (the hut was not yet projected).
        assertEquals(setOf("river"),placed(a,floatArrayOf(500f,500f,0f, 505f,502f,1f)))
        // Both overlap now: the more important hut name wins, as in MapLibre.
        assertEquals(setOf("hut"),placed(a,floatArrayOf(500f,500f,1f, 505f,502f,1f)))
    }
    @Test fun equallyImportantNamesKeepTheirPlaces(){
        val a=atlas(label("a",60.0),label("b",60.0))
        assertEquals(setOf("b"),placed(a,floatArrayOf(500f,500f,0f, 505f,502f,1f)))
        assertEquals(setOf("b"),placed(a,floatArrayOf(500f,500f,1f, 505f,502f,1f)))
    }
    @Test fun aNameHiddenByTerrainTakesNoSpace(){
        val a=atlas(label("peak behind ridge",1.0),label("hut",10.0),label("hut-dot",100.0,circle=true))
        // The peak is hidden (unprojected): the hut name and its dot are placed.
        assertEquals(setOf("hut","hut-dot"),placed(a,floatArrayOf(500f,500f,0f, 505f,502f,1f, 505f,502f,1f)))
        // Visible, the more important peak name takes the space; the dot stays.
        assertEquals(setOf("peak behind ridge","hut-dot"),placed(a,floatArrayOf(500f,500f,1f, 505f,502f,1f, 505f,502f,1f)))
    }
    @Test fun aRoadNameIsNotRepeatedCloserThanItsSpacing(){
        val a=atlas(label("sh1",35.0,text="SH 80",spacing=850f),label("sh2",35.0,text="SH 80",spacing=850f),
            label("sh3",35.0,text="SH 80",spacing=850f),label("river",65.0,text="Tasman River",spacing=900f))
        // Three pieces of the same highway 300 px apart and one 900 px away; a river nearby.
        val placed=placed(a,floatArrayOf(100f,100f,1f, 100f,400f,1f, 100f,950f,1f, 300f,250f,1f))
        assertEquals(setOf("sh1","sh3","river"),placed)
    }
}
