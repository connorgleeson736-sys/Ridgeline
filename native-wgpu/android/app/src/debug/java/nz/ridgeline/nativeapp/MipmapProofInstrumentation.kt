package nz.ridgeline.nativeapp
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
/** Exact device-side mip contents, distinct from successful frame submission. */
class MipmapProofInstrumentation:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 override fun onStart(){
  val result=Bundle();var activity:ProofActivity?=null
  try{
   val current=startActivitySync(Intent(targetContext,ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity;activity=current
   val deadline=SystemClock.elapsedRealtime()+120000
   while(!current.independentSourcesReady()){check(SystemClock.elapsedRealtime()<deadline){"Native fixture not ready"};Thread.sleep(100)}
   val done=CountDownLatch(1);val rows=AtomicReference<IntArray?>()
   runOnMainSync{current.checkMips{rows.set(it);done.countDown()}}
   check(done.await(60,TimeUnit.SECONDS)){"Mip readback timed out"}
   val values=rows.get()?:error("Mip readback unavailable")
   result.putString("levelsBadPixelsMinAlpha",values.toList().chunked(3).joinToString(";"))
   check((0..8).all{values[it*3+1]==0&&values[it*3+2]==255}){"Mip content mismatch"}
   result.putString("mipmapProof","PASS: full original level0 and all8derived levels retain exact checker mean and opacity")
   finish(Activity.RESULT_OK,result)
  }catch(e:Throwable){result.putString("failure",e.javaClass.simpleName);finish(Activity.RESULT_CANCELED,result)}
  finally{activity?.let{runOnMainSync{it.finish()}}}
 }
}
