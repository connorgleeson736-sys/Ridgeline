package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
class ResidentCoverageTest{
 @Test fun completeChildrenCoverParentButPartialChildrenNeverDo(){
    val parent=GpuUploadQueue.Id(12,3982,2600);val children=(0..3).map{GpuUploadQueue.Id(13,parent.x*2+it%2,parent.y*2+it/2)}
    assertTrue(ResidentCoverage.complete(parent,children.toSet()));assertFalse(ResidentCoverage.complete(parent,children.take(3).toSet()))
    assertTrue(ResidentCoverage.complete(children[0],setOf(parent)));assertFalse(ResidentCoverage.complete(children[0],setOf(parent),false));assertTrue(ResidentCoverage.complete(parent,children.toSet(),false));assertFalse(ResidentCoverage.complete(parent,emptySet()))
 }
}
