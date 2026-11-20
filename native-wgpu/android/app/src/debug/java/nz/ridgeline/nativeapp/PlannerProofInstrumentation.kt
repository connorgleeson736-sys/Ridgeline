package nz.ridgeline.nativeapp
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.util.concurrent.atomic.AtomicReference

/** One end-to-end native planner and durable offline storage integration. */
class PlannerProofInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun <T> ui(action:()->T):T{val result=AtomicReference<T>();runOnMainSync{result.set(action())};return result.get()}
    private fun await(label:String,timeout:Long=180000,ready:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(!ready()){check(SystemClock.elapsedRealtime()<end){"Timeout: $label"};Thread.sleep(100)}}
    private fun terrain(view:View):TerrainView?{if(view is TerrainView)return view;if(view is ViewGroup)for(i in 0 until view.childCount)terrain(view.getChildAt(i))?.let{return it};return null}
    override fun onStart(){
        val result=Bundle();var activity:MainActivity?=null;var controller:RouteController?=null;var routeId:String?=null
        try{
            val current=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity;activity=current
            val planner=MainActivity::class.java.getDeclaredField("planner").apply{isAccessible=true}.get(current) as PlannerUi
            controller=planner.controller;val c=controller
            await("route storage ready"){ui{c.state.loaded&&!c.state.busy}}
            val route=GpxCodec.Imported("Native integration checkpoint",listOf(Coordinate(170.10,-43.62),Coordinate(170.14219572,-43.5955671),Coordinate(170.18,-43.58)))
            ui{c.import(route)}
            await("autosaved imported route"){ui{c.state.saveState==RouteController.SaveState.SAVED&&c.state.points==route.points}}
            routeId=ui{c.state.id}
            val surface=ui{terrain(current.window.decorView)}?:error("No native terrain")
            await("native route geometry and grounded frame",300000){ui{surface.groundedFrameReady()&&surface.projectedWaypoints().size==9&&surface.projectedWaypoints().any{it!=0f}}}
            ui{c.select(1);check(c.beginDrag(1));c.previewDrag(Coordinate(170.1424,-43.5956));c.finishDrag();check(c.state.canUndo);c.undo();check(c.state.points==route.points)}
            await("undo saved"){ui{c.state.saveState==RouteController.SaveState.SAVED}}
            val blob=GpxCodec.encode(route.name,ui{c.state.points});check(GpxCodec.decode(blob,"proof.gpx").points==route.points)
            targetContext.deleteDatabase("offline-proof.sqlite")
            var offline=OfflineStore(targetContext,"offline-proof.sqlite")
            val first=offline.begin("First");val second=offline.begin("Second");val url="https://fixture.invalid/offline-proof";val bytes=byteArrayOf(1,2,3,4)
            offline.keep(first,url,bytes);offline.keep(second,url,bytes);offline.status(first,"Ready");offline.status(second,"Ready");offline.close()
            offline=OfflineStore(targetContext,"offline-proof.sqlite");check(offline.regions().size==2);check(offline.read(url)!!.contentEquals(bytes));offline.delete(first);check(offline.read(url)!!.contentEquals(bytes));offline.delete(second);check(offline.read(url)==null);offline.close();targetContext.deleteDatabase("offline-proof.sqlite")
            result.putString("plannerProof","PASS: native Activity/controller binding, saved GPX route, grounded GPU route/waypoint projection, drag/undo/autosave, GPX round trip")
            result.putString("offlineProof","PASS: shared original bodies survive region deletion and DB reopen; final deletion releases unshared data")
            result.putString("scope","Functional Android integration; not phone performance or complete UI/feature acceptance")
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){result.putString("failure",e.javaClass.simpleName+": "+(e.message?.takeIf{!it.contains("http")}?:"see private runtime log"));finish(Activity.RESULT_CANCELED,result)}
        finally{controller?.let{c->routeId?.let{id->runOnMainSync{c.delete(id)}}};activity?.let{runOnMainSync{it.finish()}}}
    }
}
