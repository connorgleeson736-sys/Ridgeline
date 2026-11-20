package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Never claims a complete grid. Only these 128² measured vertices may be queried. */
internal object MeasuredInterior {
    data class Patch(val rect:IntArray,val packet:ByteBuffer)
    fun sample(z:Int,x:Int,y:Int,main:DemSampling.Raster):Patch? {
        if(z !in 1..19||main.id.z!=z-1||main.id.x!=x/2||main.id.y!=y/2)return null
        val rect=intArrayOf(if(x%2==0)1 else 0,if(y%2==0)1 else 0,if(x%2==0)128 else 127,if(y%2==0)128 else 127)
        val packet=ByteBuffer.allocateDirect(16+129*129*4).order(ByteOrder.LITTLE_ENDIAN);for(v in rect)packet.putInt(v)
        for(j in 0..128)for(i in 0..128){
            if(i<rect[0]||i>rect[2]||j<rect[1]||j>rect[3]){packet.putFloat(0f);continue}
            val px=(x*256.0+i*2.0)/2-.5;val py=(y*256.0+j*2.0)/2-.5
            val ix=floor(px).toInt();val iy=floor(py).toInt();val fx=px-ix;val fy=py-iy
            val xx=ix-main.id.x*256;val yy=iy-main.id.y*256
            fun h(dx:Int,dy:Int):Float{val h=main.samples[(yy+dy)*256+xx+dx];return if(h!=0f)h else DemSampling.sample(main,(ix+dx).toDouble(),(iy+dy).toDouble(),z-1)}
            val value=(h(0,0)*(1-fx)*(1-fy)+h(1,0)*fx*(1-fy)+h(0,1)*(1-fx)*fy+h(1,1)*fx*fy).toFloat()
            if(!value.isFinite())return null
            packet.putFloat(value)
        };packet.rewind();return Patch(rect,packet)
    }
}
