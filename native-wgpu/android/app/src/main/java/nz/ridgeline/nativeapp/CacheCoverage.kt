package nz.ridgeline.nativeapp

import kotlin.math.*

/** Advanced view: how detailed the imagery stored on this device is at each
 * place, from the automatic disk cache (tile URLs) and saved offline regions.
 * Colours run continuously from red (nothing or only coarse imagery stored)
 * through orange and yellow to green (detailed imagery stored). */
internal class CacheCoverage {
    private val tiles=Array(MAX_ZOOM+1){HashSet<Long>()}
    private val regions=ArrayList<Pair<DoubleArray,Int>>()
    var count=0;private set
    /** Record a cached imagery tile URL (LINZ aerial / NZ Topo or Esri imagery). */
    fun addUrl(url:String):Boolean{
        val (z,x,y)=parse(url)?:return false
        if(z !in 0..MAX_ZOOM)return false
        if(tiles[z].add((x.toLong() shl 32) or (y.toLong() and 0xffffffffL)))count++
        return true
    }
    fun addTile(z:Int,x:Int,y:Int){if(z in 0..MAX_ZOOM&&tiles[z].add((x.toLong() shl 32) or (y.toLong() and 0xffffffffL)))count++}
    /** A saved region: everything inside it is stored down to [zoom]. */
    fun addRegion(bounds:TileLoader.Bounds,zoom:Int){
        regions.add(doubleArrayOf((bounds.west+180)/360,CameraMath.mercatorY(bounds.north),(bounds.east+180)/360,CameraMath.mercatorY(bounds.south)) to zoom)
    }
    /** A saved painted area: each cell is stored down to its detail zoom. */
    private val painted=HashMap<Int,Int>()
    fun addCells(cells:Map<Int,Int>){for((k,d)in cells){val z=PaintedArea.DETAILS[d];if(z>(painted[k]?:-1))painted[k]=z}}
    /** Finest stored imagery zoom at a Mercator point, or -1 if none. */
    fun level(mx:Double,my:Double):Int{
        var best=-1
        if(painted.isNotEmpty()){val n=PaintedArea.GRID.toDouble();val x=floor(mx*n).toInt();val y=floor(my*n).toInt()
            if(x in 0 until PaintedArea.GRID&&y in 0 until PaintedArea.GRID)painted[PaintedArea.key(x,y)]?.let{best=it}}
        for((b,z)in regions)if(mx>=b[0]&&mx<=b[2]&&my>=b[1]&&my<=b[3])best=max(best,z)
        for(z in MAX_ZOOM downTo best+1){val n=(1 shl z).toDouble()
            val key=(floor(mx*n).toLong() shl 32) or (floor(my*n).toLong() and 0xffffffffL)
            if(key in tiles[z])return z}
        return best
    }
    /** SIZE x SIZE straight-alpha RGBA over the square Mercator area centred on
     * (cx, cy) with half-width [half]; row 0 is the north edge. */
    fun grid(cx:Double,cy:Double,half:Double):ByteArray{
        val out=ByteArray(SIZE*SIZE*4)
        for(row in 0 until SIZE){val my=cy-half+(row+.5)/SIZE*2*half
            for(col in 0 until SIZE){val mx=cx-half+(col+.5)/SIZE*2*half
                val c=color(level(mx,my));val i=(row*SIZE+col)*4
                out[i]=(c shr 16 and 255).toByte();out[i+1]=(c shr 8 and 255).toByte();out[i+2]=(c and 255).toByte();out[i+3]=(c ushr 24).toByte()}}
        return out
    }
    companion object{
        const val SIZE=256
        const val MAX_ZOOM=19
        /** Zooms at or below this count as "not detailed" (red); [DETAILED]
         * and finer are green; a continuous hue spectrum in between. */
        const val COARSE=10
        const val DETAILED=17
        private val linz=Regex("/v1/tiles/(?:aerial|topo-raster)/WebMercatorQuad/(\\d+)/(\\d+)/(\\d+)\\.")
        private val esri=Regex("/World_Imagery/MapServer/tile/(\\d+)/(\\d+)/(\\d+)")
        fun parse(url:String):Triple<Int,Int,Int>?{
            linz.find(url)?.let{val g=it.groupValues;return Triple(g[1].toInt(),g[2].toInt(),g[3].toInt())}
            esri.find(url)?.let{val g=it.groupValues;return Triple(g[1].toInt(),g[3].toInt(),g[2].toInt())}
            return null
        }
        /** ARGB for a stored zoom: hue 0 (red) at COARSE or nothing, 120 (green) at DETAILED. */
        fun color(level:Int):Int{
            val t=if(level<0)0.0 else ((level-COARSE).toDouble()/(DETAILED-COARSE)).coerceIn(0.0,1.0)
            val hue=120*t;val s=.85;val v=.92
            val c=v*s;val x=c*(1-abs((hue/60)%2-1));val m=v-c
            val (r,g,b)=if(hue<60)Triple(c,x,0.0)else Triple(x,c,0.0)
            val alpha=if(level<0).55 else .5
            return ((alpha*255).roundToInt() shl 24) or (((r+m)*255).roundToInt() shl 16) or (((g+m)*255).roundToInt() shl 8) or ((b+m)*255).roundToInt()
        }
    }
}
