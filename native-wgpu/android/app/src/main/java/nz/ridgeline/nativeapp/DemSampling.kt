package nz.ridgeline.nativeapp

import java.nio.ByteBuffer
import kotlin.math.*

/** Exact original pixel-centre sampling, with no redundant interpolation at integer pixels. */
object DemSampling {
    data class Raster(val id:GpuUploadQueue.Id,val samples:FloatArray)
    /** Exact0.18TerrainSourceCache deltaZoom=1; original DEM ceiling stays18. */
    fun terrainSourceZoom(geometryZoom:Int):Int {
        require(geometryZoom in 0..22)
        return (geometryZoom-1).coerceIn(0,18)
    }
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
    fun footprint(z:Int,x:Int,y:Int,sourceZoom:Int=min(z,18)):Set<GpuUploadQueue.Id> {
        require(sourceZoom in 0..min(z,18))
        val dz=sourceZoom;val factor=1 shl(z-dz);val n=1 shl dz
        fun lo(v:Int)=floor(v*256.0/factor-.5).toInt()
        fun hi(v:Int)=floor((v*256.0+256)/factor-.5).toInt()+1
        val ids=LinkedHashSet<GpuUploadQueue.Id>()
        for(ty in Math.floorDiv(lo(y),256)..Math.floorDiv(hi(y),256))
            for(tx in Math.floorDiv(lo(x),256)..Math.floorDiv(hi(x),256))
                ids.add(GpuUploadQueue.Id(dz,Math.floorMod(tx,n),ty.coerceIn(0,n-1)))
        return ids
    }
    /** Generic same-zoom sampler retained for compatibility/height-buffer regressions. */
    fun mesh(z:Int,x:Int,y:Int,output:ByteBuffer,load:(GpuUploadQueue.Id)->Raster) =
        meshAtSourceZoom(z,x,y,min(z,18),output,load)
    /** Production terrain ownership matches the authoritative reference. */
    fun terrainMesh(z:Int,x:Int,y:Int,output:ByteBuffer,check:()->Unit={},load:(GpuUploadQueue.Id)->Raster) =
        meshAtSourceZoom(z,x,y,terrainSourceZoom(z),output,load,check)
    private fun meshAtSourceZoom(z:Int,x:Int,y:Int,dz:Int,output:ByteBuffer,load:(GpuUploadQueue.Id)->Raster,check:()->Unit={}) {
        val factor=1 shl(z-dz);val n=1 shl dz
        // A mesh touches at most three source rows/columns. Resolve the
        // immutable halo once; avoid 4*129² temporary IDs and hash lookups.
        val ids=footprint(z,x,y,dz)
        val columns=ids.map{it.x}.distinct().toIntArray()
        val rows=ids.map{it.y}.distinct().toIntArray()
        val halo=arrayOfNulls<Raster>(columns.size*rows.size)
        for(id in ids)halo[rows.indexOf(id.y)*columns.size+columns.indexOf(id.x)]=load(id)
        val pixelsX=IntArray(258);val slotsX=IntArray(258);val weightsX=DoubleArray(129)
        for(i in 0..128){
            val px=(x*256.0+i*2.0)/factor-.5;val ix=floor(px).toInt();weightsX[i]=px-ix
            for(tap in 0..1){val gx=Math.floorMod(ix+tap,n*256);val at=i*2+tap
                pixelsX[at]=gx;slotsX[at]=columns.indexOf(gx/256)}
        }
        fun height(px:Int,py:Int,slot:Int):Float {
            val record=halo[slot]!!
            val exact=record.samples[(py%256)*256+px%256]
            // Preserve fallback interpolation and signed zero bit for bit.
            return if(record.id.z==dz&&exact!=0f)exact
                else sample(record,px.toDouble(),py.toDouble(),dz)
        }
        for(j in 0..128){check()
            val py=(y*256.0+j*2.0)/factor-.5;val iy=floor(py).toInt();val fy=py-iy
            val py0=iy.coerceIn(0,n*256-1);val py1=(iy+1).coerceIn(0,n*256-1)
            val row0=rows.indexOf(py0/256)*columns.size;val row1=rows.indexOf(py1/256)*columns.size
            for(i in 0..128){
                val a=i*2;val b=a+1;val fx=weightsX[i]
                val h=(height(pixelsX[a],py0,row0+slotsX[a])*(1-fx)*(1-fy)+height(pixelsX[b],py0,row0+slotsX[b])*fx*(1-fy)+
                    height(pixelsX[a],py1,row1+slotsX[a])*(1-fx)*fy+height(pixelsX[b],py1,row1+slotsX[b])*fx*fy).toFloat()
                require(h.isFinite());output.putFloat(h)
            }
        }
        output.rewind()
    }
}
