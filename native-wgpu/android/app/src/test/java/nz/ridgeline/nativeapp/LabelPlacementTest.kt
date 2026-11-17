package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/** Collision placement: importance first, hidden names take no space. */
class LabelPlacementTest {
    private fun label(key:String,priority:Double,circle:Boolean=false,text:String=key,spacing:Float=0f)=
        NativeLabelAtlas.Row(TopographyStyle.Label(VectorTile.Point(0.0,0.0),if(circle)"" else text,12f,0,priority,circle=circle,key=key,spacing=spacing),80f,16f)
    private fun atlas(vararg rows:NativeLabelAtlas.Row)=NativeLabelAtlas(rows.toList(),1,1,ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(4),ByteBuffer.allocateDirect(0))
    private fun placed(a:NativeLabelAtlas,positions:FloatArray):Set<String>{
        val out=NativeLabels.place(a,positions,1000,1000,1f);val n=out.int
        return (0 until n).map{val i=out.int;out.float;out.float;out.float;a.labels[i].label.key}.toSet()
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

    // ---- Names along curves (the Te Araroa name) ----
    private fun curve(text:String,vertices:Int,size:Float=16f,glyphWidth:Float=20f):List<NativeLabelAtlas.Row>{
        val p=VectorTile.Point(.5,.5)
        val v=(0 until vertices).map{NativeLabelAtlas.Row(TopographyStyle.Label(p,"",1f,0,2.0,key="name:v$it",path="name",vertex=it),9f,9f)}
        val g=text.mapIndexed{k,ch->NativeLabelAtlas.Row(TopographyStyle.Label(p,ch.toString(),size,0,2.0,key="name:g$k",path="name",glyph=k),glyphWidth+8f,size*1.6f)}
        return v+g
    }
    private class Entry(val index:Int,val x:Float,val y:Float,val angle:Float)
    private fun entries(a:NativeLabelAtlas,positions:FloatArray,w:Int=1000,h:Int=1000):List<Entry>{
        val out=NativeLabels.place(a,positions,w,h,1f);val n=out.int
        return List(n){Entry(out.int,out.float,out.float,out.float)}
    }
    /** Vertex positions (x, y, visible) then the letters' shared anchor at (500, 500). */
    private fun positions(points:List<Pair<Float,Float>>,letters:Int)=(points.flatMap{listOf(it.first,it.second,1f)}+List(letters){listOf(500f,500f,1f)}.flatten()).toFloatArray()
    @Test fun lettersFollowAStraightLineInReadingOrderAboveIt(){
        val a=atlas(*curve("Te Araroa",5).toTypedArray())
        val e=entries(a,positions((0..4).map{100f+it*200f to 600f},9))
        assertEquals(9,e.size)
        // Left to right, level, centred on the line's middle, lifted above it.
        val centres=e.map{val row=a.labels[it.index];500f+it.x+row.width/2 to 500f+it.y+row.height/2}
        assertTrue(centres.zipWithNext().all{(p,q)->q.first>p.first})
        assertTrue(e.all{abs(it.angle)<1e-3})
        assertEquals(500f,(centres.first().first+centres.last().first)/2,2f)
        assertTrue(centres.all{it.second<600f})
    }
    @Test fun aCurveDrawnLeftwardsIsReadFromItsOtherEnd(){
        val a=atlas(*curve("Te Araroa",5).toTypedArray())
        val e=entries(a,positions((0..4).map{900f-it*200f to 600f},9))
        val xs=e.map{500f+it.x+a.labels[it.index].width/2}
        assertTrue(xs.zipWithNext().all{(p,q)->q>p})
        assertTrue(e.all{abs(it.angle)<1e-3})
    }
    @Test fun lettersTurnWithTheCurve(){
        // A gentle arc: letters at either end lean opposite ways.
        val pts=(0..20).map{val t=it/20f;100f+800f*t to 600f-200f*kotlin.math.sin(t*Math.PI.toFloat())}
        val a=atlas(*curve("Te Araroa",21).toTypedArray())
        val e=entries(a,positions(pts,9))
        assertEquals(9,e.size)
        assertTrue(e.first().angle<-.05f);assertTrue(e.last().angle>.05f)
    }
    @Test fun aNameIsLeftOutWhereItDoesNotFitOrBendsTooSharply(){
        val a=atlas(*curve("Te Araroa",3).toTypedArray())
        // Too short for the name.
        assertTrue(entries(a,positions(listOf(450f to 600f,500f to 600f,550f to 600f),9)).isEmpty())
        // A right angle under the name.
        val b=atlas(*curve("Te Araroa",3).toTypedArray())
        assertTrue(entries(b,positions(listOf(300f to 600f,500f to 600f,500f to 400f),9)).isEmpty())
    }
    @Test fun aCurvedNameIsLaidOutAgainFromItsWholeCurveKeepingOtherNames(){
        val rows=curve("Te Araroa",5)+listOf(label("town",10.0))
        val a=atlas(*rows.toTypedArray())
        val set=NativeLabels.curves(a)!!
        assertEquals(1,set.curves.size);assertEquals(5,set.curves[0].first.size);assertEquals(9,set.curves[0].second.size)
        // The background placement: the town, and the name's letters from an older camera.
        val town=rows.size-1
        val base=java.nio.ByteBuffer.allocateDirect(4+10*NativeLabels.ENTRY).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply{putInt(10)
            putInt(town).putFloat(-40f).putFloat(-8f).putFloat(0f);for(k in 0 until 9)putInt(5+k).putFloat(999f).putFloat(999f).putFloat(0f);rewind()}
        // The curve now: a level line from x 100 to 900 at y 600 (every point projected).
        val out=NativeLabels.withCurves(a,set,base,{_,o->for(k in 0..4)o.putFloat(100f+k*200f).putFloat(600f).putFloat(1f);5},1000,1000,1f)
        val n=out.int;val e=List(n){Entry(out.int,out.float,out.float,out.float)}
        assertEquals(10,e.size);assertEquals(1,e.count{it.index==town})
        // The letters: none left from the old camera, centred on the curve's middle (its anchor, x 500).
        val letters=e.filter{it.index!=town};assertEquals(9,letters.size);assertTrue(letters.none{it.x==999f})
        val centres=letters.map{it.x+a.labels[it.index].width/2}
        assertEquals(0f,(centres.first()+centres.last())/2,2f)
    }
    @Test fun otherNamesGiveWayToACurvedName(){
        val rows=curve("Te Araroa",5)+listOf(label("town",10.0))
        val a=atlas(*rows.toTypedArray())
        // The town's name sits where the curved name is.
        val pos=positions((0..4).map{100f+it*200f to 600f},9)+floatArrayOf(500f,585f,1f)
        val keys=entries(a,pos).map{a.labels[it.index].label.key}
        assertFalse("town" in keys);assertEquals(9,keys.size)
    }
}
