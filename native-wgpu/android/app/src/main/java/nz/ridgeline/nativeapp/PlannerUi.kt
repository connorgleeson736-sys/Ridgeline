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
class PlannerUi(private val activity:Activity,private val root:FrameLayout,private val terrain:TerrainView,private val loader:TileLoader):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val files=Executors.newSingleThreadExecutor()
    private val preferences=activity.getSharedPreferences("planner",0)
    private val controller=RouteController(RouteDatabase(activity),java.util.concurrent.Executor{main.post(it)})
    private val passive=linkedMapOf<String,List<Coordinate>>()
    private val loading=hashSetOf<String>()
    private var lastPaths=emptyList<Triple<List<Coordinate>,Int,Int>>()
    private var summaryPoints=emptyList<Coordinate>()
    private var routeLength=0.0
    private var profilePoints=emptyList<Coordinate>()
    private var export:Pair<String,List<Coordinate>>?=null
    private var restoring=false
    private var closed=false
    private var changingTitle=false
    private var lastId:String?=null
    private val panel=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6));setBackgroundColor(Color.argb(245,251,252,248))}
    private val title=EditText(activity).apply{setSingleLine();hint="Route name";textSize=16f;filters=arrayOf(android.text.InputFilter.LengthFilter(80))}
    private val summary=TextView(activity).apply{textSize=12f;setPadding(0,dp(2),0,dp(2))}
    private val caption=TextView(activity).apply{textSize=11f}
    private val chart=ElevationProfileView(activity)
    private val tools=LinearLayout(activity)
    private val edit=button("Done"){controller.drawing(!controller.state.drawing)}
    private val undo=button("Undo"){controller.undo()}
    private val delete=button("Delete point"){controller.deleteSelected()}
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+.5f).toInt()
    private fun button(text:String,action:()->Unit)=Button(activity).apply{this.text=text;textSize=12f;minimumWidth=0;setPadding(dp(8),0,dp(8),0);setOnClickListener{action()}}
    private fun toast(message:String){Toast.makeText(activity,message,Toast.LENGTH_LONG).show()}
    init {
        val menu=button("Routes"){showRoutes()}
        root.addView(menu,FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.START).apply{leftMargin=dp(12);topMargin=dp(62)})
        val heading=LinearLayout(activity)
        heading.addView(title,LinearLayout.LayoutParams(0,dp(44),1f));heading.addView(button("×"){controller.unload()},LinearLayout.LayoutParams(dp(44),dp(44)))
        panel.addView(heading)
        val scroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false}
        for(view in listOf(edit,undo,delete,button("Fit"){terrain.focus(controller.state.points)},button("GPX"){exportGpx()}))tools.addView(view,LinearLayout.LayoutParams(-2,dp(42)))
        scroll.addView(tools);panel.addView(scroll)
        panel.addView(summary);panel.addView(chart,LinearLayout.LayoutParams(-1,dp(84)));panel.addView(caption)
        root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{bottomMargin=dp(46);marginStart=dp(8);marginEnd=dp(8)})
        panel.visibility=View.GONE
        title.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!changingTitle)controller.name(s.toString())}
            override fun afterTextChanged(s:Editable?){}
        })
        chart.onInspect={p,recenter->caption.text="${formatDistance(p.distance)} · ${p.height?.let{"${it.roundToInt()} m"}?:"Elevation unavailable"}";if(recenter)terrain.goTo(p.point)}
        terrain.onMapTap={x,y,index->
            val state=controller.state
            if(index>=0)controller.select(index)
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
            if(!restoring&&state.loaded){restoring=true;val old=preferences.getString("active",null);if(old!=null&&state.records.any{it.id==old})main.post{controller.open(old)}}
            panel.visibility=if(state.drawing||state.points.isNotEmpty()||state.id!=null)View.VISIBLE else View.GONE
            if(title.text.toString()!=state.name&&!title.hasFocus()){changingTitle=true;title.setText(state.name);changingTitle=false}
            edit.text=if(state.drawing)"Done"else"Edit";edit.isEnabled=state.loaded&&!state.busy
            undo.isEnabled=state.canUndo&&!state.busy;delete.isEnabled=state.selected>=0&&!state.busy
            val saved=state.error?:when(state.saveState){RouteController.SaveState.SAVING->"Saving…";RouteController.SaveState.SAVED->"Saved";RouteController.SaveState.ERROR->"Not saved";else->"Tap the map to add points"}
            if(change!=RouteController.Change.PREVIEW&&state.points!==summaryPoints){summaryPoints=state.points;routeLength=NativeGeo.length(state.points)}
            summary.text="${state.points.size} points · ${formatDistance(routeLength)} · $saved"
            if(state.id!=lastId){lastId=state.id;preferences.edit().apply{if(state.id==null)remove("active")else putString("active",state.id)}.apply()}
            if(state.id!=null)passive[state.id]=state.points
            syncRoutes()
            if(change==RouteController.Change.OPENED&&state.points.isNotEmpty())terrain.focus(state.points)
            if(state.points!=profilePoints&&change!=RouteController.Change.PREVIEW){
                profilePoints=state.points;chart.data(emptyList());caption.text=if(state.points.size>=2)"Loading elevation profile…"else"Long-press a waypoint to drag it."
                if(state.points.size>=2)loader.profile(state.points){values->
                    chart.data(values);val totals=NativeGeo.totals(values.map{it.height})
                    caption.text="↑ ${totals.ascent.roundToInt()} m   ↓ ${totals.descent.roundToInt()} m   High ${totals.high?.roundToInt()?:"—"} m${if(totals.complete)""else" · incomplete coverage"}"
                }
            }
        }
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
        paths.add(Triple(state.points,Color.rgb(211,70,38),state.selected))
        for(key in state.opened)if(key!=state.id){
            val points=passive[key]
            if(points!=null)paths.add(Triple(points,Color.rgb(71,112,174),-1))
            else if(loading.add(key))controller.loadOther(key){route->loading.remove(key);if(!closed&&route!=null&&key in controller.state.opened){passive[key]=route.points;syncRoutes()}}
        }
        if(paths!=lastPaths){lastPaths=paths;terrain.routes(paths)}
    }
    private fun showRoutes(){
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
        val scroll=ScrollView(activity).apply{addView(content)}
        val dialog=AlertDialog.Builder(activity).setTitle("Routes").setView(scroll).setPositiveButton("New route"){_,_->controller.newRoute()}.setNeutralButton("Import GPX"){_,_->importGpx()}.setNegativeButton("Close",null).create()
        if(controller.state.records.isEmpty())content.addView(TextView(activity).apply{text="No saved routes yet. Create a route and tap the map to add waypoints."})
        for(row in controller.state.records){
            val line=LinearLayout(activity)
            line.addView(CheckBox(activity).apply{isChecked=row.id in controller.state.opened;contentDescription="Show ${row.name} on map";setOnCheckedChangeListener{_,checked->if(checked)controller.showOther(row.id)else controller.closeOther(row.id)}},LinearLayout.LayoutParams(dp(44),dp(52)))
            line.addView(TextView(activity).apply{text="${row.name}\n${row.pointCount} points · ${formatDistance(row.lengthMeters)}";setPadding(dp(4),dp(8),0,dp(8));setOnClickListener{dialog.dismiss();controller.open(row.id)}},LinearLayout.LayoutParams(0,-2,1f))
            line.addView(button("⋮"){routeActions(row,dialog)},LinearLayout.LayoutParams(dp(44),dp(52)));content.addView(line)
        }
        content.addView(button("Combine shown routes"){
            val ids=controller.state.records.filter{it.id in controller.state.opened}.map{it.id to false}
            if(ids.size<2)toast("Show at least two saved routes first.")else{dialog.dismiss();controller.combine(ids)}
        })
        dialog.show()
    }
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
    fun pause(){controller.flush()}
    override fun close(){closed=true;controller.close();files.shutdown()}
    private fun formatDistance(m:Double)=if(m>=1000)String.format(java.util.Locale.ROOT,"%.2f km",m/1000)else"${m.roundToInt()} m"
}
