package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class ResidentCoverageTest{
 private fun reference(target:GpuUploadQueue.Id,available:Set<GpuUploadQueue.Id>,ancestors:Boolean):Boolean{
  if(if(ancestors)available.any{it.contains(target)}else target in available)return true
  fun children(id:GpuUploadQueue.Id,rows:List<GpuUploadQueue.Id>):Boolean{
   if(id in available)return true
   if(rows.isEmpty()||id.z>=22)return false
   return (0..3).all{q->val c=GpuUploadQueue.Id(id.z+1,id.x*2+q%2,id.y*2+q/2);children(c,rows.filter{c.contains(it)})}
  }
  return children(target,available.filter{target.contains(it)})
 }
 @Test fun coverageIndexMatchesPartialAndNestedResidentTrees(){
  val random=java.util.Random(73);val root=GpuUploadQueue.Id(10,995,650)
  repeat(100){trial->
   val available=(0..95).map{val dz=1+random.nextInt(10);GpuUploadQueue.Id(root.z+dz,(root.x shl dz)+random.nextInt(1 shl dz),(root.y shl dz)+random.nextInt(1 shl dz))}.toMutableSet()
   if(trial%2==0)available+=root
   val index=ResidentCoverage.Index(available)
   for(target in available+root)for(ancestors in listOf(false,true))assertEquals(reference(target,available,ancestors),index.complete(target,ancestors))
  }
 }
 @Test fun benchmarkIndexedReadiness(){
  if(System.getenv("RIDGELINE_PRIORITY_BENCH")==null)return
  val root=GpuUploadQueue.Id(10,995,650)
  val available=(0..63).map{GpuUploadQueue.Id(16,(root.x shl 6)+it,(root.y shl 6)+it)}.toSet()+root
  val targets=(0..95).map{GpuUploadQueue.Id(17,(root.x shl 7)+it,(root.y shl 7)+it)}
  val index=ResidentCoverage.Index(available);val a=ArrayList<Long>();val b=ArrayList<Long>()
  fun run(current:Boolean):Long{val start=System.nanoTime();repeat(20){for(id in targets)assertTrue(if(current)index.complete(id)else reference(id,available,true))};return (System.nanoTime()-start)/20}
  repeat(60){i->val old:Long;val fresh:Long;if(i%2==0){old=run(false);fresh=run(true)}else{fresh=run(true);old=run(false)};if(i>=20){a+=old;b+=fresh}}
  val start=System.nanoTime();repeat(100){ResidentCoverage.Index(available)};val build=(System.nanoTime()-start)/100
  val report="""{"scope":"Host JVM readiness, 96 queries of 65 immutable resident sources; cached index, no I/O/GPU","beforeMedianNs":${a.sorted()[20]},"afterMedianNs":${b.sorted()[20]},"indexBuildNs":$build,"beforeNs":$a,"afterNs":$b}"""
  println(report);System.getenv("RIDGELINE_BENCH_OUTPUT")?.let{java.io.File(it,"readiness-index.json").writeText(report)}
 }

 @Test fun completeChildrenCoverParentButPartialChildrenNeverDo(){
    val parent=GpuUploadQueue.Id(12,3982,2600);val children=(0..3).map{GpuUploadQueue.Id(13,parent.x*2+it%2,parent.y*2+it/2)}
    assertTrue(ResidentCoverage.complete(parent,children.toSet()));assertFalse(ResidentCoverage.complete(parent,children.take(3).toSet()))
    val grandchildren=(0..3).map{GpuUploadQueue.Id(14,children.last().x*2+it%2,children.last().y*2+it/2)}
    assertTrue(ResidentCoverage.Index((children.take(3)+grandchildren).reversed().toSet()).complete(parent,false))
    assertTrue(ResidentCoverage.complete(children[0],setOf(parent)));assertFalse(ResidentCoverage.complete(children[0],setOf(parent),false));assertTrue(ResidentCoverage.complete(parent,children.toSet(),false));assertFalse(ResidentCoverage.complete(parent,emptySet()))
 }
}
