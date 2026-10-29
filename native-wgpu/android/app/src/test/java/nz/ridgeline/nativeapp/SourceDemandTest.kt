package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class SourceDemandTest {
 @Test fun ancestorsConsumeBudgetWithoutRemovingVisibleCoverage(){
  val input=(0..15).flatMap{x->(0..15).map{y->GpuUploadQueue.Id(18,254000+x,166000+y)}}
  for(budget in listOf(16,32,64,128)){
   val out=SourceDemand.bounded(input,budget)
   assertTrue(out.size<=budget)
   for(id in input)assertTrue(out.keys.any{p->p.z<=id.z&&(id.x shr(id.z-p.z))==p.x&&(id.y shr(id.z-p.z))==p.y})
  }
 }
 @Test fun affordableSourcesKeepFullDetail(){
  val leaf=GpuUploadQueue.Id(12,3984,2600)
  val demand=SourceDemand.bounded(listOf(leaf),16)
  assertTrue(demand.containsKey(leaf));assertEquals(5,demand.size)
  assertEquals(0,demand[GpuUploadQueue.Id(8,249,162)])
 }
}
