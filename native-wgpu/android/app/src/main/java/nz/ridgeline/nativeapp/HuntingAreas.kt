package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** DOC recreational hunting permit areas, served live by the Department of
 * Conservation (ArcGIS "DOC Recreational Hunting Areas", CC BY 4.0). Only
 * permitted areas are requested, as DOC asks for external distribution. Each
 * map tile is one query in ArcGIS's compact tile mode (generalised to 1/1024
 * of the tile width); nothing is bundled with the app. */
internal object HuntingAreas {
    const val SERVICE="https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/DOC_Recreational_Hunting_Areas/FeatureServer/0/query"
    /** Shown from this camera zoom. */
    const val MIN_ZOOM=7.0
    /** Names from this camera zoom. */
    const val NAME_ZOOM=11.0
    /** Data tile zoom for a camera zoom: three levels keep requests few. */
    fun dataZoom(zoom:Double)=when{zoom<10.5->8;zoom<12.5->11;else->13}
    private const val HALF=20037508.342789244
    fun url(z:Int,x:Int,y:Int):String{
        val n=(1 shl z).toDouble();val size=2*HALF/n
        val x0=-HALF+x*size;val x1=x0+size;val y1=HALF-y*size;val y0=y1-size
        val tolerance=size/1024
        fun f(v:Double)=String.format(java.util.Locale.ROOT,"%.3f",v)
        val q="""{"mode":"view","originPosition":"upperLeft","tolerance":${f(tolerance)},"extent":{"xmin":${f(x0)},"ymin":${f(y0)},"xmax":${f(x1)},"ymax":${f(y1)},"spatialReference":{"wkid":102100}}}"""
        return SERVICE+"?where="+enc("HuntStatus='Permitted'")+"&geometry=${f(x0)},${f(y0)},${f(x1)},${f(y1)}&geometryType=esriGeometryEnvelope&inSR=102100"+
            "&spatialRel=esriSpatialRelIntersects&outFields=OBJECTID,HuntBlockName,PermitArea,BlockType,HuntingWebPage,Ha&returnGeometry=true&outSR=102100"+
            "&resultType=tile&quantizationParameters="+enc(q)+"&f=pbf"
    }
    private fun enc(s:String)=java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20")
    fun isUrl(url:String)=url.startsWith(SERVICE)

    /** One permitted area. [rings] are in Web Mercator world units (0..1). */
    class Area(val id:Long,val name:String,val permitArea:String,val type:String,val webPage:String,val hectares:Double,val rings:List<List<VectorTile.Point>>){
        val bounds=DoubleArray(4).also{b->b[0]=Double.MAX_VALUE;b[1]=Double.MAX_VALUE;b[2]=-Double.MAX_VALUE;b[3]=-Double.MAX_VALUE
            for(r in rings)for(p in r){b[0]=min(b[0],p.x);b[1]=min(b[1],p.y);b[2]=max(b[2],p.x);b[3]=max(b[3],p.y)}}
        /** Even-odd over all rings, so holes are outside. */
        fun contains(x:Double,y:Double):Boolean{
            if(x<bounds[0]||x>bounds[2]||y<bounds[1]||y>bounds[3])return false
            var inside=false
            for(r in rings)for(i in 1 until r.size){val a=r[i-1];val b=r[i];if((a.y>y)!=(b.y>y)&&x<a.x+(b.x-a.x)*(y-a.y)/(b.y-a.y))inside=!inside}
            return inside
        }
        /** A point well inside the area for its name (widest interior span). */
        val anchor:VectorTile.Point? by lazy{
            var best:VectorTile.Point?=null;var width=0.0
            for(fraction in listOf(.5,.35,.65,.2,.8)){
                val y=bounds[1]+(bounds[3]-bounds[1])*fraction;val hits=ArrayList<Double>()
                for(r in rings)for(i in 1 until r.size){val a=r[i-1];val b=r[i];if((a.y>y)!=(b.y>y))hits.add(a.x+(b.x-a.x)*(y-a.y)/(b.y-a.y))}
                hits.sort();for(i in 0 until hits.size-1 step 2){val w=hits[i+1]-hits[i];if(w>width){width=w;best=VectorTile.Point((hits[i]+hits[i+1])/2,y)}}
            };best
        }
    }

