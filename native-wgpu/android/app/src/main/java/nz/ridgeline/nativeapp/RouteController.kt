package nz.ridgeline.nativeapp

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface RouteStorage {
    fun summaries():List<RouteSummary>
    fun load(id:String):SavedRoute?
    fun save(id:String?,name:String,points:List<Coordinate>,now:Long=System.currentTimeMillis()):RouteSummary
    fun rename(id:String,name:String,now:Long=System.currentTimeMillis()):Boolean
    fun delete(id:String):Boolean
}

/** UI-owner session; all storage work is serialized off the UI thread. Stable
 * preallocated IDs prevent duplicate new rows when edits overtake an I/O save. */
class RouteController(
    private val storage:RouteStorage,
    private val deliver:Executor,
    private val io:ScheduledExecutorService=Executors.newSingleThreadScheduledExecutor(),
):AutoCloseable {
    enum class SaveState {IDLE,SAVING,SAVED,ERROR}
    enum class Change {VIEW,GEOMETRY,PREVIEW,SELECTION,SAVE,OPENED,LIST}
    data class State(val loaded:Boolean,val busy:Boolean,val name:String,val id:String?,val points:List<Coordinate>,val selected:Int,
                     val drawing:Boolean,val canUndo:Boolean,val dragging:Boolean,val saveState:SaveState,val error:String?,
                     val records:List<RouteSummary>,val opened:Set<String>,val revision:Long)
    private val editor=RouteEditor()
    private var loaded=false;private var busy=false;private var name="Untitled route";private var id:String?=null
    private var drawing=false;private var status=SaveState.IDLE;private var error:String?=null
    private var records=emptyList<RouteSummary>();private val opened=linkedSetOf<String>()
    private var dirty=false;private var revision=0L;private var saveRevision=0L;private var epoch=0L
    private var timer:ScheduledFuture<*>?=null
    @Volatile private var closed=false
    private val listeners=linkedSetOf<(State,Change)->Unit>()
    val state get()=State(loaded,busy,name,id,editor.points,editor.selected,drawing,editor.canUndo,editor.isDragging,status,error,records,opened.toSet(),revision)
    init {reload()}
    fun observe(listener:(State,Change)->Unit):()->Unit {listeners.add(listener);listener(state,Change.VIEW);return {listeners.remove(listener)}}
    private fun emit(change:Change){val snapshot=state;listeners.toList().forEach{it(snapshot,change)}}
    private fun post(action:()->Unit){deliver.execute{if(!closed)action()}}
    fun reload(){if(closed)return;io.execute{try{val rows=storage.summaries();post{records=rows;loaded=true;error=null;emit(Change.LIST)}}catch(_:Exception){post{error="Saved routes could not be read.";emit(Change.VIEW)}}}}
    private fun editable()=loaded&&!busy&&!closed
    private fun edited(){revision++;saveRevision++;dirty=true;error=null;emit(Change.GEOMETRY);save(0)}
    fun append(point:Coordinate):RouteEditor.Result {
        if(!editable()||editor.isDragging)return RouteEditor.Result.NO_CHANGE
        val result=editor.append(point);if(result==RouteEditor.Result.CHANGED)edited();return result
    }
    fun insert(index:Int,point:Coordinate):RouteEditor.Result {
        if(!editable())return RouteEditor.Result.NO_CHANGE
        val result=editor.insertAfter(index,point);if(result==RouteEditor.Result.CHANGED){drawing=true;edited()};return result
    }
    fun select(index:Int){if(editable()&&editor.select(index)){drawing=true;emit(Change.SELECTION)}}
    fun deleteSelected(){if(editable()&&editor.deleteSelected())edited()}
    fun undo(){if(editable()&&editor.undo())edited()}
    fun beginDrag(index:Int):Boolean {if(!editable())return false;val started=editor.beginDrag(index);if(started){drawing=true;emit(Change.SELECTION)};return started}
    fun previewDrag(point:Coordinate){if(editable()&&editor.previewDrag(point))emit(Change.PREVIEW)}
    fun finishDrag(){if(editable()&&editor.commitDrag())edited()}
    fun cancelDrag(){if(editor.cancelDrag())emit(Change.PREVIEW)}
    fun drawing(value:Boolean){if(!editable())return;finishDrag();drawing=value;emit(Change.VIEW)}
    fun name(value:String){if(!editable()||name==value.take(80))return;name=value.take(80);saveRevision++;dirty=true;error=null;emit(Change.VIEW);save(400)}
    private fun save(delay:Long,after:(()->Unit)?=null) {
        timer?.cancel(false)
        if(editor.points.isEmpty()&&id==null){dirty=false;status=SaveState.IDLE;busy=false;after?.invoke();return}
        if(!dirty){busy=false;after?.invoke();return}
        if(id==null)id=UUID.randomUUID().toString()
        val key=id!!;val points=editor.points;val title=name;val version=saveRevision;val generation=epoch
        status=SaveState.SAVING;error=null;emit(Change.SAVE)
        timer=io.schedule({
            try {
                storage.save(key,title,points);val rows=storage.summaries()
                post {
                    if(generation!=epoch)return@post
                    records=rows;opened.add(key)
                    if(version==saveRevision){dirty=false;status=SaveState.SAVED}
                    if(after!=null){busy=false;if(version==saveRevision)after()}
                    emit(Change.SAVE)
                }
            }catch(e:Exception){post{
                if(generation!=epoch)return@post
                if(version==saveRevision){status=SaveState.ERROR;error=if(e is RouteLimitException)e.message else "Not saved. Export GPX to keep this route."}
                if(after!=null)busy=false
                emit(Change.SAVE)
            }}
        },delay,TimeUnit.MILLISECONDS)
    }
    fun retrySave(){if(editable())save(0)}
    private fun navigate(action:()->Unit){if(!editable())return;finishDrag();busy=true;emit(Change.VIEW);save(0,action)}
    private fun reset(points:List<Coordinate>,title:String,key:String?,draw:Boolean) {
        epoch++;saveRevision++;revision++;editor.reset(points);name=title;id=key;drawing=draw;dirty=false
        status=if(key==null)SaveState.IDLE else SaveState.SAVED;busy=false;error=null
    }
    fun newRoute(){navigate{reset(emptyList(),"Untitled route",null,true);emit(Change.OPENED)}}
    fun unload(){navigate{val previous=id;reset(emptyList(),name,null,false);if(previous!=null)opened.remove(previous);emit(Change.GEOMETRY)}}
    fun open(key:String){navigate{
        busy=true;emit(Change.VIEW)
        io.execute{try{val route=storage.load(key)?:error("Missing route");post{reset(route.points,route.summary.name,key,false);opened.add(key);emit(Change.OPENED)}}catch(_:Exception){post{busy=false;error="This saved route could not be opened.";emit(Change.VIEW)}}}
    }}
    fun showOther(key:String){if(editable()){opened.add(key);emit(Change.LIST)}}
    fun closeOther(key:String){if(key==id)unload()else if(editable()){opened.remove(key);emit(Change.LIST)}}
    /** Load passive routes only when requested, preserving the metadata-only list path. */
    fun loadOther(key:String,callback:(SavedRoute?)->Unit){if(closed)return;io.execute{val route=runCatching{storage.load(key)}.getOrNull();post{callback(route)}}}
    fun import(route:GpxCodec.Imported){navigate{reset(route.points,route.name,null,false);dirty=true;emit(Change.OPENED);save(0)}}
    fun rename(key:String,value:String) {
        val title=value.trim();require(title.isNotEmpty()&&title.length<=80){"Use a route name of 1–80 characters."}
        if(!editable())return
        if(key==id){name=title;saveRevision++;dirty=true;emit(Change.VIEW);save(0);return}
        io.execute{try{check(storage.rename(key,title));val rows=storage.summaries();post{records=rows;emit(Change.LIST)}}catch(_:Exception){post{error="Route name was not saved.";emit(Change.VIEW)}}}
    }
    fun delete(key:String) {
        if(!editable())return
        val action={
            busy=true;emit(Change.VIEW)
            io.execute{try{check(storage.delete(key));val rows=storage.summaries();post{
                if(key==id)reset(emptyList(),name,null,false)
                records=rows;opened.remove(key);busy=false;emit(Change.GEOMETRY)
                if(dirty)save(0)
            }}catch(_:Exception){post{busy=false;error="Route was not deleted.";emit(Change.VIEW)}}}
        }
        if(key==id)navigate(action)else action()
    }
    fun combine(selection:List<Pair<String,Boolean>>) {navigate{
        if(records.size>=50){error="Delete or export an old route before combining (50 route limit).";emit(Change.VIEW);return@navigate}
        busy=true;emit(Change.VIEW)
        io.execute{try{
            val points=NativeGeo.combine(selection.map{(key,reverse)->(storage.load(key)?:error("Missing route")).points to reverse})
            post{reset(points,"Combined route",null,false);opened.clear();dirty=true;emit(Change.OPENED);save(0)}
        }catch(e:Exception){post{busy=false;error=e.message?:"Routes could not be combined.";emit(Change.VIEW)}}}
    }}
    fun flush(){if(!closed)save(0)}
    override fun close(){
        if(closed)return
        closed=true;listeners.clear();timer?.cancel(false)
        val points=editor.points;val title=name;val key=id
        val pending=dirty&&(points.isNotEmpty()||key!=null)
        io.execute{try{if(pending)storage.save(key,title,points)}finally{(storage as? AutoCloseable)?.close()}}
        io.shutdown()
    }
}
