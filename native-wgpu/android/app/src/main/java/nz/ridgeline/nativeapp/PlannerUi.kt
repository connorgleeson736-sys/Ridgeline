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
    /** Last requested profile: detail pass included, and its in-view focus. */
    private var profileDetail=false
    private var profileFocus=""
    private val settle=Runnable{cameraSettled()}
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
    /** Capacitor's section ends on the map: green A, orange B. */
    private val rangeBadges=listOf("A" to ElevationProfileView.START,"B" to ElevationProfileView.END).map{(label,color)->TextView(activity).apply{
        text=label;textSize=10f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);gravity=Gravity.CENTER;visibility=View.GONE;elevation=dp(3).toFloat()
        background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(color);setStroke(dp(2),Color.WHITE)}
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }}
    private val chart=ElevationProfileView(activity)
    private val tools=LinearLayout(activity)
    private val edit=button("Done"){controller.drawing(!controller.state.drawing)}
    private val undo=button("Undo"){controller.undo()}
    private val delete=button("Delete point"){controller.deleteSelected()}
    private val stats=listOf("Distance","Ascent","Descent","Highest").map{StatView(activity).apply{text=it+"\n—"}}
    /** Capacitor route stat: small muted label above a larger value. */
    private class StatView(context:android.content.Context):TextView(context){
        init{textSize=13f;setTextColor(NativeUi.ink)}
        override fun setText(text:CharSequence?,type:BufferType?){
            val plain=text?.toString();val split=plain?.indexOf('\n')?:-1
            if(text is android.text.Spanned||plain==null||split<0){super.setText(text,type);return}
            val styled=android.text.SpannableString(plain)
            styled.setSpan(android.text.style.RelativeSizeSpan(.8f),0,split,0);styled.setSpan(android.text.style.ForegroundColorSpan(NativeUi.muted),0,split,0)
            styled.setSpan(android.text.style.RelativeSizeSpan(1.25f),split+1,plain.length,0)
            super.setText(styled,BufferType.SPANNABLE)
        }
    }
    private fun metres(value:Double)=String.format(java.util.Locale.ROOT,"%,d m",value.roundToInt())
    private var drawer:View?=null
    private var drawerList:LinearLayout?=null
    private var drawerStamp:Any?=null
    private data class Card(val view:LinearLayout,val check:CheckBox,val thumb:RouteThumbnailView,val summary:TextView,val open:TextView,val close:TextView,val order:TextView)
    private val drawerCards=linkedMapOf<String,Card>()
    private var updatingCards=false
    private var combineButton:TextView?=null
    private var closeAllButton:TextView?=null
    private val combineSelection=linkedMapOf<String,Boolean>()
    var onOffline:()->Unit={}
    /** A tap on the map while not drawing a route (e.g. hunting area details). */
    var onIdleTap:(Double,Double)->Unit={_,_->}
    /** A search result chosen: true if it opened its own summary (tracks, huts). */
    internal var onPlace:(PlaceSearch.Place)->Boolean={false}
    /** Fills the drawer's saved-regions section under the routes (OfflineUi). */
    var savedRegions:(LinearLayout,()->Unit)->Unit={_,_->}
    var onProfilePoint:(Coordinate?)->Unit={}
    var onDrawing:(Boolean)->Unit={}
    /** Bottom map controls move above the route sheet's overlapping edge. */
    var onMapCover:(Int)->Unit={}
    private var fitOpenedPending=false
    /** Offline place search (LINZ Gazetteer, DOC huts, campsites and walks),
     * loaded on first use off the main thread. */
    @Volatile private var placeSearch:PlaceSearch?=null
    private var searchQuery=""
    private var searchResults:LinearLayout?=null
    private var routesContent:List<View> = emptyList()
    private val searchRun=Runnable{runSearch()}
    private fun loadSearch(){if(placeSearch==null)files.execute{if(placeSearch==null)placeSearch=PlaceSearch.get(activity.assets);main.post{if(searchQuery.isNotBlank())runSearch()}}}
    private fun runSearch(){
        val query=searchQuery;val list=searchResults?:return
        if(query.isBlank()){list.removeAllViews();list.visibility=View.GONE;for(v in routesContent)v.visibility=View.VISIBLE;return}
        list.visibility=View.VISIBLE;for(v in routesContent)v.visibility=View.GONE
        val engine=placeSearch
        if(engine==null){list.removeAllViews();list.addView(NativeUi.text(activity,"Loading place names…",13f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(10),0,dp(10))});loadSearch();return}
        val near=terrain.cameraPose.let{Coordinate(it.longitude,it.latitude)}
        files.execute{val results=engine.search(query,near);main.post{if(searchQuery==query&&searchResults===list)showResults(list,results)}}
    }
    private fun showResults(list:LinearLayout,results:List<PlaceSearch.Result>){
        list.removeAllViews()
        if(results.isEmpty()){list.addView(NativeUi.text(activity,"No places, huts or tracks found.",13f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(10),0,dp(10))});return}
        for(result in results){val place=result.place
            val row=NativeUi.column(activity).apply{setPadding(dp(2),dp(8),dp(2),dp(8));background=NativeUi.pressable(activity,null);isClickable=true;contentDescription="${PlaceSearch.label(place.kind)} ${place.name}"}
            row.addView(NativeUi.text(activity,place.name,14f).apply{setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(NativeUi.ink);maxLines=2})
            val distance=if(result.distanceKm<1)"${(result.distanceKm*1000).roundToInt()} m away" else if(result.distanceKm<100)String.format(java.util.Locale.ROOT,"%.1f km away",result.distanceKm)else"${result.distanceKm.roundToInt()} km away"
            row.addView(NativeUi.text(activity,listOf(PlaceSearch.label(place.kind),place.detail,distance).filter{it.isNotBlank()}.joinToString(" · "),11.5f).apply{setTextColor(Color.rgb(120,132,124));maxLines=2})
            if(place.link.isNotBlank())row.addView(link("DOC page"){runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(place.link)))}.onFailure{toast("No browser is available.")}}.apply{textSize=12.5f},LinearLayout.LayoutParams(-2,dp(30)))
            row.setOnClickListener{
                hideKeyboard();dismissDrawer()
                val point=Coordinate(place.longitude,place.latitude)
                terrain.focus(listOf(point),PlaceSearch.zoom(place.kind))
                if(!onPlace(place)){terrain.inspect(point);main.removeCallbacks(clearFound);main.postDelayed(clearFound,8000)}
            }
            list.addView(row);NativeUi.divider(list)
        }
    }
    private val clearFound=Runnable{terrain.inspect(null)}
    // Build the search index in the background shortly after launch, so it is
    // normally ready before the search field is first used.
    init{main.postDelayed({if(!closed)loadSearch()},4000)}
    private fun hideKeyboard(){(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)}
    private var combiningPending=false
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+.5f).toInt()
    private fun button(text:String,action:()->Unit)=NativeUi.button(activity,text,action=action)
    /** Capacitor drawer actions: orange text links. */
    private fun link(text:String,action:()->Unit)=TextView(activity).apply{this.text=text;textSize=15f;setTextColor(android.graphics.Color.rgb(196,104,46));gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,dp(18),0);isClickable=true;isFocusable=true;background=NativeUi.pressable(activity,null);setOnClickListener{action()}}
    /** Set text only when it changes: each set re-lays-out the sheet, and drags
     * update route state on every move. */
    private fun show(view:TextView,value:String){if(view.text.toString()!=value)view.text=value}
    private fun toast(message:String){Toast.makeText(activity,message,Toast.LENGTH_LONG).show()}
    init {
        // Capacitor route sheet: name field, stats, a chart that fills the
        // expanded sheet, then Edit / Undo / Export GPX / Delete point.
        val heading=LinearLayout(activity)
        title.background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(4).toFloat();setStroke(dp(1),Color.rgb(180,188,182))};title.setPadding(dp(8),0,dp(8),0);title.textSize=16f
        heading.addView(title,LinearLayout.LayoutParams(0,dp(40),1f))
        panel.addView(heading,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
        // Same columns as the tool buttons below (equal widths, 8 dp gaps), centred.
        panel.addView(LinearLayout(activity).apply{for((i,v) in stats.withIndex())addView(v.apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(40),1f).apply{if(i>0)marginStart=dp(8)})})
        panel.addView(chart,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(4)})
        val export=button("Export GPX"){exportGpx()}.apply{setOnLongClickListener{shareGpx();true};contentDescription="Export GPX. Long press to share."}
        delete.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.argb(110,186,99,53),Color.rgb(186,99,53))))
        for((i,view) in listOf(edit,undo,export,delete).withIndex())tools.addView(view.apply{minimumHeight=0;minHeight=0},LinearLayout.LayoutParams(0,dp(34),1f).apply{if(i>0)marginStart=dp(8)})
        panel.addView(tools,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)});panel.addView(summary)
        sheet.onClose={controller.unload()}
        sheet.onCover={onMapCover(it)}
        sheet.onSnap={expanded->chart.visibility=if(expanded)View.VISIBLE else View.GONE;heading.visibility=if(expanded)View.VISIBLE else View.GONE;summary.visibility=if(expanded&&controller.state.drawing)View.VISIBLE else View.GONE;if(!expanded)chart.clearInspection()}
        for(badge in rangeBadges)mapHost.addView(badge,1,android.widget.FrameLayout.LayoutParams(dp(22),dp(22)))
        terrain.onRangeMarkers={points,positions->
            for((i,badge) in rangeBadges.withIndex()){
                val visible=i<points.size&&i*3+2<positions.size&&positions[i*3+2]>0f
                badge.visibility=if(visible)View.VISIBLE else View.GONE
                if(visible){badge.translationX=positions[i*3]-dp(11);badge.translationY=positions[i*3+1]-dp(11)}
            }
        }
        title.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!changingTitle)controller.name(s.toString())}
            override fun afterTextChanged(s:Editable?){}
        })
        title.setOnFocusChangeListener{_,focused->if(!focused)controller.flush()}
        // The chart draws its own readout; the map shows one cursor, or A and B
        // for a dragged section (Capacitor hides the cursor while selecting).
        chart.onInspect={p,_->if(chart.rangeEnds()==null)onProfilePoint(p.point)}
        chart.onRange={_->val ends=chart.rangeEnds();if(ends!=null){onProfilePoint(null);terrain.rangeMarkers(listOf(ends.first,ends.second))}else terrain.rangeMarkers(emptyList())}
        chart.onLeave={onProfilePoint(null);terrain.rangeMarkers(emptyList())}
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
            else onIdleTap(x,y)
        }
        terrain.onWaypointDragStart={controller.beginDrag(it)}
        terrain.onWaypointDrag={controller.previewDrag(it)}
        terrain.onWaypointDragEnd={if(it)controller.finishDrag()else controller.cancelDrag()}
        controller.observe{state,change->
            if(combiningPending&&change==RouteController.Change.OPENED){combineSelection.clear();combiningPending=false}else if(state.error!=null&&!state.busy)combiningPending=false
            if(!restoring&&state.loaded){restoring=true;val old=preferences.getString("active",null);if(old!=null&&state.records.any{it.id==old}){restoreId=old;main.post{controller.open(old)}}}
            sheet.show(state.drawing||state.points.isNotEmpty()||state.id!=null)
            terrain.routeEditing=state.drawing;onDrawing(state.drawing)
            if(title.text.toString()!=state.name&&!title.hasFocus()){changingTitle=true;title.setText(state.name);changingTitle=false}
            show(edit,if(state.drawing)"Done"else"Edit");edit.isEnabled=state.loaded&&!state.busy
            undo.isEnabled=state.drawing&&state.canUndo&&!state.busy;delete.isEnabled=state.drawing&&state.selected>=0&&!state.busy
            val saved=state.error?:when(state.saveState){RouteController.SaveState.SAVING->"Saving…";RouteController.SaveState.SAVED->"Saved";RouteController.SaveState.ERROR->"Not saved";else->"Tap the map to add points"}
            if(change!=RouteController.Change.PREVIEW&&state.points!==summaryPoints){summaryPoints=state.points;routeLength=NativeGeo.length(state.points)}
            show(summary,"${state.points.size} points · $saved");(if(sheet.expanded()&&state.drawing)View.VISIBLE else View.GONE).let{if(summary.visibility!=it)summary.visibility=it}
            if(state.id!=lastId){lastId=state.id;preferences.edit().apply{if(state.id==null)remove("active")else putString("active",state.id)}.apply()}
            show(stats[0],"Distance\n${formatDistance(routeLength)}")
            if(state.id!=null)passive[state.id]=state.points
            passive.keys.retainAll(state.records.map{it.id}.toSet()+state.opened)
            syncRoutes()
            if(change==RouteController.Change.OPENED&&state.points.isNotEmpty()){if(state.id!=restoreId){if(fitOpenedPending)fitOpened()else terrain.focus(state.points)};restoreId=null}
            if(state.dragging){if(change==RouteController.Change.PREVIEW){loader.cancelProfile();onProfilePoint(null);profileDirty=true}}
            else if(state.points!=profilePoints||profileDirty){
                profileDirty=false;profilePoints=state.points;profileValues=emptyList();chart.data(emptyList());onProfilePoint(null);terrain.rangeMarkers(emptyList())
                for(i in 1..3)stats[i].text=listOf("","Ascent","Descent","Highest")[i]+"\n—"
                chart.message=if(state.points.size>=2)"Loading elevation profile…"else"Elevation will appear here"
                loader.cancelProfile()
                if(state.points.size>=2)requestProfile(state.points,!state.drawing,true)
            }else if(!state.drawing&&!profileDetail&&state.points.size>=2){
                // Capacitor: drawing shows the quick preview; finishing refines it.
                requestProfile(state.points,true,false)
            }
            if(drawer!=null)renderDrawer()
        }
    }
    private fun focusBounds()=RouteProfile.focusBounds(terrain.cameraPose,(terrain.width/terrain.pixelScale).roundToInt(),(terrain.height/terrain.pixelScale).roundToInt())
    private fun requestProfile(points:List<Coordinate>,detail:Boolean,previewFirst:Boolean){
        val focus=if(detail)focusBounds()else null
        profileDetail=detail;profileFocus=focus?.stamp()?:"overview"
        loader.profile(points,detail,focus,previewFirst){values,_->
            if(closed||profilePoints!=points)return@profile
            profileValues=values;chart.data(values);val totals=NativeGeo.totals(values.map{it.height})
            val measured=values.count{it.height!=null}>=2
            stats[1].text="Ascent\n"+if(measured&&totals.complete)metres(totals.ascent)else"—";stats[2].text="Descent\n"+if(measured&&totals.complete)metres(totals.descent)else"—";stats[3].text="Highest\n${totals.high?.let(::metres)?:"—"}"
            if(values.count{it.height!=null}<2)chart.message="Elevation unavailable";chart.refreshInspection()
        }
    }
    /** Camera moved: once it settles, refine the in-view part of the route at
     * 2 m spacing (Capacitor re-samples when its zoom-15+ view bounds change). */
    fun cameraChanged(){main.removeCallbacks(settle);main.postDelayed(settle,400)}
    private fun cameraSettled(){
        if(closed)return
        if(terrain.gestureInProgress){main.postDelayed(settle,400);return}
        val state=controller.state
        if(state.drawing||state.dragging||state.points.size<2||state.points!=profilePoints||!profileDetail)return
        val stamp=focusBounds()?.stamp()?:"overview"
        if(stamp!=profileFocus)requestProfile(state.points,true,false)
    }
    fun newRoute(){dismissDrawer();controller.newRoute()}
    fun dismissDrawer():Boolean{val view=drawer?:return false;root.removeView(view);drawer=null;drawerList=null;searchResults=null;routesContent=emptyList();main.removeCallbacks(searchRun);drawerStamp=null;drawerCards.clear();combineButton=null;closeAllButton=null;return true}
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
        paths.add(Triple(state.points,Color.rgb(232,121,68),state.selected))
        for(key in state.opened)if(key!=state.id){
            val points=passive[key]
            if(points!=null)paths.add(Triple(points,Color.argb(230,81,137,153),-1))
            else if(loading.add(key))controller.loadOther(key){route->loading.remove(key);if(!closed&&route!=null&&key in controller.state.opened){passive[key]=route.points;syncRoutes();if(fitOpenedPending)fitOpened()}}
        }
        trackLine?.let{paths.add(Triple(it,if(trackPart!=null)trackDim else trackColor,-1))}
        trackPart?.let{paths.add(Triple(it,trackColor,-1))}
        if(paths!=lastPaths){lastPaths=paths;terrain.routes(paths)}
    }
    /** A tapped DOC track drawn over the map while its summary is open. */
    private var trackLine:List<Coordinate>?=null
    private val trackColor=Color.rgb(255,201,74)
    private var trackPart:List<Coordinate>?=null
    private val trackDim=Color.argb(150,255,214,120)
    /** [points] highlighted; [part] (a section of it) brighter, the rest dimmed. */
    fun highlightTrack(points:List<Coordinate>?,part:List<Coordinate>?=null){
        val p=part?.takeIf{it.size>=2};if(trackLine==points&&trackPart==p)return
        trackLine=points?.takeIf{it.size>=2};trackPart=p;syncRoutes()}
    /** Sizes the map for the route sheet again (after the track sheet closes). */
    fun relayout()=sheet.refresh()
    fun showRoutes(){
        if(drawer!=null){dismissDrawer();return}
        val overlay=FrameLayout(activity);overlay.addView(View(activity).apply{setBackgroundColor(Color.argb(40,16,40,35));setOnClickListener{dismissDrawer()}},FrameLayout.LayoutParams(-1,-1))
        // Compact floating card, as Capacitor's mobile sidebar.
        val sidebar=NativeUi.column(activity).apply{setPadding(dp(14),dp(4),dp(14),dp(10));background=NativeUi.background(activity,NativeUi.paper,14,true);elevation=dp(12).toFloat();isClickable=true;clipToOutline=true}
        // Capacitor "Saved routes" sidebar.
        // Search (places, huts, tracks) above the saved routes, with the close button.
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(2))}
        val field=EditText(activity).apply{
            hint="Search places, huts, tracks";textSize=14f;setSingleLine();imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(NativeUi.ink);setHintTextColor(Color.rgb(132,142,136));setPadding(dp(12),0,dp(12),0)
            background=NativeUi.background(activity,Color.rgb(237,241,233),10,true);contentDescription="Search places, huts and tracks"
            setText(searchQuery);setOnEditorActionListener{_,_,_->hideKeyboard();true}
            addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(t:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(t:CharSequence?,a:Int,b:Int,c:Int){}
                override fun afterTextChanged(t:android.text.Editable?){searchQuery=t?.toString().orEmpty();main.removeCallbacks(searchRun);main.postDelayed(searchRun,120)}
            })
        }
        top.addView(field,LinearLayout.LayoutParams(0,dp(40),1f))
        top.addView(TextView(activity).apply{text="✕";textSize=18f;gravity=Gravity.CENTER;setTextColor(NativeUi.muted);contentDescription="Close saved routes";background=NativeUi.pressable(activity,null);isClickable=true;setOnClickListener{hideKeyboard();dismissDrawer()}},LinearLayout.LayoutParams(dp(40),dp(40)).apply{marginStart=dp(4)});sidebar.addView(top)
        val heading=NativeUi.text(activity,"Saved routes",17f).apply{setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(10),0,dp(6))};sidebar.addView(heading)
        val importButton=button("Import GPX"){dismissDrawer();importGpx()}.apply{textSize=13f};sidebar.addView(importButton,LinearLayout.LayoutParams(-1,dp(38)))
        val gap=View(activity);sidebar.addView(gap,LinearLayout.LayoutParams(-1,dp(6)))
        val createButton=button("＋  Create new"){newRoute()}.apply{textSize=13f};sidebar.addView(createButton,LinearLayout.LayoutParams(-1,dp(38)))
        drawerList=NativeUi.column(activity)
        val results=NativeUi.column(activity).apply{visibility=View.GONE};searchResults=results
        // Saved offline regions, under the saved routes.
        val regions=NativeUi.column(activity)
        sidebar.addView(ScrollView(activity).apply{addView(NativeUi.column(activity).apply{addView(results);addView(drawerList);addView(regions)})},LinearLayout.LayoutParams(-1,0,1f))
        savedRegions(regions){dismissDrawer()}
        val offlineButton=NativeUi.button(activity,"Download offline region",true){dismissDrawer();onOffline()}.apply{textSize=13f}
        sidebar.addView(offlineButton,LinearLayout.LayoutParams(-1,dp(40)).apply{topMargin=dp(8)})
        routesContent=listOf(heading,importButton,gap,createButton,drawerList!!,regions,offlineButton)
        loadSearch()
        overlay.addView(sidebar,FrameLayout.LayoutParams(minOf(dp(300),activity.resources.displayMetrics.widthPixels-dp(64)),-1,Gravity.START).apply{setMargins(dp(10),dp(10),0,dp(10))})
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1));drawer=overlay;drawerStamp=null;renderDrawer();if(searchQuery.isNotBlank())runSearch()
    }
    private fun renderDrawer(){
        val list=drawerList?:return;val state=controller.state
        combineSelection.keys.retainAll(state.records.map{it.id}.toSet())
        val stamp=listOf(state.records,state.id,state.opened,combineSelection.toMap())
        if(stamp==drawerStamp)return;drawerStamp=stamp
        if(list.childCount==0){NativeUi.space(list,8);combineButton=link("Combine selected"){if(combineSelection.size>=2){val order=combineSelection.map{it.key to it.value};dismissDrawer();combiningPending=true;controller.combine(order)}};list.addView(combineButton);closeAllButton=link("Close all routes"){controller.closeAll()};list.addView(closeAllButton)}
        val ids=state.records.map{it.id}.toSet()
        for(id in drawerCards.keys.toList())if(id !in ids){list.removeView(drawerCards.remove(id)!!.view)}
        updatingCards=true
        for((index,row)in state.records.withIndex()){
            val card=drawerCards.getOrPut(row.id){
                val view=NativeUi.column(activity).apply{setPadding(0,dp(10),0,dp(2))};val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
                val thumb=RouteThumbnailView(activity).apply{contentDescription="Route preview for ${row.name}";background=NativeUi.background(activity,android.graphics.Color.rgb(237,241,233),8,true)};heading.addView(thumb,LinearLayout.LayoutParams(dp(50),dp(42)))
                val details=NativeUi.column(activity)
                val check=CheckBox(activity).apply{textSize=13.5f;setPadding(dp(4),0,0,0);setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(NativeUi.ink);buttonTintList=android.content.res.ColorStateList.valueOf(NativeUi.ink);setOnCheckedChangeListener{_,checked->if(!updatingCards){if(checked)combineSelection[row.id]=false else combineSelection.remove(row.id);renderDrawer()}}};details.addView(check)
                val summary=NativeUi.text(activity,"",11.5f).apply{setTextColor(Color.rgb(120,132,124));setPadding(dp(2),0,0,0)};details.addView(summary)
                heading.addView(details,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(8)});view.addView(heading)
                val actions=LinearLayout(activity).apply{setPadding(0,dp(2),0,0)}
                // Opening a route closes the sidebar so the route is visible (Capacitor).
                val open=link("Open"){fitOpenedPending=true;controller.open(row.id);dismissDrawer()}
                val close=link("Close"){controller.closeOther(row.id)}
                // Capacitor order: Open/Editing, Close, Rename, Delete.
                for(action in listOf(open,close,link("Rename"){controller.state.records.firstOrNull{it.id==row.id}?.let(::renameRoute)},link("Delete"){controller.state.records.firstOrNull{it.id==row.id}?.let(::deleteRoute)})){action.textSize=13f;action.setPadding(0,0,dp(14),0);actions.addView(action,LinearLayout.LayoutParams(-2,dp(36)))}
                view.addView(actions);val order=link(""){combineSelection[row.id]=combineSelection[row.id]!=true;renderDrawer()};view.addView(order);NativeUi.divider(view)
                Card(view,check,thumb,summary,open,close,order)
            }
            if(list.getChildAt(index+1)!==card.view){(card.view.parent as? LinearLayout)?.removeView(card.view);list.addView(card.view,index+1)}
            if(card.check.text.toString()!=row.name)card.check.text=row.name;card.check.contentDescription="Select ${row.name} for combining";card.check.isChecked=row.id in combineSelection
            val summaryText="${formatDistance(row.lengthMeters)} · ${row.pointCount} waypoints";if(card.summary.text.toString()!=summaryText)card.summary.text=summaryText
            val openText=if(state.id==row.id)"Editing"else"Open";if(card.open.text.toString()!=openText)card.open.text=openText;card.close.visibility=if(row.id in state.opened)View.VISIBLE else View.GONE
            card.order.visibility=if(row.id in combineSelection)View.VISIBLE else View.GONE
            val orderText="Join ${combineSelection.keys.indexOf(row.id)+1} · Direction: "+if(combineSelection[row.id]==true)"reversed"else"original";if(card.order.text.toString()!=orderText)card.order.text=orderText
            passive[row.id]?.let{card.thumb.data(it)}?:run{if(loading.add(row.id))controller.loadOther(row.id){route->
                loading.remove(row.id);if(!closed&&route!=null){val points=if(controller.state.id==row.id)controller.state.points else route.points;passive[row.id]=points;drawerCards[row.id]?.thumb?.data(points);syncRoutes();if(fitOpenedPending)fitOpened()}
            }}
        }
        updatingCards=false
        // Like Capacitor, list tools appear only when they apply.
        combineButton?.apply{val label="Combine selected (${combineSelection.size})";if(text.toString()!=label)text=label;isEnabled=combineSelection.size>=2;alpha=if(isEnabled)1f else .45f;visibility=if(combineSelection.isEmpty())View.GONE else View.VISIBLE}
        closeAllButton?.visibility=if(state.opened.size>1)View.VISIBLE else View.GONE
    }
    private fun renameRoute(route:RouteSummary){val input=EditText(activity).apply{setSingleLine();setText(route.name);filters=arrayOf(android.text.InputFilter.LengthFilter(80))};AlertDialog.Builder(activity).setTitle("Rename route").setView(input).setPositiveButton("Save name"){_,_->try{controller.rename(route.id,input.text.toString())}catch(e:Exception){toast(e.message?:"Invalid name")}}.setNegativeButton("Cancel",null).show()}
    private fun deleteRoute(route:RouteSummary){AlertDialog.Builder(activity).setMessage("Delete ${route.name}?").setPositiveButton("Delete"){_,_->controller.delete(route.id)}.setNegativeButton("Cancel",null).show()}
    private fun importGpx(){activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/gpx+xml","application/xml","text/xml","application/octet-stream"))},4101)}
    private fun shareGpx(){
        val state=controller.state;if(state.points.size<2){toast("Add at least two waypoints first.");return}
        val name=state.name;val points=state.points.toList()
        files.execute{try{
            val folder=java.io.File(activity.cacheDir,"route-share").apply{mkdirs()}
            folder.listFiles()?.filter{it.name.matches(Regex("[a-f0-9-]{36}\\.gpx"))}?.sortedByDescending{it.lastModified()}?.drop(19)?.forEach{it.delete()}
            val file=java.io.File(folder,java.util.UUID.randomUUID().toString()+".gpx");file.writeBytes(GpxCodec.encode(name,points))
            val uri=android.net.Uri.Builder().scheme("content").authority(activity.packageName+".routes").appendPath("gpx").appendPath(file.name).appendQueryParameter("name",GpxCodec.exportName(name)).build()
            main.post{if(!closed)runCatching{activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/gpx+xml";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("GPX route",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share GPX"))}.onFailure{toast("No app is available to share GPX. Use Export GPX to save it.")}}
        }catch(_:Exception){main.post{if(!closed)toast("GPX could not be prepared for sharing.")}}}
    }
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
    fun pause(){controller.cancelDrag();controller.flush();loader.cancelProfile();profileDirty=true}
    fun resume(){if(profileDirty)controller.drawing(controller.state.drawing)}
    override fun close(){closed=true;main.removeCallbacks(settle);loader.cancelProfile();controller.close();files.shutdown()}
    private fun formatDistance(m:Double)=if(m>=1000)String.format(java.util.Locale.ROOT,"%.2f km",m/1000)else"${m.roundToInt()} m"
}
