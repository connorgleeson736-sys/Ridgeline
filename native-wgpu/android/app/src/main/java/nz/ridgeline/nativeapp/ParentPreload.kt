package nz.ridgeline.nativeapp

import kotlin.math.*

/** Coarser parents of the visible tiles, 2 and 3 levels up, with one ring of
 * neighbours (a few screens wide), nearest to the camera first. Bounded: at
 * most [perKind] tiles each for terrain and imagery per idle batch. */
internal object ParentPreload {
    data class Tile(val z:Int,val x:Int,val y:Int,val geometry:Boolean)
    fun plan(visible:Collection<Tile>,pose:CameraPose,perKind:Int=12):List<Tile>{
        val mx=(pose.longitude+180)/360;val my=CameraMath.mercatorY(pose.latitude)
        fun distance(t:Tile):Double{val n=(1 shl t.z).toDouble();val dx=(t.x+.5)/n-mx;val dy=(t.y+.5)/n-my;return dx*dx+dy*dy}
        val result=ArrayList<Tile>()
        for(geometry in listOf(true,false)){
            val tiles=visible.filter{it.geometry==geometry};if(tiles.isEmpty())continue
            // The finest visible level sets the scale; coarser fallback rows are ignored.
            val z=tiles.maxOf{it.z}
            // Two thirds of the batch two levels up (sharper stand-ins), the rest
            // three levels up (wider reach), nearest to the camera first.
            for((up,quota) in listOf(2 to perKind*2/3,3 to perKind-perKind*2/3)){val level=z-up;if(level<1)continue;val n=1 shl level
                val candidates=LinkedHashSet<Tile>()
                val parents=tiles.filter{it.z>=level}.map{Tile(level,it.x shr(it.z-level),it.y shr(it.z-level),geometry)}.toSet()
                for(p in parents)for(dy in -1..1)for(dx in -1..1){val y=p.y+dy;if(y in 0 until n)candidates.add(Tile(level,Math.floorMod(p.x+dx,n),y,geometry))}
                result.addAll(candidates.sortedBy(::distance).take(quota))
            }
        }
        return result
    }
}
