package nz.ridgeline.nativeapp

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*

/** Areas painted for offline download: a grid of zoom-[CELL_ZOOM] cells
 * (about 440 m square in New Zealand), each with one of [DETAILS]. Painting
 * overwrites, erasing removes; each stroke can be undone. Pure Kotlin: no
 * Android types, so it is unit tested. */
internal class PaintedArea {
    /** Cell key (y * 2^16 + x at zoom 16) to detail index in [DETAILS].
     * Concurrent: the overlay and estimate read it off the UI thread. */
    private val cells=java.util.concurrent.ConcurrentHashMap<Int,Int>()
    /** Each stroke's previous values (-1: unpainted), newest last. */
    private val strokes=ArrayDeque<HashMap<Int,Int>>()
    private var open:HashMap<Int,Int>?=null
    var revision=0L;private set
    val size get()=cells.size
    fun isEmpty()=cells.isEmpty()
    fun canUndo()=strokes.isNotEmpty()||open?.isNotEmpty()==true
    fun detailAt(key:Int):Int=cells[key]?:-1
    fun snapshot():Map<Int,Int> = HashMap(cells)

    fun begin(){end();open=HashMap()}
    /** Ends the current stroke (kept for undo if it changed anything). */
    fun end(){val s=open?:return;open=null;if(s.isNotEmpty()){strokes.addLast(s);while(strokes.size>MAX_UNDO)strokes.removeFirst()}}
    /** Reverts the current stroke (a second finger turned it into a map gesture). */
    fun cancel(){val s=open?:return;open=null;restore(s)}
    fun undo():Boolean{
        open?.let{if(it.isNotEmpty()){open=null;restore(it);return true}}
        val s=strokes.removeLastOrNull()?:return false;restore(s);return true
    }
    private fun restore(previous:Map<Int,Int>){for((k,v)in previous)if(v<0)cells.remove(k)else cells[k]=v;revision++}
    private fun set(key:Int,value:Int){
        val before=cells[key]?:-1;if(before==value)return
        val stroke=open?:HashMap<Int,Int>().also{open=it}
        if(key !in stroke)stroke[key]=before
        if(value<0)cells.remove(key)else cells[key]=value
        revision++
    }
    /** Paints (or with [detail] -1 erases) a disc at Mercator ([mx], [my]) with
     * radius [r] in Mercator units. Cells whose centre is inside, plus the
     * cell under the centre so a small brush always paints something. */
    fun dab(mx:Double,my:Double,r:Double,detail:Int){
        if(!mx.isFinite()||!my.isFinite())return
        val n=GRID.toDouble();val rc=max(r,0.0)*n
        val cx=mx*n;val cy=my*n
        val x0=floor(cx-rc).toInt().coerceIn(0,GRID-1);val x1=floor(cx+rc).toInt().coerceIn(0,GRID-1)
        val y0=floor(cy-rc).toInt().coerceIn(0,GRID-1);val y1=floor(cy+rc).toInt().coerceIn(0,GRID-1)
        if((x1-x0+1).toLong()*(y1-y0+1)>MAX_DAB_CELLS)return
        for(y in y0..y1)for(x in x0..x1){val dx=x+.5-cx;val dy=y+.5-cy;if(dx*dx+dy*dy<=rc*rc)paint(key(x,y),detail)}
        paint(key(floor(cx).toInt().coerceIn(0,GRID-1),floor(cy).toInt().coerceIn(0,GRID-1)),detail)
    }
    private fun paint(key:Int,detail:Int){if(detail<0)set(key,-1)else if(cells.size<MAX_CELLS||cells.containsKey(key))set(key,detail)}
    /** Dabs every [r]/2 along a straight segment. */
    fun line(ax:Double,ay:Double,bx:Double,by:Double,r:Double,detail:Int){
        val steps=max(1,ceil(hypot(bx-ax,by-ay)/max(r*.5,1e-9)).toInt()).coerceAtMost(4096)
        for(i in 1..steps){val t=i.toDouble()/steps;dab(ax+(bx-ax)*t,ay+(by-ay)*t,r,detail)}
    }
    /** A corridor [halfWidthMeters] either side of a route, as one stroke. */
    fun corridor(points:List<Coordinate>,halfWidthMeters:Double,detail:Int){
        if(points.isEmpty())return
        begin()
        var previous:Pair<Double,Double>?=null
        for(p in points){
            val mx=(p.longitude+180)/360;val my=CameraMath.mercatorY(p.latitude)
            val r=metersToMercator(halfWidthMeters,p.latitude)
            val last=previous
            if(last==null)dab(mx,my,r,detail)else line(last.first,last.second,mx,my,r,detail)
            previous=mx to my
        }
        end()
    }
    fun clear(){if(cells.isEmpty())return;begin();for(k in cells.keys.toList())set(k,-1);end()}
    fun replace(values:Map<Int,Int>){cells.clear();cells.putAll(values);strokes.clear();open=null;revision++}

