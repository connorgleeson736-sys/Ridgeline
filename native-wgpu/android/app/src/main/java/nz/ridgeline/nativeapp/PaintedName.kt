package nz.ridgeline.nativeapp

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A name painted onto the map like print on a paper map: each letter's filled outline
 * as overlay lines on the ground (widths in Mercator units, so the lettering scales,
 * tilts and drapes over the terrain with the map and never moves on it), laid along a
 * curve. The only thing about it that depends on the camera is which way it reads. */
internal object PaintedName {
    /** Font units per em. */
    private const val UNITS=100f
    /** Letter spacing (em), as the website's. */
    const val TRACKING=.36
    /** Fill rows across the lettering's height. */
    private const val ROWS=28
    /** A letter in font units (y down, baseline at 0): its closed outlines and advance. */
    class Glyph(val contours:List<List<DoubleArray>>,val advance:Double)
    /** The letters of a text and the text's ink extent (y, font units). */
    class Font(val glyphs:List<Glyph>,val top:Double,val bottom:Double){
        val width=glyphs.sumOf{it.advance}+TRACKING*UNITS*(glyphs.size-1)
        val height=bottom-top
    }
    fun font(typeface:Typeface,text:String):Font{
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.typeface=typeface;textSize=UNITS}
        val glyphs=text.map{ch->
            val path=Path();paint.getTextPath(ch.toString(),0,1,0f,0f,path)
            val contours=ArrayList<List<DoubleArray>>();val m=PathMeasure(path,true);val pos=FloatArray(2)
            do{val length=m.length
                if(length>0f){val n=max(8,(length/1.5f).toInt())
                    contours.add(List(n){i->m.getPosTan(length*i/n,pos,null);doubleArrayOf(pos[0].toDouble(),pos[1].toDouble())})}
            }while(m.nextContour())
            Glyph(contours,paint.measureText(ch.toString()).toDouble())}
        val r=Rect();paint.getTextBounds(text,0,text.length,r)
        return Font(glyphs,r.top.toDouble(),r.bottom.toDouble())
    }
    /** A curve with its lengths; points before its start or past its end continue straight on. */
    private class Curve(points:List<VectorTile.Point>){
        val x=DoubleArray(points.size){points[it].x};val y=DoubleArray(points.size){points[it].y}
        val s=DoubleArray(points.size).also{for(i in 1 until points.size)it[i]=it[i-1]+hypot(x[i]-x[i-1],y[i]-y[i-1])}
        val length get()=s.last()
        fun at(t:Double):DoubleArray{
            var i=1;while(i<x.size-1&&s[i]<t)i++
            val f=(t-s[i-1])/max(1e-15,s[i]-s[i-1])
            return doubleArrayOf(x[i-1]+(x[i]-x[i-1])*f,y[i-1]+(y[i]-y[i-1])*f)
        }
    }
    /** The direction (unit, Mercator) from the name's first letter to its last along
     * [curve], written [height] tall (Mercator units) and not reversed. */
    fun chord(font:Font,curve:List<VectorTile.Point>,height:Double):DoubleArray{
        val c=Curve(curve);val half=font.width*height/font.height/2;val m=c.length/2
        val a=c.at(m-half);val b=c.at(m+half);val l=max(1e-15,hypot(b[0]-a[0],b[1]-a[1]))
        return doubleArrayOf((b[0]-a[0])/l,(b[1]-a[1])/l)
    }
    /** Whether a name along [chord] reads upside down (right to left on screen) at map
     * [bearing] (degrees, clockwise): with [was], it turns only once clearly so. */
    fun reversed(chord:DoubleArray,bearing:Double,was:Boolean?):Boolean{
        val b=Math.toRadians(bearing)
        // How far the name runs to the right on screen.
        val right=chord[0]*Math.cos(b)+chord[1]*Math.sin(b)
        return when{was==null->right<0;was->right<.2;else->right< -.2}
    }
    /** The lines painting [font]'s text [height] tall (Mercator units: the ink's height)
     * centred on [curve]'s middle, letter by letter along it, each letter turned to the
     * curve there; [above]: how far its middle sits north of the curve (Mercator).
     * [reversed]: read from the curve's end to its start (kept upright). */
    fun lines(font:Font,curve:List<VectorTile.Point>,height:Double,reversed:Boolean,above:Double,color:Int,halo:Int,order:Int):List<TopographyStyle.Line>{
        if(curve.size<2||font.height<=0)return emptyList()
        val c=Curve(curve);if(c.length<=0)return emptyList()
        val k=height/font.height;val mid=c.length/2;val total=font.width*k
        val spacing=font.height/ROWS;val middle=(font.top+font.bottom)/2
        val row=-(spacing*k*1.3).toFloat();val edge=-(spacing*k*.8).toFloat();val ring=-(height*.24).toFloat()
        val halos=ArrayList<TopographyStyle.Line>();val fills=ArrayList<TopographyStyle.Line>()
        var u=0.0
        for(g in font.glyphs){
            val centre=u+g.advance/2;u+=g.advance+TRACKING*UNITS
            val s=if(reversed)mid+total/2-centre*k else mid-total/2+centre*k
            // The letter turned to the curve's general line across it (not one point's).
            val d=max(g.advance*k/2,total*.02);val a=c.at(s-d);val b=c.at(s+d)
            var tx=b[0]-a[0];var ty=b[1]-a[1];val l=hypot(tx,ty);if(l<=0)continue;tx/=l;ty/=l
            // North of the curve, whichever way the name reads, so turning it never moves it across.
            var nx=-ty;var ny=tx;if(ny>0){nx=-nx;ny=-ny}
            val o=c.at(s);val ox=o[0]+nx*above;val oy=o[1]+ny*above
            if(reversed){tx=-tx;ty=-ty}
            // Down the letter (y down on the map, as in the font): the reading direction turned a right angle.
            val dx=-ty;val dy=tx
            fun world(x:Double,y:Double):VectorTile.Point{val lx=(x-g.advance/2)*k;val ly=(y-middle)*k
                return VectorTile.Point(ox+lx*tx+ly*dx,oy+lx*ty+ly*dy)}
            for(contour in g.contours){val ring2=contour.map{world(it[0],it[1])}+world(contour[0][0],contour[0][1])
                halos.add(TopographyStyle.Line(ring2,halo,ring,0f,0f,order))
                fills.add(TopographyStyle.Line(ring2,color,edge,0f,0f,order+1))}
            for(r in 0 until ROWS){val y=font.top+(r+.5)*spacing
                for((x0,x1) in spans(g.contours,y))if(abs(x1-x0)>1e-9)fills.add(TopographyStyle.Line(listOf(world(x0,y),world(x1,y)),color,row,0f,0f,order+1))}
        }
        return halos+fills
    }
    /** The inside of [contours] along the row [y] (non-zero winding, as fonts are filled). */
    internal fun spans(contours:List<List<DoubleArray>>,y:Double):List<Pair<Double,Double>>{
        val hits=ArrayList<Pair<Double,Int>>()
        for(c in contours)for(i in c.indices){val a=c[i];val b=c[(i+1)%c.size]
            if((a[1]<=y)==(b[1]<=y))continue
            hits.add(a[0]+(y-a[1])/(b[1]-a[1])*(b[0]-a[0]) to if(b[1]>a[1])1 else -1)}
        hits.sortBy{it.first}
        val out=ArrayList<Pair<Double,Double>>();var winding=0;var start=0.0
        for((x,d) in hits){val was=winding;winding+=d
            if(was==0&&winding!=0)start=x else if(was!=0&&winding==0)out.add(start to x)}
        return out
    }
}