    /** Decodes ArcGIS's FeatureCollection protocol buffer (query f=pbf):
     * attributes in field order, geometry as ring lengths and zigzag delta
     * coordinates quantised by the result's transform (upper-left origin). */
    fun decode(bytes:ByteArray):List<Area>{
        val root=Pb(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        val query=root.message(2)?:return emptyList()
        val result=query.message(1)?:return emptyList()
        var sx=1.0;var sy=1.0;var tx=0.0;var ty=0.0
        result.message(12)?.let{t->t.message(2)?.let{s->sx=s.double(1)?:1.0;sy=s.double(2)?:1.0};t.message(3)?.let{o->tx=o.double(1)?:0.0;ty=o.double(2)?:0.0}}
        val fields=result.messages(13).map{it.string(1).orEmpty()}
        val areas=ArrayList<Area>()
        for(f in result.messages(15)){
            val values=f.messages(1).map{it.value()}
            val a=fields.indices.associate{fields[it] to values.getOrNull(it)}
            val g=f.message(2)?:continue
            val lengths=g.packed(2,false);val coords=g.packed(3,true)
            val rings=ArrayList<List<VectorTile.Point>>();var x=0L;var y=0L;var k=0
            for(n in lengths){
                val ring=ArrayList<VectorTile.Point>(n.toInt())
                repeat(n.toInt()){
                    if(k+1>=coords.size)return@repeat
                    x+=coords[k];y+=coords[k+1];k+=2
                    val mx=tx+x*sx;val my=ty-y*sy
                    ring.add(VectorTile.Point((mx/HALF+1)/2,(1-my/HALF)/2))
                }
                if(ring.size>=2)rings.add(ring)
            }
            if(rings.isEmpty())continue
            areas.add(Area((a["OBJECTID"] as? Number)?.toLong()?:0L,(a["HuntBlockName"] as? String).orEmpty(),(a["PermitArea"] as? String).orEmpty(),
                (a["BlockType"] as? String).orEmpty(),(a["HuntingWebPage"] as? String).orEmpty(),(a["Ha"] as? Number)?.toDouble()?:0.0,rings))
        }
        return areas
    }
    /** A minimal protocol-buffer reader over one message. */
    private class Pb(private val buffer:ByteBuffer){
        private class Field(val number:Int,val wire:Int,val start:Int,val length:Int,val value:Long)
        private val fields:List<Field> = run{
            val out=ArrayList<Field>();val b=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            while(b.hasRemaining()){
                val key=varint(b);val number=(key ushr 3).toInt();val wire=(key and 7).toInt()
                when(wire){
                    0->out.add(Field(number,0,0,0,varint(b)))
                    1->{out.add(Field(number,1,b.position(),8,0));b.position(b.position()+8)}
                    2->{val n=varint(b).toInt();require(n>=0&&n<=b.remaining()){"Truncated hunting data"};out.add(Field(number,2,b.position(),n,0));b.position(b.position()+n)}
                    5->{out.add(Field(number,5,b.position(),4,0));b.position(b.position()+4)}
                    else->throw IllegalArgumentException("Unsupported protocol buffer wire type $wire")
                }
            };out
        }
        private fun slice(f:Field)=buffer.duplicate().apply{position(f.start);limit(f.start+f.length)}.slice().order(ByteOrder.LITTLE_ENDIAN)
        fun message(n:Int)=fields.firstOrNull{it.number==n&&it.wire==2}?.let{Pb(slice(it))}
        fun messages(n:Int)=fields.filter{it.number==n&&it.wire==2}.map{Pb(slice(it))}
        fun double(n:Int)=fields.firstOrNull{it.number==n&&it.wire==1}?.let{buffer.getDouble(it.start)}
        fun string(n:Int)=fields.firstOrNull{it.number==n&&it.wire==2}?.let{val s=slice(it);val a=ByteArray(s.remaining());s.get(a);String(a,Charsets.UTF_8)}
        fun packed(n:Int,signed:Boolean):LongArray{
            val f=fields.firstOrNull{it.number==n&&it.wire==2}?:return LongArray(0)
            val s=slice(f);val out=ArrayList<Long>()
            while(s.hasRemaining()){val v=varint(s);out.add(if(signed)(v ushr 1) xor -(v and 1) else v)}
            return out.toLongArray()
        }
        /** An ArcGIS Value: string, float, double, or one of the integer kinds. */
        fun value():Any?{
            val f=fields.firstOrNull()?:return null
            return when(f.number){
                1->string(1)
                2->if(f.wire==5)buffer.getFloat(f.start).toDouble() else null
                3->if(f.wire==1)buffer.getDouble(f.start) else null
                4,8->(f.value ushr 1) xor -(f.value and 1)
                5,6,7->f.value
                9->f.value!=0L
                else->null
            }
        }
        companion object{
            fun varint(b:ByteBuffer):Long{var shift=0;var v=0L
                while(true){require(b.hasRemaining()&&shift<64){"Truncated hunting data"};val c=b.get().toInt() and 0xff;v=v or((c and 0x7f).toLong() shl shift);if(c<0x80)return v;shift+=7}}
        }
    }
}
