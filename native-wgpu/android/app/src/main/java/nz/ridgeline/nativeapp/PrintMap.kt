package nz.ridgeline.nativeapp

import android.graphics.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** A print-resolution map for PDFs, stitched from the map's own tiles (NZ Topo,
 * LINZ aerial or satellite) instead of a screen capture, which printed at about
 * 75 dpi. The open route is drawn on top. */
internal object PrintMap {
    const val DPI=200.0
    data class Plan(val zoom:Int,val width:Int,val height:Int,val tiles:List<GpuUploadQueue.Id>,val x0:Double,val y0:Double,val x1:Double,val y1:Double)
    /** Pixels for a map [mapWidthPt] points wide at [dpi], and the tile zoom
     * whose 256 px tiles reach that resolution (at most [maxZoom] and
     * [maxTiles] tiles). Never finer than the tiles themselves. */
    fun plan(b:TileLoader.Bounds,mapWidthPt:Double,maxZoom:Int,dpi:Double=DPI,maxTiles:Int=480,maxPixels:Long=12_000_000):Plan{
        val x0=(b.west+180)/360;val x1=(b.east+180)/360;val y0=CameraMath.mercatorY(b.north);val y1=CameraMath.mercatorY(b.south)
        require(x1>x0&&y1>y0){"Choose an area on the map."}
        val aspect=(y1-y0)/(x1-x0)
        val wanted=mapWidthPt/72*dpi
        fun count(z:Int):Long{val n=1 shl z;return (floor(x1*n).toLong()-floor(x0*n).toLong()+1)*(floor(y1*n).toLong()-floor(y0*n).toLong()+1)}
        var z=ceil(log2(wanted/((x1-x0)*256))).toInt().coerceIn(0,maxZoom)
        while(z>0&&count(z)>maxTiles)z--
        var width=min(wanted,(x1-x0)*256*(1 shl z))
        if(width*width*aspect>maxPixels)width=sqrt(maxPixels/aspect)
        val w=max(1,width.roundToInt());val h=max(1,(width*aspect).roundToInt())
        val n=1 shl z
        val tiles=buildList{for(y in floor(y0*n).toInt()..floor(y1*n).toInt().coerceAtMost(n-1))for(x in floor(x0*n).toInt()..floor(x1*n).toInt().coerceAtMost(n-1))add(GpuUploadQueue.Id(z,x,y))}
        return Plan(z,w,h,tiles,x0,y0,x1,y1)
    }
    /** Draws [plan]'s tiles (fetched four at a time) and [route]; null if
     * cancelled. A missing tile falls back to its parent. */
    fun render(plan:Plan,fetch:(Int,Int,Int)->ByteArray?,route:List<Coordinate>,routeWidthPx:Float,progress:(Int,Int)->Boolean):Bitmap?{
        val out=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(out);canvas.drawColor(Color.rgb(242,240,232))
        val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val n=(1 shl plan.zoom).toDouble()
        fun px(mx:Double)=((mx-plan.x0)/(plan.x1-plan.x0)*plan.width).toFloat()
        fun py(my:Double)=((my-plan.y0)/(plan.y1-plan.y0)*plan.height).toFloat()
        fun decode(bytes:ByteArray?)=bytes?.let{BitmapFactory.decodeByteArray(it,0,it.size)}
        val pool=Executors.newFixedThreadPool(4)
        try{
            val jobs:List<Future<Pair<Bitmap?,Rect?>>> = plan.tiles.map{t->pool.submit<Pair<Bitmap?,Rect?>>{
                decode(fetch(t.z,t.x,t.y))?.let{return@submit it to null}
                if(t.z==0)return@submit null to null
                val parent=decode(fetch(t.z-1,t.x/2,t.y/2))?:return@submit null to null
                val hw=parent.width/2;val hh=parent.height/2
                parent to Rect((t.x%2)*hw,(t.y%2)*hh,(t.x%2)*hw+hw,(t.y%2)*hh+hh)
            }}
            for((i,t)in plan.tiles.withIndex()){
                if(!progress(i,plan.tiles.size)){jobs.forEach{it.cancel(true)};out.recycle();return null}
                val (bitmap,src)=runCatching{jobs[i].get()}.getOrDefault(null to null)
                if(bitmap!=null){
                    // Half a pixel of overlap hides seams between neighbours.
                    val dst=RectF(px(t.x/n)-.5f,py(t.y/n)-.5f,px((t.x+1)/n)+.5f,py((t.y+1)/n)+.5f)
                    canvas.drawBitmap(bitmap,src,dst,paint);bitmap.recycle()
                }
            }
        }finally{pool.shutdownNow()}
        if(route.size>=2){
            val path=Path()
            route.forEachIndexed{i,p->val x=px((p.longitude+180)/360);val y=py(CameraMath.mercatorY(p.latitude));if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
            val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
            stroke.color=Color.WHITE;stroke.strokeWidth=routeWidthPx*1.8f;canvas.drawPath(path,stroke)
            stroke.color=Color.rgb(214,88,37);stroke.strokeWidth=routeWidthPx;canvas.drawPath(path,stroke)
        }
        return out
    }
}
