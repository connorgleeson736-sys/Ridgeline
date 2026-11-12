package nz.ridgeline.nativeapp
import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.*

class OfflineUi(private val activity:Activity,private val root:FrameLayout,private val terrain:TerrainView,private val loader:TileLoader,attachLauncher:Boolean=true){
    private var downloading=false
    private var attaching=false
    private val attachRequest=4301
    private fun addImagery(){
        if(attaching){Toast.makeText(activity,"Imagery attachment is still being checked.",Toast.LENGTH_SHORT).show();return}
        val intent=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(android.content.Intent.CATEGORY_OPENABLE);type="*/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES,arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching{activity.startActivityForResult(intent,attachRequest)}.onFailure{Toast.makeText(activity,"No file picker is available.",Toast.LENGTH_LONG).show()}
    }
    fun result(request:Int,result:Int,data:android.content.Intent?){
        pdfResult(request,result,data)
        if(request!=attachRequest||result!=Activity.RESULT_OK||data==null)return
        val uris=if(data.clipData!=null)(0 until data.clipData!!.itemCount).map{data.clipData!!.getItemAt(it).uri}else listOfNotNull(data.data)
        val selected=uris.distinct().take(6);if(selected.isEmpty())return
        attaching=true
        val progress=TextView(activity).apply{setPadding(dp(16),dp(12),dp(16),dp(12))}
        val dialog=AlertDialog.Builder(activity).setTitle("Add imagery").setView(progress).create().apply{setCancelable(false);show()}
        var complete=0;val errors=ArrayList<String>()
        fun next(){
            if(complete==selected.size){attaching=false;dialog.dismiss();Toast.makeText(activity,if(errors.isEmpty())"Imagery attached · ${loader.overviews.list().size}/6 packs"else errors.distinct().joinToString("\n"),Toast.LENGTH_LONG).show();startRegion();return}
            progress.text="Checking ZIP ${complete+1}/${selected.size}…\nOriginal files stay at their selected location."
            val uri=selected[complete]
            try{activity.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){errors.add("Choose ZIPs stored in Files or Downloads so Ridgeline can retain access.");complete++;next();return}
            loader.attachOverview(uri){error->
                if(error!=null){errors.add(error);if(loader.overviews.list().none{it.uri==uri.toString()})runCatching{activity.contentResolver.releasePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
                complete++;next()
            }
        };next()
    }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density+.5f).toInt()
    private fun button(label:String,action:()->Unit)=Button(activity).apply{text=label;textSize=12f;setOnClickListener{action()}}
    init{if(attachLauncher)root.addView(button("Offline maps"){startRegion()},FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.START).apply{leftMargin=dp(12);topMargin=dp(114)})}
    /** Live loading diagnostics (copyable). */
    fun diagnostics(){
        val snapshot=TextView(activity).apply{setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
        val loading=AlertDialog.Builder(activity).setTitle("Loading diagnostics").setView(ScrollView(activity).apply{addView(snapshot)}).setNegativeButton("Close",null).setNeutralButton("Copy"){_,_->
            activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Ridgeline loading",snapshot.text))
        }.create()
        fun refresh(){if(loading.isShowing){snapshot.text=loader.loadingDiagnostics();snapshot.postDelayed({refresh()},1000)}}
        loading.show();refresh()
    }
    /** GPU texture mode picker; [changed] runs after a choice. */
    fun gpuTextures(changed:()->Unit){
        val choices=arrayOf("Automatic","Compatibility","Texture arrays")
        AlertDialog.Builder(activity).setTitle("GPU textures").setSingleChoiceItems(choices,loader.textureBackend.coerceIn(0,2)){picker,mode->loader.textureBackend(mode);picker.dismiss();changed()}.setNegativeButton("Cancel",null).show()
    }

    // ---- Offline: one bottom bar with two tabs. "Paint": paint the detail
    // you want onto the 2D or 3D map (one finger paints, two move it).
    // "Topo map": a page-shaped frame for NZ Topo regions and PDFs. ----
    /** Opened route and saved routes, for painting along a route. */
    var routes:RouteController?=null
    private var frame:RegionFrame?=null
    private var bar:View?=null
    private var pageSize="A4";private var landscape=false
    private var progressText=""
    private var progressView:TextView?=null
    private var progressRow:View?=null
    private val pageNames=RegionFrame.PAPER.keys.toList()+CUSTOM
    private val imageryBases=listOf(MapSettings.Base.LINZ,MapSettings.Base.SATELLITE)
    private val prefs=activity.getSharedPreferences("offline-paint",0)
    private var imageryChoice=prefs.getInt("imagery",0).coerceIn(0,1);private var includeTopo=prefs.getBoolean("overlays",true)
    private var regionName=""
    private var topoTab=false
    /** Map style before the Topo tab switched to NZ Topo in 2D; restored after. */
    private var styleBefore:MapSettings.State?=null
    private fun spinner(items:List<String>,selected:Int,changed:(Int)->Unit)=Spinner(activity).apply{
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,items);setSelection(selected)
        onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){changed(i)};override fun onNothingSelected(p:AdapterView<*>?){}}
    }
    private fun row(vararg views:View)=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;views.forEachIndexed{i,v->addView(v,LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)marginStart=dp(8)})}}
    private fun toggle(label:String,body:View):TextView{
        val header=TextView(activity).apply{text="$label ▸";textSize=13f;setTextColor(NativeUi.ink);setPadding(0,dp(8),0,dp(6));isClickable=true;background=NativeUi.pressable(activity,null)}
        body.visibility=View.GONE
        header.setOnClickListener{val open=body.visibility!=View.VISIBLE;body.visibility=if(open)View.VISIBLE else View.GONE;header.text=(if(open)"$label ▾" else "$label ▸")}
        return header
    }
    private fun bold(text:String)=NativeUi.text(activity,text,13f).apply{setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(8),0,dp(2))}
    private fun km(v:Double)=if(v<10)String.format(java.util.Locale.ROOT,"%.1f",v)else String.format(java.util.Locale.ROOT,"%.0f",v)
    /** Width and height of [b] in kilometres (at its middle latitude). */
    private fun extentKm(b:TileLoader.Bounds):Pair<Double,Double>{
        val lat=Math.toRadians((b.south+b.north)/2)
        return (b.east-b.west)*111.32*cos(lat) to (b.north-b.south)*110.57
    }
    /** Back closes the offline bar. */
    fun back():Boolean{if(bar==null)return false;closeBar();return true}
    private var tick:Runnable?=null
    private fun closeBar(){
        stopPainting();removeFrame()
        bar?.let{root.removeView(it)};bar=null;progressView=null;progressRow=null
        tick?.let{root.removeCallbacks(it)};tick=null
        restoreStyle();loader.coverageOverride=false
    }
    private fun restoreStyle(){styleBefore?.let{before->styleBefore=null;loader.mapSettings.change{it.copy(threeD=before.threeD)}}}
    private fun removeFrame(){frame?.let{root.removeView(it)};frame=null}
    /** Opens the offline bar. */
    fun startRegion(){
        closeBar()
        paintMode=false
        val content=NativeUi.column(activity)
        val paintPane=paintPane()
        val topoPane=NativeUi.column(activity)
        content.addView(paintPane);content.addView(topoPane)
        // Progress, shared by both tabs.
        val progress=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,0)}
        val progressLabel=NativeUi.text(activity,"",12.5f);progressView=progressLabel
        progress.addView(progressLabel,LinearLayout.LayoutParams(0,-2,1f));progress.addView(button("Stop"){loader.cancelDownload()},LinearLayout.LayoutParams(-2,dp(40)))
        progressRow=progress;content.addView(progress);showProgress()
        // Saved regions.
        val saved=NativeUi.column(activity)
        val savedHeader=toggle("Saved regions",saved)
        refreshRegions={loader.regions{rows->if(bar==null)return@regions
            savedHeader.text=(if(saved.visibility==View.VISIBLE)"Saved regions (${rows.size}) ▾" else "Saved regions (${rows.size}) ▸")
            saved.removeAllViews()
            if(rows.isEmpty())saved.addView(NativeUi.text(activity,"None yet",12.5f).apply{setTextColor(NativeUi.muted)})
            for(region in rows)saved.addView(regionRow(region))
        }}
        savedHeader.setOnClickListener{val open=saved.visibility!=View.VISIBLE;saved.visibility=if(open)View.VISIBLE else View.GONE;refreshRegions()}
        content.addView(savedHeader);content.addView(saved)
        content.addView(toggle("Advanced",advancedPane()))
        content.addView(advancedView!!)
        // Tabs in the heading.
        val tabs=LinearLayout(activity).apply{background=NativeUi.background(activity,android.graphics.Color.rgb(232,236,229),9);setPadding(dp(3),dp(3),dp(3),dp(3))}
        val tabViews=listOf("Paint","Region").map{label->TextView(activity).apply{text=label;textSize=13f;gravity=Gravity.CENTER;isClickable=true;setPadding(dp(14),0,dp(14),0)}}
        fun select(topo:Boolean){
            topoTab=topo
            for((i,v)in tabViews.withIndex()){val on=(i==1)==topo
                v.setTextColor(if(on)android.graphics.Color.WHITE else NativeUi.ink);v.setTypeface(null,if(on)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.background=if(on)NativeUi.background(activity,NativeUi.ink,7)else NativeUi.pressable(activity,null)}
            paintPane.visibility=if(topo)View.GONE else View.VISIBLE;topoPane.visibility=if(topo)View.VISIBLE else View.GONE
            if(topo){stopPainting();openTopo(topoPane)}else{removeFrame();restoreStyle();topoPane.removeAllViews();startPainting()}
        }
        for((i,v)in tabViews.withIndex()){v.setOnClickListener{if((i==1)!=topoTab)select(i==1)};tabs.addView(v,LinearLayout.LayoutParams(-2,dp(32)))}
        val panel=bottomPanel(tabs,"Close offline maps",.45f,{closeBar()},content)
        root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));bar=panel
        loader.coverageOverride=true
        panel.addOnLayoutChangeListener{_,_,top,_,bottom,_,_,_,_->val h=bottom-top;frame?.let{f->if(h>0&&f.bottomInset!=h){f.bottomInset=h;if(pageSize==CUSTOM)f.custom()else f.page(pageSize,landscape);refreshTopoInfo()}}}
        select(topoTab)
        val poll=object:Runnable{override fun run(){if(bar!==panel)return;if(topoTab)refreshTopoInfo()else refreshOverlay(false);root.postDelayed(this,300)}}
        tick=poll;root.postDelayed(poll,300)
        refreshRegions()
        loader.storedCoverage{stored=it;refreshOverlay(true)}
    }
    private var refreshRegions:()->Unit={}
    private fun regionRow(region:OfflineStore.Region):View{
        val item=NativeUi.column(activity).apply{setPadding(0,dp(4),0,dp(4))}
        item.addView(NativeUi.text(activity,region.name,14f).apply{setTypeface(null,android.graphics.Typeface.BOLD)})
        item.addView(NativeUi.text(activity,String.format(java.util.Locale.ROOT,"%s · %.1f MB",region.status,region.bytes/1048576.0),12f).apply{setTextColor(NativeUi.muted)})
        val buttons=LinearLayout(activity)
        val meta=region.metadata
        if(meta!=null)buttons.addView(button("View"){closeBar();val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ);if(base==MapSettings.Base.TOPO)loader.mapSettings.change{it.copy(base=base)};terrain.focus(listOf(Coordinate(meta.bounds.west,meta.bounds.south),Coordinate(meta.bounds.east,meta.bounds.north)))})
        if(meta!=null&&!downloading&&region.status.startsWith("Partial"))buttons.addView(button("Resume"){
            val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ)
            val cells=meta.cells?.let{PaintedArea.decode(it)}
            if(meta.cells!=null&&cells==null){Toast.makeText(activity,"This region can't be resumed.",Toast.LENGTH_LONG).show();return@button}
            downloading=true;progressText="Resuming…";showProgress()
            // The region's own choices, not the panel's current ones.
            runCatching{loader.downloadRegion(region.name,meta.bounds,meta.zoom,base,meta.overlays,resume=region.id,painted=cells,progress={progressText=it;showProgress()}){downloadDone()}}
                .onFailure{downloading=false;showProgress();Toast.makeText(activity,it.message?:"Could not resume.",Toast.LENGTH_LONG).show()}
        })
        buttons.addView(button("Remove"){AlertDialog.Builder(activity).setMessage("Delete ${region.name} from this device?").setPositiveButton("Delete"){_,_->loader.removeRegion(region.id){refreshRegions();loader.storedCoverage{stored=it;refreshOverlay(true)}}}.setNegativeButton("Cancel",null).show()})
        item.addView(buttons);return item
    }
    private fun downloadDone(){downloading=false;progressText="";showProgress();loader.cameraChanged();refreshRegions();loader.storedCoverage{stored=it;refreshOverlay(true)}}
    private var advancedView:View?=null
    private fun advancedPane():View{
        val packs=NativeUi.column(activity)
        fun showPacks(){packs.removeAllViews()
            packs.addView(NativeUi.text(activity,"Overview imagery: ${loader.overviews.list().size}/6 packs",12.5f).apply{setTextColor(NativeUi.muted)})
            for(pack in loader.overviews.list())packs.addView(button("Detach ${pack.name}"){loader.detachOverview(pack.payload){showPacks()}},LinearLayout.LayoutParams(-2,dp(40)))
            packs.addView(button("Add imagery ZIPs"){closeBar();addImagery()},LinearLayout.LayoutParams(-2,dp(40)))}
        showPacks()
        val view=NativeUi.column(activity).apply{
            addView(bold("Painted downloads"))
            addView(spinner(listOf("LINZ aerial imagery","Satellite imagery"),imageryChoice){imageryChoice=it;prefs.edit().putInt("imagery",it).apply();scheduleEstimate()},LinearLayout.LayoutParams(-1,-2))
            addView(CheckBox(activity).apply{text="Include NZ Topo & overlays";isChecked=includeTopo;textSize=13f;setOnCheckedChangeListener{_,c->includeTopo=c;prefs.edit().putBoolean("overlays",c).apply();scheduleEstimate()}})
            addView(CheckBox(activity).apply{text="Use downloaded maps only";isChecked=loader.offlineOnly;textSize=13f;setOnCheckedChangeListener{_,c->loader.offlineOnly(c)}})
            addView(bold("Included imagery"));addView(packs)
        }
        advancedView=view;return view
    }
    private fun showProgress(){progressRow?.visibility=if(downloading)View.VISIBLE else View.GONE;progressView?.text=if(downloading)"Downloading · $progressText" else ""}
    /** Bottom bar in the route bar's style: paper, rounded top, [heading] and ✕,
     * scrolling content up to [maxFraction] of the screen. */
    private fun bottomPanel(heading:View,closeLabel:String,maxFraction:Float,close:()->Unit,content:LinearLayout):LinearLayout{
        val panel=NativeUi.column(activity).apply{
            setPadding(dp(16),dp(8),dp(12),dp(10));isClickable=true;elevation=dp(12).toFloat()
            background=android.graphics.drawable.GradientDrawable().apply{setColor(NativeUi.paper);val r=dp(20).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
        }
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(heading,LinearLayout.LayoutParams(-2,-2));top.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
        top.addView(TextView(activity).apply{text="✕";textSize=18f;gravity=Gravity.CENTER;setTextColor(NativeUi.muted);contentDescription=closeLabel;background=NativeUi.pressable(activity,null);isClickable=true;setOnClickListener{close()}},LinearLayout.LayoutParams(dp(44),dp(40)))
        panel.addView(top,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(6)})
        val limit={((root.height-root.paddingTop-root.paddingBottom).takeIf{it>0}?:activity.resources.displayMetrics.heightPixels)*maxFraction}
        panel.addView(object:ScrollView(activity){override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec(limit().toInt(),MeasureSpec.AT_MOST))}}.apply{addView(content)},LinearLayout.LayoutParams(-1,-2))
        return panel
    }

    // ---- Paint tab ----
    private val area=PaintedArea().also{a->prefs.getString("cells",null)?.let{PaintedArea.decode(it)}?.let{a.replace(it)}}
    private var brush=prefs.getInt("brush",2).coerceIn(0,3)
    private var erasing=false
    private var stored:CacheCoverage?=null
    private var info:TextView?=null
    private var undoButton:Button?=null
    private var swatches:List<View> = emptyList()
    private var eraseChip:TextView?=null
    private var cursor:BrushCursor?=null
    private val brushDp=26f
    /** Move: one finger moves the map as usual. Paint: one finger paints.
     * Two fingers always move the map. Choosing a colour or Erase switches to
     * Paint; the bar opens in Move. */
    private var paintMode=false
    private var paintTabOn=false
    private var modeViews:List<TextView> = emptyList()
    private fun setPaintMode(on:Boolean){
        if(paintMode&&!on)finishStroke(true)
        paintMode=on
        terrain.paintTouch=if(on&&paintTabOn){phase,x,y->paintTouch(phase,x,y)}else null
        for((i,v)in modeViews.withIndex()){val active=(i==1)==on
            v.setTextColor(if(active)android.graphics.Color.WHITE else NativeUi.ink);v.setTypeface(null,if(active)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            v.background=if(active)NativeUi.background(activity,NativeUi.ink,7)else NativeUi.pressable(activity,null)}
        showBrush();updatePaintInfo()
    }
    private fun paintPane():LinearLayout{
        val pane=NativeUi.column(activity)
        // Move / Paint switch.
        val modes=LinearLayout(activity).apply{background=NativeUi.background(activity,android.graphics.Color.rgb(232,236,229),9);setPadding(dp(3),dp(3),dp(3),dp(3))}
        modeViews=listOf("✋  Move" to "Move the map with one finger","🖌  Paint" to "Paint with one finger").mapIndexed{i,(label,description)->TextView(activity).apply{
            text=label;textSize=13f;gravity=Gravity.CENTER;isClickable=true;contentDescription=description;setOnClickListener{setPaintMode(i==1)}}}
        for(v in modeViews)modes.addView(v,LinearLayout.LayoutParams(0,dp(34),1f))
        pane.addView(modes,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        // Brush colours (detail) and the eraser.
        val chips=LinearLayout(activity)
        swatches=PaintedArea.NAMES.indices.map{i->swatch(i).also{v->v.setOnClickListener{brush=i;erasing=false;prefs.edit().putInt("brush",i).apply();setPaintMode(true)}}}
        for((i,v)in swatches.withIndex())chips.addView(v,LinearLayout.LayoutParams(0,dp(52),1f).apply{if(i>0)marginStart=dp(4)})
        val eraser=TextView(activity).apply{text="Erase";textSize=12f;gravity=Gravity.CENTER;isClickable=true;setTextColor(NativeUi.ink);setOnClickListener{erasing=true;setPaintMode(true)};contentDescription="Eraser"}
        eraseChip=eraser
        chips.addView(eraser,LinearLayout.LayoutParams(0,dp(52),1f).apply{marginStart=dp(4)})
        pane.addView(chips)
        // Tools.
        val undo=NativeUi.button(activity,"Undo"){if(area.undo())areaChanged(true)};undoButton=undo
        val clear=NativeUi.button(activity,"Clear"){if(!area.isEmpty())AlertDialog.Builder(activity).setMessage("Clear the painted area?").setPositiveButton("Clear"){_,_->area.clear();areaChanged(true)}.setNegativeButton("Cancel",null).show()}
        val route=NativeUi.button(activity,"Along route"){paintRoute()}
        pane.addView(LinearLayout(activity).apply{setPadding(0,dp(6),0,0)
            for((i,v)in listOf(undo,clear,route).withIndex())addView(v.apply{minimumHeight=0;minHeight=0},LinearLayout.LayoutParams(0,dp(36),if(i==2)1.4f else 1f).apply{if(i>0)marginStart=dp(8)})})
        val line=NativeUi.text(activity,"",12.5f).apply{setPadding(0,dp(6),0,dp(6))};info=line
        pane.addView(line)
        val name=EditText(activity).apply{setSingleLine();hint="Name (optional)";setText(regionName);textSize=14f;filters=arrayOf(android.text.InputFilter.LengthFilter(60))}
        val download=NativeUi.button(activity,"Download",true){downloadPainted(name.text.toString())}
        pane.addView(LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL
            addView(name,LinearLayout.LayoutParams(0,-2,1f));addView(download,LinearLayout.LayoutParams(dp(116),dp(44)).apply{marginStart=dp(8)})})
        setPaintMode(false)
        return pane
    }
    /** A detail colour: dot and name; outlined when it is the brush. */
    private fun swatch(i:Int)=LinearLayout(activity).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;contentDescription="Paint ${PaintedArea.NAMES[i]} detail"
        addView(View(activity).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(PaintedArea.COLORS[i])}},LinearLayout.LayoutParams(dp(18),dp(18)))
        addView(NativeUi.text(activity,PaintedArea.NAMES[i],11f).apply{gravity=Gravity.CENTER;setPadding(0,dp(3),0,0)})
    }
    private fun showBrush(){
        // The brush is outlined while painting; in Move it is only marked.
        for((i,v)in swatches.withIndex()){v.background=chipBackground(!erasing&&i==brush,paintMode);v.alpha=if(paintMode||(!erasing&&i==brush))1f else .75f}
        eraseChip?.background=chipBackground(erasing,paintMode)
        cursor?.color=if(erasing)android.graphics.Color.WHITE else PaintedArea.COLORS[brush]
    }
    private fun chipBackground(selected:Boolean,strong:Boolean=true)=android.graphics.drawable.GradientDrawable().apply{
        cornerRadius=dp(9).toFloat();setColor(if(selected)android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
        setStroke(dp(if(selected&&strong)2 else 1),if(selected)(if(strong)NativeUi.ink else NativeUi.muted)else NativeUi.line)
    }
    private fun startPainting(){
        if(cursor==null){val c=BrushCursor(activity);cursor=c;root.addView(c,root.indexOfChild(terrain)+1,FrameLayout.LayoutParams(-1,-1));showBrush()}
        paintTabOn=true;setPaintMode(paintMode)
        refreshOverlay(true)
    }
    private fun stopPainting(){
        paintTabOn=false;terrain.paintTouch=null;finishStroke(false)
        cursor?.let{root.removeView(it)};cursor=null
        terrain.coverage(null,null);overlayArea=null
    }
    // Strokes: ground points come back from the renderer asynchronously, in order.
    private var stroke=0L;private var pending=0;private var closing:Boolean?=null;private var strokeActive=false
    private var strokeStart=0L;private var strokeTravel=0f;private var lastX=0f;private var lastY=0f
    private var lastPickX=0f;private var lastPickY=0f;private var lastGround:DoubleArray?=null
    private fun paintTouch(phase:Int,x:Float,y:Float){
        when(phase){
            TerrainView.PAINT_START->{if(strokeActive){area.end();strokeActive=false};stroke++;area.begin();strokeActive=true;pending=0;closing=null;lastGround=null
                strokeStart=android.os.SystemClock.uptimeMillis();strokeTravel=0f;lastX=x;lastY=y;pick(x,y);cursor?.show(x,y,brushDp*activity.resources.displayMetrics.density)}
            TerrainView.PAINT_MOVE->{strokeTravel+=hypot(x-lastX,y-lastY);lastX=x;lastY=y;cursor?.show(x,y,brushDp*activity.resources.displayMetrics.density)
                if(hypot(x-lastPickX,y-lastPickY)>=dp(6))pick(x,y)}
            TerrainView.PAINT_END->{cursor?.hide();pick(x,y);finishStroke(true)}
            TerrainView.PAINT_CANCEL->{cursor?.hide()
                // A second finger soon after the first: a map gesture, not a stroke.
                val brief=android.os.SystemClock.uptimeMillis()-strokeStart<350||strokeTravel<dp(24)
                finishStroke(!brief)}
        }
    }
    private fun pick(x:Float,y:Float){
        lastPickX=x;lastPickY=y;val id=stroke;pending++
        val pose=terrain.cameraPose
        // Fixed on screen: the brush covers more ground when zoomed out.
        val radius=brushDp/(512.0*2.0.pow(pose.zoom))
        terrain.pick(x.toDouble(),y.toDouble()){hit->
            if(id!=stroke)return@pick
            pending--
            if(hit!=null&&closing!=false){
                val mx=(hit[0]+180)/360;val my=CameraMath.mercatorY(hit[1]);val detail=if(erasing)-1 else brush
                val last=lastGround
                if(last==null)area.dab(mx,my,radius,detail)else area.line(last[0],last[1],mx,my,radius,detail)
                lastGround=doubleArrayOf(mx,my);areaChanged(false)
            }
            if(pending==0)closing?.let{keep->closeStroke(keep)}
        }
    }
    /** Ends the current stroke once its pending ground points have arrived. */
    private fun finishStroke(keep:Boolean){if(!strokeActive||closing!=null)return;closing=keep;if(pending==0)closeStroke(keep)}
    private fun closeStroke(keep:Boolean){if(keep)area.end()else area.cancel();strokeActive=false;stroke++;pending=0;closing=null;areaChanged(true)}
    /** [settled]: a stroke ended; save the draft and re-estimate. */
    private fun areaChanged(settled:Boolean){
        refreshOverlay(true);undoButton?.isEnabled=area.canUndo()
        if(settled){scheduleEstimate();saveDraft()}
    }
    private val draftWriter=java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun saveDraft(){val revision=area.revision;root.removeCallbacks(draftRun);root.postDelayed(draftRun,800);draftRevision=revision}
    private var draftRevision=-1L
    private val draftRun=Runnable{val cells=area.snapshot();runCatching{draftWriter.execute{prefs.edit().putString("cells",if(cells.isEmpty())null else PaintedArea.encode(cells)).apply()}}}
    // Live estimate, off the UI thread.
    private val estimator=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var estimate:TileLoader.RegionEstimate?=null;private var estimateTiles=0;private var estimateRevision=-1L
    private fun scheduleEstimate(){
        val revision=area.revision;val cells=area.snapshot();val base=imageryBases[imageryChoice];val topo=includeTopo
        runCatching{estimator.execute{
            val tiles=PaintedArea.tilesOf(cells)
            val e=if(tiles.size<=TileLoader.MAX_PAINTED_TILES)loader.estimateTiles(tiles,base,topo)else null
            root.post{if(area.revision==revision){estimate=e;estimateTiles=tiles.size;estimateRevision=revision;updatePaintInfo()}}
        }}
    }
    private fun updatePaintInfo(){
        val line=info?:return
        line.setTextColor(NativeUi.ink)
        line.text=when{
            area.isEmpty()->if(paintMode)"One finger paints · two fingers move the map" else "Choose a detail colour, then paint the area you want"
            estimateRevision!=area.revision&&estimate==null->"Estimating…"
            estimateTiles>TileLoader.MAX_PAINTED_TILES->{line.setTextColor(NativeUi.orange);"Too large: paint a smaller area or a lower detail"}
            else->estimate?.let{String.format(java.util.Locale.ROOT,"About %.0f MB · %,d files",it.mib,it.files)}?:""
        }
        undoButton?.isEnabled=area.canUndo()
    }
    private fun downloadPainted(name:String){
        if(downloading){Toast.makeText(activity,"A region is already downloading.",Toast.LENGTH_SHORT).show();return}
        val cells=area.snapshot();val bounds=PaintedArea.boundsOf(cells)
        if(bounds==null){Toast.makeText(activity,"Paint the area you want first.",Toast.LENGTH_SHORT).show();return}
        regionName=name
        val title=name.ifBlank{"Painted area "+java.text.SimpleDateFormat("d MMM",java.util.Locale.getDefault()).format(java.util.Date())}
        try{
            downloading=true;progressText="Preparing…";showProgress()
            loader.downloadRegion(title,bounds,cells.values.maxOf{PaintedArea.DETAILS[it]},imageryBases[imageryChoice],includeTopo,painted=cells,progress={progressText=it;showProgress()}){downloadDone()}
            // The painting is now a saved region: clear it (Undo brings it back).
            area.clear();areaChanged(true)
        }catch(e:Exception){downloading=false;showProgress();info?.let{it.setTextColor(NativeUi.orange);it.text=e.message?:"Could not start the download."}}
    }
    private fun paintRoute(){
        val controller=routes
        val open=controller?.state?.takeIf{it.points.size>=2}
        val records=controller?.state?.records.orEmpty()
        if(open==null&&records.isEmpty()){Toast.makeText(activity,"No routes yet. Draw or import a route first.",Toast.LENGTH_LONG).show();return}
        val labels=ArrayList<String>();val actions=ArrayList<()->Unit>()
        fun along(points:List<Coordinate>,label:String){area.corridor(points,ROUTE_HALF_WIDTH,if(erasing)-1 else brush);areaChanged(true)
            Toast.makeText(activity,(if(erasing)"Erased along " else "Painted along ")+label,Toast.LENGTH_SHORT).show()
            if(points.isNotEmpty())terrain.focus(points,15.0)}
        if(open!=null){labels+="Open route · ${open.name}";actions+={along(open.points,open.name)}}
        for(r in records)if(r.id!=open?.id){labels+=r.name;actions+={controller!!.loadOther(r.id){route->if(route!=null)along(route.points,route.summary.name)else Toast.makeText(activity,"Could not open that route.",Toast.LENGTH_SHORT).show()}}}
        AlertDialog.Builder(activity).setTitle(if(erasing)"Erase along a route" else "Paint along a route · ${PaintedArea.NAMES[brush]}").setItems(labels.toTypedArray()){_,i->actions[i]()}.setNegativeButton("Cancel",null).show()
    }
    // Terrain tint: painted cells in their colour, stored areas faintly.
    private val overlayWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var overlayArea:DoubleArray?=null;private var overlayRevision=-1L;private var overlayBusy=false;private var overlayAgain=false
    private fun refreshOverlay(force:Boolean){
        if(!paintTabOn)return
        val pose=terrain.planningPose;val cx=(pose.longitude+180)/360;val cy=CameraMath.mercatorY(pose.latitude);val half=4.0/2.0.pow(pose.zoom)
        val a=overlayArea
        val moved=a==null||abs(a[0]-cx)>a[2]/4||abs(a[1]-cy)>a[2]/4||abs(ln(a[2]/half)/ln(2.0))>=.5
        if(!force&&!moved&&overlayRevision==area.revision)return
        if(overlayBusy){overlayAgain=true;return}
        overlayBusy=true;val revision=area.revision;val known=stored
        runCatching{overlayWorker.execute{
            val n=CacheCoverage.SIZE;val out=ByteArray(n*n*4);val grid=PaintedArea.GRID.toDouble()
            for(row in 0 until n){val my=cy-half+(row+.5)/n*2*half
                for(col in 0 until n){val mx=cx-half+(col+.5)/n*2*half
                    val x=floor(mx*grid).toInt();val y=floor(my*grid).toInt()
                    val painted=if(x in 0 until PaintedArea.GRID&&y in 0 until PaintedArea.GRID)area.detailAt(PaintedArea.key(x,y))else -1
                    val (color,alpha)=if(painted>=0)PaintedArea.COLORS[painted] to 150 else{
                        val s=known?.level(mx,my)?.let{PaintedArea.detailForZoom(it)}?:-1
                        if(s>=0)PaintedArea.COLORS[s] to 46 else 0 to 0}
                    val i=(row*n+col)*4
                    out[i]=(color shr 16 and 255).toByte();out[i+1]=(color shr 8 and 255).toByte();out[i+2]=(color and 255).toByte();out[i+3]=alpha.toByte()
                }}
            root.post{
                overlayBusy=false
                if(paintTabOn){terrain.coverage(out,doubleArrayOf(cx-half,cy-half,cx+half,cy+half));overlayArea=doubleArrayOf(cx,cy,half);overlayRevision=revision}
                if(overlayAgain){overlayAgain=false;refreshOverlay(true)}
            }
        }}.onFailure{overlayBusy=false}
    }

    // ---- Topo map tab: a page-shaped (or custom) frame for NZ Topo regions
    // and Save as PDF. ----
    private var topoInfo:TextView?=null
    private var refreshTopoInfo:()->Unit={}
    private val zooms=listOf(13,14,15,16,17)
    private val zoomNames=listOf("Zoom 13 · overview","Zoom 14 · standard","Zoom 15 · detailed","Zoom 16 · very detailed","Zoom 17 · maximum")
    private val regionBases=listOf(MapSettings.Base.LINZ,MapSettings.Base.SATELLITE,MapSettings.Base.TOPO)
    private var zoomChoice=prefs.getInt("regionZoom",2).coerceIn(0,4);private var baseChoice=prefs.getInt("regionBase",0).coerceIn(0,2);private var regionOverlays=prefs.getBoolean("regionOverlays",true)
    /** The 0.1.36 region download: a page-shaped (or custom) frame over the 2D
     * north-up map; map content and detail; Save as PDF and Download. */
    private fun openTopo(pane:LinearLayout){
        pane.removeAllViews()
        val state=loader.mapSettings.state
        if(styleBefore==null&&state.threeD)styleBefore=state
        if(state.threeD)loader.mapSettings.change{it.copy(threeD=false)}
        terrain.northUp()
        terrain.coverage(null,null)
        val overlay=RegionFrame(activity);frame=overlay
        root.addView(overlay,root.indexOfChild(terrain)+1,FrameLayout.LayoutParams(-1,-1))
        val orientation=spinner(listOf("Portrait","Landscape"),if(landscape)1 else 0){landscape=it==1;if(pageSize!=CUSTOM)overlay.page(pageSize,landscape)}
        fun applyPage(){
            if(pageSize==CUSTOM)overlay.custom()else overlay.page(pageSize,landscape)
            orientation.isEnabled=pageSize!=CUSTOM;orientation.alpha=if(pageSize==CUSTOM).4f else 1f
        }
        pane.addView(row(spinner(pageNames,pageNames.indexOf(pageSize).coerceAtLeast(0)){pageSize=pageNames[it];applyPage()},orientation))
        val name=EditText(activity).apply{setSingleLine();hint="Region name";setText(regionName);textSize=14f;filters=arrayOf(android.text.InputFilter.LengthFilter(60))}
        pane.addView(name,LinearLayout.LayoutParams(-1,-2))
        val detail=spinner(zoomNames,zoomChoice){zoomChoice=it;prefs.edit().putInt("regionZoom",it).apply();refreshTopoInfo()}
        val include=CheckBox(activity).apply{text="NZ Topo & overlays";isChecked=regionOverlays;textSize=13f;setOnCheckedChangeListener{_,c->regionOverlays=c;prefs.edit().putBoolean("regionOverlays",c).apply();refreshTopoInfo()}}
        fun topoOnly(){val topo=regionBases[baseChoice]==MapSettings.Base.TOPO;detail.visibility=if(topo)View.INVISIBLE else View.VISIBLE;include.visibility=if(topo)View.GONE else View.VISIBLE}
        pane.addView(row(spinner(listOf("LINZ aerial","Satellite","NZ Topo"),baseChoice){baseChoice=it;prefs.edit().putInt("regionBase",it).apply();topoOnly();refreshTopoInfo()},detail))
        pane.addView(include);topoOnly()
        val line=NativeUi.text(activity,"",12.5f).apply{setPadding(0,dp(4),0,dp(4))};topoInfo=line
        pane.addView(line)
        // Exact coordinates, folded away.
        val coords=listOf("West","South","East","North").map{EditText(activity).apply{hint=it;textSize=13f;inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}}
        var coordsEdited=false
        fun showCoords(b:TileLoader.Bounds){listOf(b.west,b.south,b.east,b.north).forEachIndexed{i,v->coords[i].setText(String.format(java.util.Locale.ROOT,"%.5f",v))};coordsEdited=false}
        for(field in coords)field.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(a:CharSequence?,b:Int,c:Int,d:Int){};override fun onTextChanged(a:CharSequence?,b:Int,c:Int,d:Int){if(field.hasFocus())coordsEdited=true};override fun afterTextChanged(e:android.text.Editable?){if(field.hasFocus())refreshTopoInfo()}})
        val grid=GridLayout(activity).apply{columnCount=2}
        coords.forEachIndexed{i,field->grid.addView(field,GridLayout.LayoutParams().apply{width=0;columnSpec=GridLayout.spec(i%2,1f)})}
        fun bounds():TileLoader.Bounds{
            if(coordsEdited){
                val v=coords.map{it.text.toString().toDoubleOrNull()?:error("Enter valid coordinates.")}
                val b=TileLoader.Bounds(v[0],v[1],v[2],v[3]);require(b.west>=165&&b.east<=180&&b.south>=-49&&b.north<=-33&&b.west<b.east&&b.south<b.north){"Enter valid coordinates."};return b
            }
            return frameBounds()?:error("Move the frame over New Zealand.")
        }
        fun zoom()=if(regionBases[baseChoice]==MapSettings.Base.TOPO)TOPO_MAX_ZOOM else zooms[zoomChoice]
        var lastBounds:TileLoader.Bounds?=null
        refreshTopoInfo={
            line.setTextColor(NativeUi.ink)
            line.text=try{
                val b=bounds();val (w,h)=extentKm(b)
                if(!coordsEdited&&b!=lastBounds)showCoords(b);lastBounds=b
                val e=loader.regionEstimate(b,zoom(),regionBases[baseChoice],regionOverlays)
                String.format(java.util.Locale.ROOT,"%s × %s km · about %.0f MB · %,d files",km(w),km(h),e.mib,e.files)
            }catch(e:Exception){line.setTextColor(NativeUi.orange);e.message?:"Choose a smaller area."}
        }
        overlay.onChange={refreshTopoInfo()}
        val pdf=button("Save as PDF"){
            regionName=name.text.toString()
            try{savePdf(bounds(),regionName.ifBlank{"Ridgeline map"})}catch(e:Exception){Toast.makeText(activity,e.message?:"Could not save the PDF.",Toast.LENGTH_LONG).show()}
        }
        val download=NativeUi.button(activity,"Download",true){
            if(downloading){Toast.makeText(activity,"A region is already downloading.",Toast.LENGTH_SHORT).show();return@button}
            try{
                val b=bounds();loader.regionEstimate(b,zoom(),regionBases[baseChoice],regionOverlays)
                regionName=name.text.toString();val title=regionName.ifBlank{"Region "+java.text.SimpleDateFormat("d MMM HH:mm",java.util.Locale.getDefault()).format(java.util.Date())}
                downloading=true;progressText="Preparing…";showProgress()
                loader.downloadRegion(title,b,zoom(),regionBases[baseChoice],regionOverlays,progress={progressText=it;showProgress()}){downloadDone()}
            }catch(e:Exception){downloading=false;showProgress();line.setTextColor(NativeUi.orange);line.text=e.message?:"Region could not be started."}
        }
        pane.addView(LinearLayout(activity).apply{setPadding(0,dp(6),0,0);addView(pdf,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)});addView(download,LinearLayout.LayoutParams(0,dp(44),1f))})
        pane.addView(toggle("Coordinates",grid));pane.addView(grid)
        overlay.post{bar?.height?.takeIf{it>0}?.let{overlay.bottomInset=it};if(pageSize==CUSTOM){overlay.page("A4",landscape);overlay.custom()}else overlay.page(pageSize,landscape);orientation.isEnabled=pageSize!=CUSTOM;orientation.alpha=if(pageSize==CUSTOM).4f else 1f;refreshTopoInfo()}
    }
    /** The frame's corners on the 2D map, clamped to New Zealand. */
    private fun frameBounds():TileLoader.Bounds?{
        val view=frame?:return null;val c=view.corners();val scale=terrain.pixelScale
        val pose=terrain.cameraPose;val w=(terrain.width/scale).roundToInt();val h=(terrain.height/scale).roundToInt()
        fun at(x:Float,y:Float)=CameraMath.anchor(pose,w,h,1.0,x/scale,y/scale,0.0)
        val sw=at(c[0],c[3])?:return null;val ne=at(c[2],c[1])?:return null
        val west=max(165.0,sw.mx*360-180);val east=min(180.0,ne.mx*360-180)
        val south=max(-49.0,CameraMath.latitude(sw.my));val north=min(-33.0,CameraMath.latitude(ne.my))
        return if(west<east&&south<north)TileLoader.Bounds(west,south,east,north)else null
    }
    /** The brush outline under the finger while painting (draws only; touches pass through). */
    private class BrushCursor(context:android.content.Context):View(context){
        private val ring=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{style=android.graphics.Paint.Style.STROKE;strokeWidth=2.5f*context.resources.displayMetrics.density}
        private val halo=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{style=android.graphics.Paint.Style.STROKE;color=0x99000000.toInt();strokeWidth=4.5f*context.resources.displayMetrics.density}
        var color=android.graphics.Color.WHITE;set(v){field=v;invalidate()}
        private var x=0f;private var y=0f;private var r=0f;private var on=false
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun show(x:Float,y:Float,r:Float){this.x=x;this.y=y;this.r=r;on=true;invalidate()}
        fun hide(){on=false;invalidate()}
        override fun onDraw(canvas:android.graphics.Canvas){if(!on)return;canvas.drawCircle(x,y,r,halo);ring.color=color;canvas.drawCircle(x,y,r,ring)}
        override fun onTouchEvent(event:android.view.MotionEvent)=false
    }

    // ---- Save as PDF: the framed area on the chosen page, from map tiles at print resolution. ----
    private val pdfRequest=4302
    private class PendingPdf(val map:android.graphics.Bitmap,val title:String,val paper:String,val landscape:Boolean,val widthKm:Double,val base:MapSettings.Base)
    private var pendingPdf:PendingPdf?=null
    private fun savePdf(bounds:TileLoader.Bounds,title:String){
        val paper=if(pageSize==CUSTOM)"A4" else pageSize
        val (widthKm,heightKm)=extentKm(bounds)
        val wide=if(pageSize==CUSTOM)widthKm>heightKm else landscape
        val base=loader.mapSettings.state.base
        // Plan at print resolution for the map area on the chosen page.
        val (pw,ph)=PdfLayout.pageSize(paper,wide)
        val aspect=(CameraMath.mercatorY(bounds.south)-CameraMath.mercatorY(bounds.north))/((bounds.east-bounds.west)/360)
        val area=min((pw-2*PdfLayout.SIDE).toDouble(),(ph-PdfLayout.TOP-PdfLayout.BOTTOM)/aspect)
        val plan=PrintMap.plan(bounds,area,loader.printMaxZoom(base))
        val route=routes?.state?.points.orEmpty()
        val cancelled=java.util.concurrent.atomic.AtomicBoolean()
        val status=TextView(activity).apply{setPadding(dp(20),dp(14),dp(20),dp(6));text="Preparing the map…"}
        val dialog=AlertDialog.Builder(activity).setTitle("Save as PDF").setView(status).setNegativeButton("Cancel"){_,_->cancelled.set(true)}.create().apply{setCancelable(false);show()}
        Thread{
            val map=runCatching{PrintMap.render(plan,{z,x,y->if(cancelled.get())null else loader.printTile(base,z,x,y)},route,(plan.width/area*2.2).toFloat()){i,n->
                if(i%4==0)activity.runOnUiThread{status.text=String.format(java.util.Locale.ROOT,"Preparing the map · %d%%",i*100/max(1,n))}
                !cancelled.get()}}.getOrNull()
            activity.runOnUiThread{
                dialog.dismiss()
                if(map==null){if(!cancelled.get())Toast.makeText(activity,"Could not prepare the map.",Toast.LENGTH_LONG).show();return@runOnUiThread}
                pendingPdf?.map?.recycle();pendingPdf=PendingPdf(map,title,paper,wide,widthKm,base)
                val intent=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(android.content.Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(android.content.Intent.EXTRA_TITLE,title.replace(Regex("[\\\\/:*?\"<>|]"),"_")+".pdf")}
                runCatching{activity.startActivityForResult(intent,pdfRequest)}.onFailure{Toast.makeText(activity,"No file picker is available.",Toast.LENGTH_LONG).show()}
            }
        }.apply{name="RidgelinePdf"}.start()
    }
    private fun writePdf(uri:android.net.Uri){
        val job=pendingPdf?:return;pendingPdf=null
        Thread{
            val ok=runCatching{
                val doc=PdfLayout.render(job.map,job.title,job.paper,job.landscape,job.widthKm,PdfLayout.attribution(job.base))
                try{activity.contentResolver.openOutputStream(uri)!!.use{doc.writeTo(it)}}finally{doc.close()}
            }.isSuccess
            job.map.recycle()
            activity.runOnUiThread{Toast.makeText(activity,if(ok)"PDF saved" else "Could not save the PDF.",Toast.LENGTH_SHORT).show()}
        }.start()
    }
    fun pdfResult(request:Int,result:Int,data:android.content.Intent?){
        if(request!=pdfRequest)return
        val uri=data?.data
        if(result!=Activity.RESULT_OK||uri==null){pendingPdf?.map?.recycle();pendingPdf=null;return}
        writePdf(uri)
    }
    private companion object{const val CUSTOM="Custom"
        /** Half the width of a corridor painted along a route. */
        const val ROUTE_HALF_WIDTH=500.0}
}
