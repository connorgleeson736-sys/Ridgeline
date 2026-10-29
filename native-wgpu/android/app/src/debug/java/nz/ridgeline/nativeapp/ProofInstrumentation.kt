package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android platform-only integration test: no browser, test framework or network. */
class ProofInstrumentation: Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    private val bytes=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
    private fun stats():List<Int> { bytes.rewind();check(NativeBridge.stats(bytes)==1);return List(3){bytes.int} }
    private fun await(label:String,predicate:()->Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+120000
        while(!predicate()) { check(android.os.SystemClock.elapsedRealtime()<deadline){"Timed out: $label"};Thread.sleep(50) }
    }
    override fun onStart() {
        val result=Bundle()
        try {
            val first=startActivitySync(Intent(targetContext,ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
            await("first native frame and six pack reads") { ProofState.sixPacksRead && stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            val picked=java.util.concurrent.atomic.AtomicReference<DoubleArray?>()
            val done=java.util.concurrent.CountDownLatch(1)
            runOnMainSync {first.pickCenter{point->picked.set(point);done.countDown()}}
            check(done.await(30,java.util.concurrent.TimeUnit.SECONDS)){"Terrain pick timed out"}
            val point=picked.get()?:error("No terrain hit")
            check(kotlin.math.abs(point[0]-170.14219572)<1e-6&&kotlin.math.abs(point[1]+43.5955671)<1e-6&&kotlin.math.abs(point[2]-3724)<1e-4){"Terrain center differs from camera: ${point.toList()}"}
            runOnMainSync { first.showSurface(false) }
            await("surface destruction") { stats()[0]==0 }
            runOnMainSync { first.showSurface(true) }
            await("surface recreation and retained buffers") { stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            runOnMainSync { first.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("activity recreation") { ProofState.activity.get()?.let{it!==first}==true }
            await("rotated surface and packs") { ProofState.sixPacksRead && stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            result.putString("nativeProof","PASS: Vulkan/wgpu frame, exact terrain pick, six APK pack range decodes, surface destruction/recreation, rotated activity")
            result.putString("scope","Synthetic flat height fixture; not real-terrain parity or phone performance")
            runOnMainSync { ProofState.activity.get()?.finish() }
            await("all native surfaces released") { stats()[0]==0 }
            result.putInt("remainingNativeSurfaces",0)
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){result.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,result)}
    }
}
