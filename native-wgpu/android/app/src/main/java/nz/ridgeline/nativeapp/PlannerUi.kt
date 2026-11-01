package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.*

/** Native planner bindings; metadata lists and file/database I/O stay off the map thread. */
class PlannerUi(private val activity:Activity,private val root:FrameLayout,private val terrain:TerrainView,private val loader:TileLoader,mapHost:FrameLayout):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val files=Executors.newSingleThreadExecutor()
    private val preferences=activity.getSharedPreferences("planner",0)
    internal val controller=RouteController(RouteDatabase(activity),java.util.concurrent.Executor{main.post(it)})
    private val passive=linkedMapOf<String,List<Coordinate>>()
    private val loading=hashSetOf<String>()
    private var lastPaths=emptyList<Triple<List<Coordinate>,Int,Int>>()
    private var summaryPoints=emptyList<Coordinate>()
    private var routeLength=0.0
    private var profilePoints=emptyList<Coordinate>()
    private var profileDirty=false
    private var profileValues=emptyList<NativeGeo.ProfilePoint>()
    private var export:Pair<String,List<Coordinate>>?=null
    private var restoring=false
    private var restoreId:String?=null
    private var closed=false
    private var changingTitle=false
    private var lastId:String?=null
    private val sheet=NativeSheet(root,mapHost)
    private val panel=sheet.content
    private val title=EditText(activity).apply{setSingleLine();hint="Route name";textSize=13f;setTextColor(NativeUi.ink);filters=arrayOf(android.text.InputFilter.LengthFilter(80));background=null;contentDescription="Route name"}
    private val summary=TextView(activity).apply{textSize=12f;setPadding(0,dp(2),0,dp(2))}
    private val caption=TextView(activity).apply{textSize=11f}
    private val chart=ElevationProfileView(activity)
    private val tools=LinearLayout(activity)
    private val edit=button("Done"){controller.drawing(!controller.state.drawing)}
    private val undo=button("Undo"){controller.undo()}
    private val delete=button("Delete point"){controller.deleteSelected()}
    private val stats=listOf("Distance","Ascent","Descent","Highest").map{NativeUi.text(activity,it+"\n—",12f)}
    private var drawer:View?=null
    private var drawerList:LinearLayout?=null
    private var drawerStamp:Any?=null
    private val combineSelection=linkedMapOf<String,Boolean>()
    var onOffline:()->Unit={}
    var onProfilePoint:(Coordinate?)->Unit={}
    var onDrawing:(Boolean)->Unit={}
    private var fitOpenedPending=false
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+.5f).toInt()
    private fun button(text:String,action:()->Unit)=NativeUi.button(activity,text,action=action)
    private fun toast(message:String){Toast.makeText(activity,message,Toast.LENGTH_LONG).show()}
    init {
        val heading=LinearLayout(activity)
        heading.addView(title,LinearLayout.LayoutParams(0,dp(38),1f));heading.addView(button("⋮"){routeMenu()},LinearLayout.LayoutParams(dp(44),dp(38)));heading.addView(button("×"){controller.unload()},LinearLayout.LayoutParams(dp(44),dp(38)))
        panel.addView(heading)
        panel.addView(LinearLayout(activity).apply{for(v in stats)addView(v,LinearLayout.LayoutParams(0,dp(43),1f))})
        panel.addView(chart,LinearLayout.LayoutParams(-1,dp(165)));panel.addView(caption)
        for(view in listOf(edit,undo,button("Export GPX"){exportGpx()},delete))tools.addView(view,LinearLayout.LayoutParams(0,dp(42),1f))
        panel.addView(tools);panel.addView(summary)
        sheet.onClose={controller.unload()}
        sheet.onSnap={expanded->chart.visibility=if(expanded)View.VISIBLE else View.GONE;caption.visibility=chart.visibility;heading.visibility=if(expanded)View.VISIBLE else View.GONE;summary.visibility=if(expanded)View.VISIBLE else View.GONE}
        title.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!changingTitle)controller.name(s.toString())}
            override fun afterTextChanged(s:Editable?){}
        })
        title.setOnFocusChangeListener{_,focused->if(!focused)controller.flush()}
        chart.onInspect={p,_->caption.text="${formatDistance(p.distance)} · ${p.height?.let{"${it.roundToInt()} m"}?:"Elevation unavailable"}";onProfilePoint(p.point)}
        chart.onRange={section->caption.text=section?.let{"Section ${formatDistance(it.distance)} · ↑ ${it.ascent.roundToInt()} m · ↓ ${it.descent.roundToInt()} m${if(it.complete)""else" · incomplete"}"}?:profileCaption()}
        chart.onLeave={onProfilePoint(null)}
        terrain.onMapTap={x,y,index->
            val state=controller.state
            if(index>=0&&state.drawing)controller.select(index)
            else if(state.drawing){
                val epoch=state.revision
                val insertion=nearestSegment(x,y)
                terrain.pick(x,y){hit->
                    if(hit==null)toast("Terrain is still loading here.")
                    else if(controller.state.drawing&&controller.state.revision==epoch){
                        val point=Coordinate(hit[0],hit[1]);val result=if(insertion>=0)controller.insert(insertion,point)else controller.append(point)
                        when(result){RouteEditor.Result.TOO_CLOSE->toast("Place the next point at least 5 m away.");RouteEditor.Result.OUTSIDE_NZ->toast("Choose a point in New Zealand.");RouteEditor.Result.LIMIT->toast("This route has reached 2,000 points.");else->{}}
                    }
                }
            }
        }
        terrain.onWaypointDragStart={controller.beginDrag(it)}
        terrain.onWaypointDrag={controller.previewDrag(it)}
        terrain.onWaypointDragEnd={if(it)controller.finishDrag()else controller.cancelDrag()}
        controller.observe{state,change->
            if(!restoring&&state.loaded){restoring=true;val old=preferences.getString("active",null);if(old!=null&&state.records.any{it.id==old}){restoreId=old;main.post{controller.open(old)}}}
            sheet.show(state.drawing||state.points.isNotEmpty()||state.id!=null)
            terrain.routeEditing=state.drawing;onDrawing(state.drawing)
            if(title.text.toString()!=state.name&&!title.hasFocus()){changingTitle=true;title.setText(state.name);changingTitle=false}
            edit.text=if(state.drawing)"Done"else"Edit";edit.isEnabled=state.loaded&&!state.busy
            undo.isEnabled=state.drawing&&state.canUndo&&!state.busy;delete.isEnabled=state.drawing&&state.selected>=0&&!state.busy
            val saved=state.error?:when(state.saveState){RouteController.SaveState.SAVING->"Saving…";RouteController.SaveState.SAVED->"Saved";RouteController.SaveState.ERROR->"Not saved";else->"Tap the map to add points"}
            if(change!=RouteController.Change.PREVIEW&&state.points!==summaryPoints){summaryPoints=state.points;routeLength=NativeGeo.length(state.points)}
            summary.text="${state.points.size} points · ${formatDistance(routeLength)} · $saved"
            if(state.id!=lastId){lastId=state.id;preferences.edit().apply{if(state.id==null)remove("active")else putString("active",state.id)}.apply()}
            stats[0].text="Distance\n${formatDistance(routeLength)}"
            if(state.id!=null)passive[state.id]=state.points
            passive.keys.retainAll(state.records.map{it.id}.toSet()+state.opened)
            syncRoutes()
            if(change==RouteController.Change.OPENED&&state.points.isNotEmpty()){if(state.id!=restoreId){if(fitOpenedPending)fitOpened()else terrain.focus(state.points)};restoreId=null}
            if(state.dragging){loader.cancelProfile();profileDirty=true}
            else if((state.points!=profilePoints||profileDirty)&&change!=RouteController.Change.PREVIEW){
                profileDirty=false;profilePoints=state.points;profileValues=emptyList();chart.data(emptyList());onProfilePoint(null)
                for(i in 1..3)stats[i].text=listOf("","Ascent","Descent","Highest")[i]+"\n—"
                caption.text=if(state.points.size>=2)"Loading elevation profile…"else"Tap to add · tap a line to insert · drag a point to move it."
                loader.cancelProfile()
                if(state.points.size>=2)loader.profile(state.points){values->
                    if(closed||profilePoints!=state.points)return@profile
                    profileValues=values;chart.data(values);val totals=NativeGeo.totals(values.map{it.height})
                    stats[1].text="Ascent\n${totals.ascent.roundToInt()} m";stats[2].text="Descent\n${totals.descent.roundToInt()} m";stats[3].text="Highest\n${totals.high?.roundToInt()?:"—"} m"
                    caption.text=profileCaption()
                }
            }
            if(drawer!=null)renderDrawer()
        }
    }
    private fun profileCaption():String{val totals=NativeGeo.totals(profileValues.map{it.height});return if(profileValues.isEmpty())"Elevation unavailable"else"${profileValues.size} samples${if(totals.complete)""else" · incomplete coverage"} · Drag the chart for section statistics"}
    private fun routeMenu(){AlertDialog.Builder(activity).setItems(arrayOf("Fit route","New route","Close route")){_,index->when(index){0->terrain.focus(controller.state.points);1->controller.newRoute();2->controller.unload()}}.show()}
    fun newRoute(){dismissDrawer();controller.newRoute()}
    fun dismissDrawer():Boolean{val view=drawer?:return false;root.removeView(view);drawer=null;drawerList=null;drawerStamp=null;return true}
    private fun fitOpened(){
        val state=controller.state;val all=ArrayList<Coordinate>();all.addAll(state.points)
        for(id in state.opened)if(id!=state.id)passive[id]?.let(all::addAll)
        terrain.focus(all);fitOpenedPending=state.opened.any{it!=state.id&&passive[it]==null}
    }
    private fun nearestSegment(x:Double,y:Double):Int {
        val values=terrain.projectedWaypoints();var best=(12*terrain.pixelScale).pow(2);var found=-1
        for(i in 0 until values.size/3-1){
            if(values[i*3+2]==0f||values[(i+1)*3+2]==0f)continue
            val ax=values[i*3].toDouble();val ay=values[i*3+1].toDouble();val dx=values[(i+1)*3]-ax;val dy=values[(i+1)*3+1]-ay
            val length=dx*dx+dy*dy;if(length==0.0)continue
            val t=((x-ax)*dx+(y-ay)*dy)/length
            if(t<=0||t>=1)continue
            val d=(x-ax-dx*t).pow(2)+(y-ay-dy*t).pow(2);if(d<best){best=d;found=i}
        };return found
    }
    private fun syncRoutes(){
        val state=controller.state
        val paths=ArrayList<Triple<List<Coordinate>,Int,Int>>()
        paths.add(Triple(state.points,NativeUi.orange,state.selected))
        for(key in state.opened)if(key!=state.id){
            val points=passive[key]
            if(points!=null)paths.add(Triple(points,Color.rgb(81,137,153),-1))
            else if(loading.add(key))controller.loadOther(key){route->loading.remove(key);if(!closed&&route!=null&&key in controller.state.opened){passive[key]=route.points;syncRoutes();if(fitOpenedPending)fitOpened()}}
        }
        if(paths!=lastPaths){lastPaths=paths;terrain.routes(paths)}
    }
    fun showRoutes(){
        if(drawer!=null){dismissDrawer();return}
        val overlay=FrameLayout(activity);overlay.addView(View(activity).apply{setBackgroundColor(Color.argb(40,16,40,35));setOnClickListener{dismissDrawer()}},FrameLayout.LayoutParams(-1,-1))
        val sidebar=NativeUi.column(activity).apply{setPadding(dp(16),dp(8),dp(16),dp(12));setBackgroundColor(NativeUi.paper);elevation=dp(12).toFloat();isClickable=true}
        val heading=LinearLayout(activity);heading.addView(NativeUi.heading(activity,"RIDGELINE"),LinearLayout.LayoutParams(0,-2,1f));heading.addView(button("×"){dismissDrawer()},LinearLayout.LayoutParams(dp(44),dp(44)));sidebar.addView(heading)
        sidebar.addView(NativeUi.text(activity,"New Zealand alpine planner",12f));NativeUi.space(sidebar)
        sidebar.addView(NativeUi.button(activity,"＋ Create route",true){newRoute()});NativeUi.space(sidebar)
        sidebar.addView(button("Import GPX"){dismissDrawer();importGpx()})
        drawerList=NativeUi.column(activity)
        sidebar.addView(ScrollView(activity).apply{addView(drawerList)},LinearLayout.LayoutParams(-1,0,1f))
        sidebar.addView(button("Download offline region"){dismissDrawer();onOffline()})
        overlay.addView(sidebar,FrameLayout.LayoutParams(minOf(dp(350),activity.resources.displayMetrics.widthPixels-dp(36)),-1,Gravity.START))
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1));drawer=overlay;drawerStamp=null;renderDrawer()
    }
    private fun renderDrawer(){
        val list=drawerList?:return;val state=controller.state
        combineSelection.keys.retainAll(state.records.map{it.id}.toSet())
        val stamp=listOf(state.records,state.id,state.opened,combineSelection.toMap())
        if(stamp==drawerStamp)return;drawerStamp=stamp;list.removeAllViews()
        list.addView(NativeUi.heading(activity,"Saved routes"))
        if(state.records.isEmpty())list.addView(NativeUi.text(activity,"No saved routes. Create a route to begin."))
        for(row in state.records){
            val card=NativeUi.column(activity);NativeUi.divider(card)
            val line=LinearLayout(activity);val thumb=RouteThumbnailView(activity);thumb.contentDescription="Route preview for ${row.name}";line.addView(thumb,LinearLayout.LayoutParams(dp(64),dp(56)))
            passive[row.id]?.let{thumb.data(it)}?:run{if(loading.add(row.id))controller.loadOther(row.id){route->loading.remove(row.id);if(!closed&&route!=null){passive[row.id]=route.points;if(drawer!=null)thumb.data(route.points)}}}
            val label=CheckBox(activity).apply{text=row.name;textSize=13f;setTextColor(NativeUi.ink);isChecked=combineSelection.containsKey(row.id);contentDescription="Select ${row.name} for combining";setOnCheckedChangeListener{_,checked->if(checked)combineSelection[row.id]=false else combineSelection.remove(row.id);renderDrawer()}}
            line.addView(label,LinearLayout.LayoutParams(0,-2,1f));card.addView(line)
            card.addView(NativeUi.text(activity,"${formatDistance(row.lengthMeters)} · ${row.pointCount} waypoints",11f))
            val actions=LinearLayout(activity)
            for((text,action) in listOf<Pair<String,()->Unit>>((if(row.id==state.id)"Editing"else"Open") to {fitOpenedPending=true;controller.open(row.id)},"Rename" to {renameRoute(row)},"Delete" to {deleteRoute(row)}))actions.addView(button(text,action),LinearLayout.LayoutParams(0,dp(40),1f))
            if(row.id in state.opened)actions.addView(button("Close"){controller.closeOther(row.id)},LinearLayout.LayoutParams(0,dp(40),1f))
            card.addView(actions)
            if(row.id in combineSelection){val position=combineSelection.keys.indexOf(row.id)+1;card.addView(button("Join $position · "+if(combineSelection[row.id]==true)"Direction: reversed"else"Direction: original"){combineSelection[row.id]=combineSelection[row.id]!=true;renderDrawer()})}
            list.addView(card)
        }
        NativeUi.divider(list)
        list.addView(button("Combine selected (${combineSelection.size})"){
            if(combineSelection.size<2)toast("Select at least two routes in the order you want to join them.")else{val order=combineSelection.map{it.key to it.value};dismissDrawer();controller.combine(order)}
        })
        list.addView(button("Close all routes"){controller.closeAll()})
    }
    private fun renameRoute(route:RouteSummary){val input=EditText(activity).apply{setSingleLine();setText(route.name);filters=arrayOf(android.text.InputFilter.LengthFilter(80))};AlertDialog.Builder(activity).setTitle("Rename route").setView(input).setPositiveButton("Save name"){_,_->try{controller.rename(route.id,input.text.toString())}catch(e:Exception){toast(e.message?:"Invalid name")}}.setNegativeButton("Cancel",null).show()}
    private fun deleteRoute(route:RouteSummary){AlertDialog.Builder(activity).setMessage("Delete ${route.name}?").setPositiveButton("Delete"){_,_->controller.delete(route.id)}.setNegativeButton("Cancel",null).show()}
    private fun routeActions(route:RouteSummary,parent:AlertDialog){
        AlertDialog.Builder(activity).setTitle(route.name).setItems(arrayOf("Open","Rename","Delete")){_,which->when(which){
            0->{parent.dismiss();controller.open(route.id)}
            1->{val input=EditText(activity).apply{setSingleLine();setText(route.name)};AlertDialog.Builder(activity).setTitle("Rename route").setView(input).setPositiveButton("Save"){_,_->try{controller.rename(route.id,input.text.toString());parent.dismiss()}catch(e:Exception){toast(e.message?:"Invalid name")}}.setNegativeButton("Cancel",null).show()}
            2->AlertDialog.Builder(activity).setMessage("Delete ${route.name}?").setPositiveButton("Delete"){_,_->controller.delete(route.id);parent.dismiss()}.setNegativeButton("Cancel",null).show()
        }}.show()
    }
    private fun importGpx(){activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/gpx+xml","application/xml","text/xml","application/octet-stream"))},4101)}
    private fun exportGpx(){
        val state=controller.state;if(state.points.size<2){toast("Add at least two waypoints first.");return}
        export=state.name to state.points.toList()
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/gpx+xml";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,GpxCodec.exportName(state.name))},4102)
    }
    fun result(request:Int,result:Int,data:Intent?){
        if(request !in 4101..4102||result!=Activity.RESULT_OK)return
        val uri=data?.data?:return;val snapshot=export
        files.execute{try{
            if(request==4101){
                val name=activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"route.gpx"}?:"route.gpx"
                val route=activity.contentResolver.openInputStream(uri)?.use{GpxCodec.decode(it,name)}?:error("GPX could not be opened")
                main.post{if(!closed)controller.import(route)}
            }else if(snapshot!=null){activity.contentResolver.openOutputStream(uri,"wt")?.use{it.write(GpxCodec.encode(snapshot.first,snapshot.second))}?:error("GPX could not be saved");main.post{toast("GPX saved")}}
        }catch(e:Exception){main.post{if(!closed)toast(e.message?:"File operation failed")}}}
    }
    fun back():Boolean{if(dismissDrawer())return true;if(controller.state.drawing){controller.drawing(false);return true};if(controller.state.id!=null||controller.state.points.isNotEmpty()){controller.unload();return true};return false}
    fun pause(){controller.flush()}
    override fun close(){closed=true;loader.cancelProfile();controller.close();files.shutdown()}
    private fun formatDistance(m:Double)=if(m>=1000)String.format(java.util.Locale.ROOT,"%.2f km",m/1000)else"${m.roundToInt()} m"
}
