package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
class NativeAuditTest {
 @Test fun boundaryIndexMatchesEveryOriginalFragment(){
  val json=JSONObject(File(System.getProperty("ridgeline.assets"),"imagery-boundary.geojson").readText());val paths=ArrayList<List<VectorTile.Point>>()
  val fs=json.getJSONArray("features");for(i in 0 until fs.length()){val rows=fs.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates");for(j in 0 until rows.length()){val row=rows.getJSONArray(j);paths.add(List(row.length()){k->val p=row.getJSONArray(k);VectorTile.Point((p.getDouble(0)+180)/360,(1-asinh(tan(Math.toRadians(p.getDouble(1))))/PI)/2)})}}
  val indexed=BoundaryIndex(paths);var before=0L
  for(z in listOf(10,12,14)){val n=1 shl z;val x=floor((170.14219572+180)/360*n).toInt();val y=floor(CameraMath.mercatorY(-43.5955671)*n).toInt()
   for(dy in -2..1)for(dx in -3..2){val id=GpuUploadQueue.Id(z,x+dx,y+dy);val baseline=paths.flatMap{BoundaryIndex.clip(it,id.bounds){before++}};val actual=indexed.fragments(id);assertEquals(baseline,actual);val count=indexed.segmentTests;assertSame(actual,indexed.fragments(id));assertEquals(count,indexed.segmentTests)}
  };assertTrue(indexed.segmentTests<before/20);println("Production boundary segment tests: $before -> ${indexed.segmentTests}, exact ordered coordinates")
 }
 @Test fun measuredInteriorExactlyMatchesCompleteGrid(){
  var comparisons=0
  for(z in listOf(8,12,16,19))for(qy in 0..1)for(qx in 0..1){val n=1 shl(z-1);val sx=n/2;val sy=n/3;val x=sx*2+qx;val y=sy*2+qy
   val rasters=HashMap<GpuUploadQueue.Id,DemSampling.Raster>()
   fun raster(id:GpuUploadQueue.Id)=rasters.getOrPut(id){DemSampling.Raster(id,FloatArray(256*256){i->val gx=id.x*256+i%256;val gy=id.y*256+i/256;if((gx+gy)%997==0)-0f else ((gx.toLong()*13+gy.toLong()*7)%10007).toFloat()/4})}
   val full=ByteBuffer.allocateDirect(129*129*4).order(ByteOrder.LITTLE_ENDIAN);DemSampling.terrainMesh(z,x,y,full,load=::raster)
   val interior=MeasuredInterior.sample(z,x,y,raster(GpuUploadQueue.Id(z-1,sx,sy)))!!;val r=interior.rect
   assertEquals(16129,(r[2]-r[0])*(r[3]-r[1]))
   for(j in r[1]..r[3])for(i in r[0]..r[2]){val offset=(j*129+i)*4;assertEquals(full.getFloat(offset).toRawBits(),interior.packet.getFloat(16+offset).toRawBits());comparisons++}
  };println("Exact production interior comparisons: $comparisons")
 }
 @Test fun zeroBudgetRequiresCoverageUrgency(){val budget=UploadAdmission();assertFalse(budget.allows(GpuUploadQueue.Kind.IMAGE,0,0,0,256*256*4,0,0));assertTrue(budget.allows(GpuUploadQueue.Kind.IMAGE,0,0,0,256*256*4,0,0,true))}
}
