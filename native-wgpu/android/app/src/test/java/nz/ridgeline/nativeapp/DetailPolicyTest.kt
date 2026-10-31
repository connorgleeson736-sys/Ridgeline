package nz.ridgeline.nativeapp
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.pow
class DetailPolicyTest {
 @Test fun defaultQualityAndIndependentSevenStepControls(){
  assertEquals(431.0,DetailPolicy.target(true,DetailPolicy.DEFAULT),0.0)
  assertEquals(256.0/2.0.pow(.25),DetailPolicy.target(false,DetailPolicy.DEFAULT),0.000001)
  for(geometry in listOf(false,true)){
   val targets=(0..6).map{DetailPolicy.target(geometry,it)}
   assertTrue(targets.zipWithNext().all{it.first>it.second})
   assertEquals(targets.first(),DetailPolicy.target(geometry,-1),0.0)
   assertEquals(targets.last(),DetailPolicy.target(geometry,7),0.0)
  }
 }
}
