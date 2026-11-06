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
    private fun verifyNativeRouteData(){
        val points=List(2000){Coordinate(170.14219572+it*1e-9,-43.5955671-it*1e-9)}
        val exported=GpxCodec.encode("Aoraki & <Tititea>",points.take(2))
        val imported=GpxCodec.decode(java.io.ByteArrayInputStream(exported),"fallback.gpx")
        check(imported.name=="Aoraki & <Tititea>"&&imported.points==points.take(2))
        val name="native-proof-routes.sqlite";targetContext.deleteDatabase(name)
        try {
            RouteDatabase(targetContext,name).use { db ->
                val first=db.save("proof-1","First",points,1000)
                check(first.id=="proof-1"&&db.load(first.id)?.points==points)
                db.save("proof-1","First updated",points.take(2),2000)
                check(db.summaries().size==1)
                db.save("proof-2","Second",points.take(2),3000)
                check(db.rename("proof-1","Renamed",4000))
                check(db.summaries().map{it.id}==listOf("proof-2","proof-1"))
                check(db.delete("proof-2"))
                for(i in 2..50)db.save("proof-$i","Route $i",points.take(2),5000)
                db.save("proof-1","Allowed existing edit",points.take(2),6000)
                try {db.save("proof-51","Limit",points.take(2));error("Route limit bypassed")}
                catch(_:RouteLimitException){}
                check(db.summaries().size==50)
            }
            RouteDatabase(targetContext,name).use { db ->check(db.summaries().size==50);check(db.load("proof-1")?.points==points.take(2))}
        }finally{targetContext.deleteDatabase(name)}
    }
    override fun onStart() {
        val result=Bundle()
        try {
            verifyNativeRouteData()
            result.putString("nativeDataProof","PASS: exact Float64 route storage, CRUD order, transaction limit rollback, database reopen, native GPX round trip")
            val first=startActivitySync(Intent(targetContext,ProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProofActivity
            await("first native frame and six pack reads") { ProofState.sixPacksRead && ProofState.activity.get()?.independentSourcesReady()==true && stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            val picked=java.util.concurrent.atomic.AtomicReference<DoubleArray?>()
            val done=java.util.concurrent.CountDownLatch(1)
            runOnMainSync {first.pickCenter{point->picked.set(point);done.countDown()}}
            check(done.await(30,java.util.concurrent.TimeUnit.SECONDS)){"Terrain pick timed out"}
            val point=picked.get()?:error("No terrain hit")
            check(kotlin.math.abs(point[0]-170.14219572)<1e-6&&kotlin.math.abs(point[1]+43.5955671)<1e-6&&kotlin.math.abs(point[2]-3724)<1e-4){"Terrain center differs from camera: ${point.toList()}"}
            runOnMainSync { first.showSurface(false) }
            await("surface destruction") { stats()[0]==0 }
            runOnMainSync { first.showSurface(true) }
            await("surface recreation and retained buffers") { ProofState.activity.get()?.independentSourcesReady()==true && stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            runOnMainSync { first.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("activity recreation") { ProofState.activity.get()?.let{it!==first}==true }
            await("rotated surface and packs") { ProofState.sixPacksRead && ProofState.activity.get()?.independentSourcesReady()==true && stats().let{it[0]==1&&it[1]>0&&it[2]==4} }
            result.putString("nativeProof","PASS: native wgpu independent geometry/image uploads and crop, exact terrain pick, six APK pack range decodes, surface destruction/recreation, rotated activity")
            result.putString("scope","Synthetic flat height fixture; not real-terrain parity or phone performance")
            runOnMainSync { ProofState.activity.get()?.finish() }
            await("all native surfaces released") { stats()[0]==0 }
            result.putInt("remainingNativeSurfaces",0)
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){result.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,result)}
    }
}
