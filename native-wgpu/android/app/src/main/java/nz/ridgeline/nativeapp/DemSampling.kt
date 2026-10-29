package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import kotlin.math.*

/** Exact original pixel-centre sampling, with no redundant interpolation at integer pixels. */
object DemSampling {
    data class Raster(val id:GpuUploadQueue.Id,val samples:FloatArray)
    fun sample(record:Raster,gx:Double,gy:Double,z:Int):Float {
        val scale=2.0.pow(z-record.id.z)
        val px=((gx+.5)/scale-.5-record.id.x*256).coerceIn(0.0,255.0)
        val py=((gy+.5)/scale-.5-record.id.y*256).coerceIn(0.0,255.0)
        val x=floor(px).toInt();val y=floor(py).toInt();val fx=px-x;val fy=py-y
        fun h(a:Int,b:Int)=record.samples[b*256+a]
        return (h(x,y)*(1-fx)*(1-fy)+h(min(255,x+1),y)*fx*(1-fy)+
            h(x,min(255,y+1))*(1-fx)*fy+h(min(255,x+1),min(255,y+1))*fx*fy).toFloat()
    }
    /** Pixel-centre bilinear ground height from available original DEMs only.
     * Missing source/halo stays missing; camera queries never start downloads. */
    fun point(z:Int,mx:Double,my:Double,available:(GpuUploadQueue.Id)->Raster?):Float? {
        if(z !in 0..18||!mx.isFinite()||!my.isFinite()||my !in 0.0..1.0)return null
        val n=1 shl z;val size=n*256
        val px=(mx-floor(mx))*size-.5;val py=my*size-.5
        val ix=floor(px).toInt();val iy=floor(py).toInt();val fx=px-ix;val fy=py-iy
        fun height(x:Int,y:Int):Float? {
            val gx=Math.floorMod(x,size);val gy=y.coerceIn(0,size-1)
            val record=available(GpuUploadQueue.Id(z,gx/256,gy/256))?:return null
            val exact=record.samples[(gy%256)*256+gx%256]
            return (if(record.id.z==z&&exact!=0f)exact else sample(record,gx.toDouble(),gy.toDouble(),z)).takeIf{it.isFinite()}
        }
        val a=height(ix,iy)?:return null;val b=height(ix+1,iy)?:return null
        val c=height(ix,iy+1)?:return null;val d=height(ix+1,iy+1)?:return null
        return (a*(1-fx)*(1-fy)+b*fx*(1-fy)+c*(1-fx)*fy+d*fx*fy).toFloat().takeIf{it.isFinite()}
    }
    /** Fetch only DEM tiles touched by the full-resolution mesh and its pixel-centre halo. */
    fun footprint(z:Int,x:Int,y:Int):Set<GpuUploadQueue.Id> {
        val dz=min(z,18);val factor=1 shl(z-dz);val n=1 shl dz
        fun lo(v:Int)=floor(v*256.0/factor-.5).toInt()
        fun hi(v:Int)=floor((v*256.0+256)/factor-.5).toInt()+1
        val ids=LinkedHashSet<GpuUploadQueue.Id>()
        for(ty in Math.floorDiv(lo(y),256)..Math.floorDiv(hi(y),256))
            for(tx in Math.floorDiv(lo(x),256)..Math.floorDiv(hi(x),256))
                ids.add(GpuUploadQueue.Id(dz,Math.floorMod(tx,n),ty.coerceIn(0,n-1)))
        return ids
    }
    fun mesh(z:Int,x:Int,y:Int,output:ByteBuffer,load:(GpuUploadQueue.Id)->Raster) {
        val dz=min(z,18);val factor=1 shl(z-dz);val n=1 shl dz
        val halo=footprint(z,x,y).associateWith(load)
        fun height(gx:Int,gy:Int):Float {
            val px=Math.floorMod(gx,n*256);val py=gy.coerceIn(0,n*256-1)
            val record=halo.getValue(GpuUploadQueue.Id(dz,px/256,py/256))
            val exact=record.samples[(py%256)*256+px%256]
            // Keep the old signed-zero behavior (neighbor *0 terms can affect its sign).
            return if(record.id.z==dz&&exact!=0f)exact
                else sample(record,px.toDouble(),py.toDouble(),dz)
        }
        for(j in 0..128)for(i in 0..128) {
            val px=(x*256.0+i*2.0)/factor-.5;val py=(y*256.0+j*2.0)/factor-.5
            val ix=floor(px).toInt();val iy=floor(py).toInt();val fx=px-ix;val fy=py-iy
            val h=(height(ix,iy)*(1-fx)*(1-fy)+height(ix+1,iy)*fx*(1-fy)+
                height(ix,iy+1)*(1-fx)*fy+height(ix+1,iy+1)*fx*fy).toFloat()
            require(h.isFinite());output.putFloat(h)
        }
        output.rewind()
    }
}
