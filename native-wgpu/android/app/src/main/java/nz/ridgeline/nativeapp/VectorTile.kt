package nz.ridgeline.nativeapp

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded MVT 2.x decoder. Unknown protobuf fields/layers are skipped without allocation. */
internal object VectorTile {
    data class Point(val x:Double,val y:Double)
    data class Feature(val layer:String,val id:Long,val type:Int,val properties:Map<String,Any>,val paths:List<List<Point>>)
    private class Reader(val data:ByteArray,var at:Int=0,val end:Int=data.size){
        fun number():Long{var value=0L;for(shift in 0..63 step 7){if(at>=end)throw IOException("Truncated vector tile");val b=data[at++].toInt() and 255;if(shift==63&&b>1)throw IOException("Invalid vector integer");value=value or ((b and 127).toLong() shl shift);if(b<128)return value};throw IOException("Invalid vector integer")}
        fun size():Int{val n=number();if(n<0||n>end-at)throw IOException("Invalid vector field size");return n.toInt()}
        fun child():Reader{val size=size();val result=Reader(data,at,at+size);at+=size;return result}
        fun string():String{val size=size();if(size>65536)throw IOException("Oversized vector text");val s=String(data,at,size,Charsets.UTF_8);at+=size;return s}
        fun skip(wire:Int){when(wire){0->number();1->{if(end-at<8)throw IOException("Truncated vector value");at+=8};2->{val n=size();at+=n};5->{if(end-at<4)throw IOException("Truncated vector value");at+=4};else->throw IOException("Unsupported vector wire type")}}
        fun floating(bytes:Int):Double{if(end-at<bytes)throw IOException("Truncated vector float");val b=ByteBuffer.wrap(data,at,bytes).order(ByteOrder.LITTLE_ENDIAN);at+=bytes;return if(bytes==4)b.float.toDouble()else b.double}
    }
    private fun value(r:Reader):Any{
        var result:Any=""
        while(r.at<r.end){val tag=r.number().toInt();result=when(tag){10->r.string();21->r.floating(4);25->r.floating(8);32,40->r.number();48->{val n=r.number();(n ushr 1) xor -(n and 1)};56->r.number()!=0L;else->{r.skip(tag and 7);result}}}
        return result
    }
    fun decode(bytes:ByteArray,z:Int,x:Int,y:Int,layers:Set<String>):List<Feature>{
        require(bytes.size<=8*1024*1024&&z in 0..15&&x in 0 until(1 shl z)&&y in 0 until(1 shl z))
        val result=ArrayList<Feature>();val tile=Reader(bytes);var totalPoints=0;var totalFeatures=0
        while(tile.at<tile.end){val tag=tile.number().toInt();if(tag!=26){tile.skip(tag and 7);continue};val layer=tile.child()
            var name="";var extent=4096;val keys=ArrayList<String>();val values=ArrayList<Any>();val features=ArrayList<Reader>()
            while(layer.at<layer.end){val t=layer.number().toInt();when(t){10->name=layer.string();18->{features.add(layer.child());if(++totalFeatures>50000)throw IOException("Too many vector features")};26->keys.add(layer.string());34->values.add(value(layer.child()));40->extent=layer.number().toInt();else->layer.skip(t and 7)}}
            if(name !in layers)continue
            if(extent !in 1..65536)throw IOException("Invalid vector extent")
            val n=(1 shl z).toDouble();val scale=1.0/(extent*n)
            for(feature in features){var id=0L;var type=0;var geometry:Reader?=null;val tags=ArrayList<Int>()
                while(feature.at<feature.end){val t=feature.number().toInt();when(t){8->id=feature.number();18->{val tagsReader=feature.child();while(tagsReader.at<tagsReader.end){tags.add(tagsReader.number().toInt());if(tags.size>1024)throw IOException("Too many vector tags")}};24->type=feature.number().toInt();34->geometry=feature.child();else->feature.skip(t and 7)}}
                val geo=geometry?:continue;if(type !in 1..3)continue
                if(tags.size%2!=0)throw IOException("Invalid vector tags")
                val props=HashMap<String,Any>(tags.size/2);for(i in tags.indices step 2){val k=keys.getOrNull(tags[i])?:throw IOException("Invalid vector key");props[k]=values.getOrNull(tags[i+1])?:throw IOException("Invalid vector value")}
                val paths=ArrayList<List<Point>>();var path=ArrayList<Point>();var px=0L;var py=0L
                while(geo.at<geo.end){val command=geo.number();val kind=(command and 7).toInt();val count=command ushr 3
                    if(count !in 1..262144)throw IOException("Invalid vector geometry count")
                    when(kind){1,2->repeat(count.toInt()){
                        if(kind==1&&path.isNotEmpty()){paths.add(path);path=ArrayList()}
                        val dx=geo.number();val dy=geo.number();px+=(dx ushr 1) xor -(dx and 1);py+=(dy ushr 1) xor -(dy and 1)
                        if(px !in -1048576..1048576||py !in -1048576..1048576)throw IOException("Invalid vector coordinate")
                        path.add(Point(x/n+px*scale,y/n+py*scale));if(++totalPoints>262144)throw IOException("Too many vector vertices")
                    };7->{if(type!=3||count!=1L)throw IOException("Invalid vector ring");if(path.isNotEmpty()&&path.last()!=path.first())path.add(path.first())};else->throw IOException("Unsupported vector command")}
                };if(path.isNotEmpty())paths.add(path)
                result.add(Feature(name,id,type,props,paths))
            }
        };return result
    }
}