    /** Image tiles to download (see [tilesOf]). */
    fun tiles()=tilesOf(cells)
    fun bounds()=boundsOf(cells)
    fun maxDetail()=cells.values.maxOrNull()?.let{DETAILS[it]}?:-1
    fun encode():String=encode(cells)

    companion object{
    /** Image tiles to download: every tile from zoom 0 to each cell's detail
     * zoom that covers a painted cell, ordered coarse to fine. */
    fun tilesOf(cells:Map<Int,Int>):List<GpuUploadQueue.Id>{
        val byZoom=Array(MAX_DETAIL_ZOOM+1){HashSet<Long>()}
        for((k,d)in cells){val x=k and 0xffff;val y=k ushr 16;val dz=DETAILS[d]
            for(z in 0..min(dz,CELL_ZOOM))byZoom[z].add(pack(x shr(CELL_ZOOM-z),y shr(CELL_ZOOM-z)))
            for(z in CELL_ZOOM+1..dz){val f=1 shl(z-CELL_ZOOM);for(dy in 0 until f)for(dx in 0 until f)byZoom[z].add(pack(x*f+dx,y*f+dy))}
        }
        val out=ArrayList<GpuUploadQueue.Id>(byZoom.sumOf{it.size})
        for(z in byZoom.indices)for(v in byZoom[z].sorted())out.add(GpuUploadQueue.Id(z,(v ushr 32).toInt(),(v and 0xffffffffL).toInt()))
        return out
    }
    /** Longitude/latitude bounds of the painted cells, or null if none. */
    fun boundsOf(cells:Map<Int,Int>):TileLoader.Bounds?{
        if(cells.isEmpty())return null
        var x0=Int.MAX_VALUE;var x1=-1;var y0=Int.MAX_VALUE;var y1=-1
        for(k in cells.keys){val x=k and 0xffff;val y=k ushr 16;x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)}
        val n=GRID.toDouble()
        return TileLoader.Bounds(x0/n*360-180,CameraMath.latitude((y1+1)/n),(x1+1)/n*360-180,CameraMath.latitude(y0/n))
    }
        const val CELL_ZOOM=16
        const val GRID=1 shl CELL_ZOOM
        /** Detail zooms for the four paint colours. */
        val DETAILS=intArrayOf(13,14,15,17)
        val NAMES=arrayOf("Overview","Standard","Detailed","Maximum")
        /** Red, orange, yellow, green: as the stored-detail legend. */
        val COLORS=intArrayOf(0xffe0503a.toInt(),0xffef8a2b.toInt(),0xffe8c22e.toInt(),0xff3fae5a.toInt())
        const val MAX_DETAIL_ZOOM=17
        /** About 100,000 km²: more than the South Island's hill country. */
        const val MAX_CELLS=400_000
        private const val MAX_DAB_CELLS=200_000L
        private const val MAX_UNDO=50
        fun key(x:Int,y:Int)=(y shl 16) or (x and 0xffff)
        private fun pack(x:Int,y:Int)=(x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        fun metersToMercator(meters:Double,latitude:Double)=meters/(40_075_016.686*cos(Math.toRadians(latitude)))
        /** Paint colour index for a stored imagery zoom, or -1 (below Overview). */
        fun detailForZoom(z:Int)=when{z>=17->3;z>=15->2;z>=14->1;z>=13->0;else->-1}
        /** Compact text form: per detail, the count and sorted key deltas as
         * varints; gzip; Base64 (no wraps). */
        fun encode(cells:Map<Int,Int>):String{
            val raw=ByteArrayOutputStream()
            fun varint(value:Long){var v=value;while(v>=0x80){raw.write(((v and 0x7f) or 0x80).toInt());v=v ushr 7};raw.write(v.toInt())}
            raw.write(1)
            for(d in DETAILS.indices){
                val keys=cells.filterValues{it==d}.keys.map{it.toLong() and 0xffffffffL}.sorted()
                varint(keys.size.toLong());var last=0L;for(k in keys){varint(k-last);last=k}
            }
            val zipped=ByteArrayOutputStream();GZIPOutputStream(zipped).use{it.write(raw.toByteArray())}
            return java.util.Base64.getEncoder().encodeToString(zipped.toByteArray())
        }
        fun decode(text:String):Map<Int,Int>?=runCatching{
            val bytes=GZIPInputStream(java.util.Base64.getDecoder().decode(text).inputStream()).use{it.readBytes()}
            var at=0
            fun varint():Long{var shift=0;var v=0L;while(true){val b=bytes[at++].toInt() and 255;v=v or((b and 0x7f).toLong() shl shift);if(b<0x80)return v;shift+=7;require(shift<64)}}
            require(bytes[at++].toInt()==1)
            val out=HashMap<Int,Int>()
            for(d in DETAILS.indices){val n=varint();require(n in 0..MAX_CELLS.toLong());var k=0L;repeat(n.toInt()){k+=varint();out[k.toInt()]=d}}
            out
        }.getOrNull()
    }
}
