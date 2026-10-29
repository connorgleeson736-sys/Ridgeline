package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in genuine source integration proof. No synthetic fixture or source substitution. */
class LiveProofInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    private fun terrain(view:View):TerrainView? {
        if(view is TerrainView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)terrain(view.getChildAt(i))?.let{return it}
        return null
    }
    override fun onStart() {
        val result=Bundle()
        var activity:Activity?=null
        try {
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val surface=terrain(activity.window.decorView)?:error("No native terrain surface")
            val deadline=SystemClock.elapsedRealtime()+300000
            while(surface.residentSourceCounts().let{it.first==0||it.second==0}) {
                check(SystemClock.elapsedRealtime()<deadline){"Timed out awaiting genuine geometry and imagery; inspect provider/TLS/GPU logs"}
                Thread.sleep(100)
            }
            val point=AtomicReference<DoubleArray?>();val done=CountDownLatch(1)
            runOnMainSync {surface.pick(surface.width/2.0,surface.height/2.0){point.set(it);done.countDown()}}
            check(done.await(60,TimeUnit.SECONDS)){"Native pick timed out"}
            val hit=point.get()?:error("No genuine terrain hit at center")
            check(hit.all{it.isFinite()}&&hit[2] in -500.0..4000.0){"Invalid NZ terrain pick"}
            result.putString("nativeLiveProof","PASS: production TileLoader, real source pools, rendered terrain picking")
            result.putString("pickedLonLatHeight",hit.joinToString(","))
            result.putString("scope","Live Android integration only; not cross-LOD, visual parity or phone performance")
            finish(Activity.RESULT_OK,result)
        } catch(e:Throwable) {
            // Provider URLs/key values must never enter instrumentation output.
            result.putString("failure",e.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED,result)
        } finally {activity?.let{runOnMainSync{it.finish()}}}
    }
}
