package nz.ridgeline.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.random.Random

/** Sea-level heights (exactly zero) take a direct path in the mesh, bit for bit as
 * the general sampler gave them (signed zero included). */
class DemSeaLevelTest {
    /** The mesh as before: every zero height through DemSampling.sample. */
    private fun reference(z:Int,x:Int,y:Int,dz:Int,load:(GpuUploadQueue.Id)->DemSampling.Raster):IntArray{
        val factor=1 shl(z-dz);val n=1 shl dz;val out=IntArray(129*129)
        fun height(gx:Int,gy:Int):Float{val record=load(GpuUploadQueue.Id(dz,gx/256,gy/256));val exact=record.samples[(gy%256)*256+gx%256]
            return if(record.id.z==dz&&exact!=0f)exact else DemSampling.sample(record,gx.toDouble(),gy.toDouble(),dz)}
        for(j in 0..128){val py=(y*256.0+j*2.0)/factor-.5;val iy=floor(py).toInt();val fy=py-iy
            val py0=iy.coerceIn(0,n*256-1);val py1=(iy+1).coerceIn(0,n*256-1)
            for(i in 0..128){val px=(x*256.0+i*2.0)/factor-.5;val ix=floor(px).toInt();val fx=px-ix
                val a=Math.floorMod(ix,n*256);val b=Math.floorMod(ix+1,n*256)
                val h=(height(a,py0)*(1-fx)*(1-fy)+height(b,py0)*fx*(1-fy)+height(a,py1)*(1-fx)*fy+height(b,py1)*fx*fy).toFloat()
                out[j*129+i]=java.lang.Float.floatToRawIntBits(h)}}
        return out
    }
    private fun mesh(z:Int,x:Int,y:Int,load:(GpuUploadQueue.Id)->DemSampling.Raster):IntArray{
        val buffer=ByteBuffer.allocate(129*129*4).order(ByteOrder.LITTLE_ENDIAN)
        DemSampling.terrainMesh(z,x,y,buffer,load=load)
        return IntArray(129*129){java.lang.Float.floatToRawIntBits(buffer.float)}
    }
    @Test fun seaLevelMatchesTheGeneralSamplerBitForBit(){
        val random=Random(7)
        // Sea (zero and negative zero), shore, and small negative heights, mixed.
        val choices=floatArrayOf(0f,-0f,0f,-0f,-1.5f,2f,0f,-0f,-0.25f,12f)
        val rasters=HashMap<GpuUploadQueue.Id,DemSampling.Raster>()
        val load={id:GpuUploadQueue.Id->rasters.getOrPut(id){DemSampling.Raster(id,FloatArray(65536){choices[random.nextInt(choices.size)]})}}
        for((z,x,y) in listOf(Triple(12,4030,2600),Triple(11,2015,1300),Triple(13,8061,5201))){
            val dz=DemSampling.terrainSourceZoom(z)
            assertArrayEquals("z$z",reference(z,x,y,dz,load),mesh(z,x,y,load))
        }
        // All sea: every height is exactly zero, positive or negative as before.
        rasters.clear()
        val sea={id:GpuUploadQueue.Id->rasters.getOrPut(id){DemSampling.Raster(id,FloatArray(65536){if(it%3==0)-0f else 0f})}}
        assertArrayEquals(reference(12,4030,2600,11,sea),mesh(12,4030,2600,sea))
    }
}
