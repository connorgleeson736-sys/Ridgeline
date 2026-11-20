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
  assertTrue(demand.containsKey(leaf));assertTrue(demand.size<=16)
  assertTrue(demand[GpuUploadQueue.Id(11,1992,1300)]!!<demand[leaf]!!)
  assertEquals(0,demand[GpuUploadQueue.Id(11,1992,1300)])
 }
 @Test fun visibleDetailIsNeverEvictedToMakeRoomForAncestors(){
  val leaves=(0..23).map{GpuUploadQueue.Id(18,254000+it,166000)}
  val demand=SourceDemand.bounded(leaves,32)
  assertTrue(demand.size<=32);assertTrue(demand.keys.containsAll(leaves))
  assertTrue(demand.containsKey(GpuUploadQueue.Id(17,127000,83000)))
 }
 @Test fun allFourCloseLeavesPrecedeDistantCoverage(){
  val leaves=(0..47).map{GpuUploadQueue.Id(18,254000+it,166000)}
  val demand=SourceDemand.bounded(leaves,64)
  val distant=demand.filter{(id,_)->id !in leaves&&!id.contains(leaves.first())}.values
  for(leaf in leaves.take(4))assertTrue(demand.getValue(leaf)<distant.min())
 }
 @Test fun backgroundCoverageStartsBeforeBackgroundFineDetail(){
  val leaves=(0..47).map{GpuUploadQueue.Id(18,254000+it,166000)}
  val demand=SourceDemand.bounded(leaves,64)
  for(leaf in leaves.drop(4))assertTrue(demand.any{(p,priority)->p.z<leaf.z&&(leaf.x shr(leaf.z-p.z))==p.x&&(leaf.y shr(leaf.z-p.z))==p.y&&priority<demand.getValue(leaf)})
  assertTrue(demand.getValue(leaves.first())<=1)
 }
}
