package nz.ridgeline.nativeapp

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RouteControllerTest {
 private class Memory:RouteStorage {
  val values=LinkedHashMap<String,SavedRoute>();val calls=AtomicInteger();val fail=AtomicBoolean()
  @Volatile var entered:CountDownLatch?=null
  @Volatile var release:CountDownLatch?=null
  @Synchronized override fun summaries()=values.values.toList().asReversed().map{it.summary}
  @Synchronized override fun load(id:String)=values[id]
  override fun save(id:String?,name:String,points:List<Coordinate>,now:Long):RouteSummary {
   calls.incrementAndGet();entered?.countDown();release?.await(3,TimeUnit.SECONDS)
   if(fail.getAndSet(false))throw java.io.IOException("Simulated unavailable storage")
   synchronized(this){val key=id?:java.util.UUID.randomUUID().toString();if(key !in values&&values.size>=50)throw RouteLimitException()
    val row=RouteSummary(key,name.trim().ifEmpty{"Untitled route"},points.size,NativeGeo.length(points),now);values[key]=SavedRoute(row,points.toList());return row}
  }
  @Synchronized override fun rename(id:String,name:String,now:Long):Boolean {val old=values[id]?:return false;values[id]=old.copy(summary=old.summary.copy(name=name,updatedMillis=now));return true}
  @Synchronized override fun delete(id:String)=values.remove(id)!=null
 }
 private class Fixture(val storage:Memory=Memory()):AutoCloseable {
  val queue=LinkedBlockingQueue<Runnable>();val io=Executors.newSingleThreadScheduledExecutor()
  val controller=RouteController(storage,Executor{queue.add(it)},io)
  init{until{controller.state.loaded}}
  fun until(test:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!test()){check(System.nanoTime()<end){"Timed out: ${controller.state}"};queue.poll(20,TimeUnit.MILLISECONDS)?.run()}}
  fun saved(){until{controller.state.saveState==RouteController.SaveState.SAVED}}
  override fun close(){storage.release?.countDown();controller.close();io.shutdownNow()}
 }
 private val a=Coordinate(170.0,-43.0)
 private val b=Coordinate(170.01,-43.01)
 @Test fun editsDuringFirstSaveProduceOneRowWithLatestGeometry(){Fixture().use{f->
  f.storage.entered=CountDownLatch(1);f.storage.release=CountDownLatch(1)
  f.controller.append(a);assertTrue(f.storage.entered!!.await(2,TimeUnit.SECONDS));val key=f.controller.state.id
  f.controller.append(b);f.storage.release!!.countDown();f.saved()
  assertEquals(1,f.storage.summaries().size);assertEquals(key,f.controller.state.id);assertEquals(listOf(a,b),f.storage.load(key!!)!!.points)
 }}
 @Test fun failedAutosavePreventsNavigationAndRetryPreservesEdits(){Fixture().use{f->
  f.controller.append(a);f.saved();f.storage.fail.set(true);f.controller.name("Changed name");f.controller.newRoute()
  f.until{!f.controller.state.busy&&f.controller.state.saveState==RouteController.SaveState.ERROR}
  assertEquals(listOf(a),f.controller.state.points);assertEquals("Changed name",f.controller.state.name)
  f.controller.newRoute();f.until{f.controller.state.points.isEmpty()&&f.controller.state.drawing}
  assertEquals("Changed name",f.storage.summaries().single().name)
 }}
 @Test fun dragPreviewsDoNotWriteAndOneUndoRestoresPersistedGeometry(){Fixture().use{f->
  f.controller.append(a);f.controller.append(b);f.saved();val before=f.storage.calls.get()
  assertTrue(f.controller.beginDrag(1));repeat(20){f.controller.previewDrag(Coordinate(170.02+it*.00001,-43.02))};assertEquals(before,f.storage.calls.get())
  f.controller.finishDrag();f.saved();assertEquals(before+1,f.storage.calls.get());assertTrue(f.controller.state.canUndo)
  f.controller.undo();f.saved();assertEquals(listOf(a,b),f.storage.load(f.controller.state.id!!)!!.points);assertFalse(f.controller.state.canUndo&&f.controller.state.points.isEmpty())
 }}
 @Test fun deletingOneSavedRouteRetriesCurrentUnsavedRouteAtTheLimit(){
  val storage=Memory();repeat(50){storage.save("old-$it","Old $it",listOf(a,b),1000)}
  Fixture(storage).use{f->
   f.controller.append(a);f.until{f.controller.state.saveState==RouteController.SaveState.ERROR};val key=f.controller.state.id!!
   f.controller.delete("old-0");f.saved();assertEquals(50,storage.summaries().size);assertEquals(listOf(a),storage.load(key)!!.points)
  }
 }
}
