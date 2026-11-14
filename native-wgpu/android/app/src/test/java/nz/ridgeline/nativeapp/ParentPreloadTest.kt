package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test

class ParentPreloadTest {
    private val pose=CameraPose(170.14,-43.59,14.5,0f,0f,1000f)
    private fun visible(z:Int,geometry:Boolean):List<ParentPreload.Tile>{
        val n=1 shl z;val x=((pose.longitude+180)/360*n).toInt();val y=(CameraMath.mercatorY(pose.latitude)*n).toInt()
        return buildList{for(dy in -2..2)for(dx in -3..3)add(ParentPreload.Tile(z,x+dx,y+dy,geometry))}
    }
    @Test fun parentsTwoAndThreeLevelsUpAroundTheView(){
        val plan=ParentPreload.plan(visible(16,true)+visible(17,false),pose)
        val ground=plan.filter{it.geometry};val photos=plan.filter{!it.geometry}
        assertEquals(12,ground.size);assertEquals(12,photos.size)
        assertEquals(setOf(14,13),ground.map{it.z}.toSet());assertEquals(setOf(15,14),photos.map{it.z}.toSet())
        assertEquals(8,ground.count{it.z==14});assertEquals(4,ground.count{it.z==13})
        // Every visible tile's own z-2 parent is included (no gap under the view).
        for(t in visible(16,true))assertTrue(ParentPreload.Tile(14,t.x shr 2,t.y shr 2,true) in ground)
        assertEquals(plan.size,plan.toSet().size)
    }
    @Test fun nothingWithoutVisibleTiles(){assertTrue(ParentPreload.plan(emptyList(),pose).isEmpty())}
}
