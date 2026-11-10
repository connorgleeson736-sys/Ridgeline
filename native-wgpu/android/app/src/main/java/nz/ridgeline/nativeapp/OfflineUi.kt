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
        if(request!=attachRequest||result!=Activity.RESULT_OK||data==null)return
        val uris=if(data.clipData!=null)(0 until data.clipData!!.itemCount).map{data.clipData!!.getItemAt(it).uri}else listOfNotNull(data.data)
        val selected=uris.distinct().take(6);if(selected.isEmpty())return
        attaching=true
        val progress=TextView(activity).apply{setPadding(dp(16),dp(12),dp(16),dp(12))}
        val dialog=AlertDialog.Builder(activity).setTitle("Add imagery").setView(progress).create().apply{setCancelable(false);show()}
        var complete=0;val errors=ArrayList<String>()
        fun next(){
            if(complete==selected.size){attaching=false;dialog.dismiss();Toast.makeText(activity,if(errors.isEmpty())"Imagery attached · ${loader.overviews.list().size}/6 packs"else errors.distinct().joinToString("\n"),Toast.LENGTH_LONG).show();show();return}
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
    init{if(attachLauncher)root.addView(button("Offline / imagery"){show()},FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.START).apply{leftMargin=dp(12);topMargin=dp(114)})}
    fun show(){
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
        val list=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val status=TextView(activity)
        val dialog=AlertDialog.Builder(activity).setTitle("Offline maps").setView(ScrollView(activity).apply{addView(content)}).setNegativeButton("Close",null).create()
        for(geometry in listOf(false,true)){
            val name=if(geometry)"Terrain" else "Imagery"
            val label=TextView(activity).apply{textSize=13f}
            val slider=SeekBar(activity).apply{max=DetailPolicy.MAX;progress=if(geometry)loader.terrainDetail else loader.imageryDetail;contentDescription="$name detail"}
            fun update(){label.text="$name detail: "+String.format(java.util.Locale.ROOT,"%.1f×",DetailPolicy.multiplier(slider.progress))}
            update();content.addView(label);content.addView(slider)
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean){update();if(user)loader.detail(geometry,value)}
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        content.addView(TextView(activity).apply{text="Higher detail loads finer tiles and uses more memory and data. Changes apply immediately.";textSize=12f})
        content.addView(TextView(activity).apply{text="Download jobs";textSize=16f})
        val downloadSliders=LinkedHashMap<DownloadLimits.Mode,SeekBar>()
        for(mode in DownloadLimits.Mode.entries){
            val name=when(mode){DownloadLimits.Mode.MOVING->"While moving";DownloadLimits.Mode.STATIONARY->"While stationary";DownloadLimits.Mode.BACKGROUND->"Background"}
            val label=TextView(activity).apply{textSize=13f}
            val slider=SeekBar(activity).apply{max=DownloadLimits.MAX-1;progress=loader.downloadJobs(mode)-1;contentDescription="$name download jobs"}
            fun update(){val jobs=slider.progress+1;label.text="$name: $jobs "+if(jobs==1)"job"else"jobs"}
            update();downloadSliders[mode]=slider;content.addView(label);content.addView(slider)
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean){update();if(user)loader.downloadJobs(mode,value+1)}
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        content.addView(TextView(activity).apply{text="Simultaneous network downloads, from 1 to 50. Saved automatically and applied immediately; current downloads finish normally. Background jobs are additional. More jobs can use more memory and data.";textSize=12f})
        content.addView(button("Reset download jobs"){loader.resetDownloadJobs();for((mode,slider)in downloadSliders)slider.progress=loader.downloadJobs(mode)-1})
        val cacheLabel=TextView(activity).apply{textSize=12f}
        val cacheSize=SeekBar(activity).apply{max=1000;progress=loader.automaticCacheMiB;contentDescription="Automatic map cache size in MiB"}
        fun cacheText(){cacheLabel.text="Automatic disk cache: ${cacheSize.progress} MiB maximum · active limit ${loader.automaticCacheBytes/1_048_576} MiB. Adjusted for free storage. Changes apply after restarting the app; 0 disables automatic disk caching. Saved regions and attached ZIPs are separate."}
        cacheText();content.addView(cacheLabel);content.addView(cacheSize)
        cacheSize.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean){cacheText();if(user)loader.automaticCacheMiB(value)}
            override fun onStartTrackingTouch(bar:SeekBar?){}
            override fun onStopTrackingTouch(bar:SeekBar?){}
        })
        val ramLabel=TextView(activity).apply{textSize=12f}
        val ramSize=SeekBar(activity).apply{max=1000;progress=loader.ramCacheMiB;contentDescription="Combined RAM tile cache budget in MiB"}
        fun ramText(){
            ramLabel.text="Combined RAM tile cache: up to ${ramSize.progress} MiB · recommended ${loader.recommendedRamCacheMiB} MiB. Applies immediately; 0 disables retained tile caching. Temporary loading memory, renderer working memory and GPU resources are separate."+
                if(ramSize.progress>loader.recommendedRamCacheMiB)"\nHigh RAM budgets can cause Android to close the app, especially on the Samsung A20."else ""
        }
        ramText();content.addView(ramLabel);content.addView(ramSize)
        ramSize.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean){ramText();if(user)loader.ramCacheMiB(value)}
            override fun onStartTrackingTouch(bar:SeekBar?){}
            override fun onStopTrackingTouch(bar:SeekBar?){}
        })
        content.addView(button("GPU textures: "+arrayOf("Automatic","Compatibility","Texture arrays")[loader.textureBackend.coerceIn(0,2)]){
            val choices=arrayOf("Automatic","Compatibility","Texture arrays")
            AlertDialog.Builder(activity).setTitle("GPU textures").setSingleChoiceItems(choices,loader.textureBackend){picker,mode->loader.textureBackend(mode);picker.dismiss();dialog.dismiss();show()}.setNegativeButton("Cancel",null).show()
        })
        content.addView(CheckBox(activity).apply{text="Prepare nearby terrain while idle";isChecked=loader.prepareAhead;setOnCheckedChangeListener{_,checked->loader.prepareAhead(checked)}})
        content.addView(CheckBox(activity).apply{text="Offline only";isChecked=loader.offlineOnly;setOnCheckedChangeListener{_,checked->loader.offlineOnly(checked)}})
        content.addView(button("Loading diagnostics"){
            val snapshot=TextView(activity).apply{setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
            val loading=AlertDialog.Builder(activity).setTitle("Loading diagnostics").setView(ScrollView(activity).apply{addView(snapshot)}).setNegativeButton("Close",null).setNeutralButton("Copy"){_,_->
                activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Ridgeline loading",snapshot.text))
            }.create()
            fun refresh(){if(loading.isShowing){snapshot.text=loader.loadingDiagnostics();snapshot.postDelayed({refresh()},1000)}}
            loading.show();refresh()
        })
        content.addView(button("Add imagery ZIPs"){dialog.dismiss();addImagery()})
        content.addView(TextView(activity).apply{text="Overview imagery: ${loader.overviews.list().size}/6 packs attached. Keep the ZIPs at their selected location. Overviews contain imagery; terrain needs a connection or a saved region.";textSize=12f})
        for(pack in loader.overviews.list())content.addView(button("Detach ${pack.name}"){loader.detachOverview(pack.payload){dialog.dismiss();show()}})
        content.addView(TextView(activity).apply{text="Aerial and Satellite regions include terrain and available topographic overlays. NZ Topo regions contain printed cartography. Saved data stays until you delete it.";textSize=12f})
        content.addView(button("Download a region"){dialog.dismiss();startRegion()})
        if(downloading)content.addView(button("Stop current download"){loader.cancelDownload()})
        content.addView(status);content.addView(list)
        fun refresh(){loader.regions{rows->if(dialog.isShowing){list.removeAllViews();status.text=if(rows.isEmpty())"No saved regions"else"${rows.size} saved regions";for(region in rows){
            val line=LinearLayout(activity)
            line.addView(TextView(activity).apply{text="${region.name}\n${region.status} · ${region.bytes/1048576} MB · ${region.resources} resources";setPadding(0,dp(8),0,dp(8))},LinearLayout.LayoutParams(0,-2,1f))
            line.addView(button("Open"){
                val meta=region.metadata
                if(meta==null)Toast.makeText(activity,"This older region has no saved area metadata. Its downloaded tiles remain available on the map.",Toast.LENGTH_LONG).show()
                else{dialog.dismiss();val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ);loader.mapSettings.change{it.copy(base=base)};terrain.focus(listOf(Coordinate(meta.bounds.west,meta.bounds.south),Coordinate(meta.bounds.east,meta.bounds.north)))}
            })
            line.addView(button("Delete"){AlertDialog.Builder(activity).setMessage("Delete ${region.name} from this device?").setPositiveButton("Delete"){_,_->loader.removeRegion(region.id){refresh()}}.setNegativeButton("Cancel",null).show()});list.addView(line)
        }}}}
        dialog.show();refresh()
    }
    // ---- Capacitor's offline region flow: choose a page-shaped area, then
    // the "Download region" settings, progress and saved regions. ----
    private var frame:RegionFrame?=null
    private var chooser:View?=null
    private var pageSize="A4";private var landscape=false
    private var chosen:TileLoader.Bounds?=null
    private var progressText=""
    private var settingsDialog:AlertDialog?=null
    private val pageNames=RegionFrame.PAPER.keys.toList()
    private fun spinner(items:List<String>,selected:Int,changed:(Int)->Unit)=Spinner(activity).apply{
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,items);setSelection(selected)
        onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(p:AdapterView<*>?,v:android.view.View?,i:Int,id:Long){changed(i)};override fun onNothingSelected(p:AdapterView<*>?){}}
    }
    private fun labelled(label:String,view:android.view.View)=NativeUi.column(activity).apply{addView(NativeUi.text(activity,label,12f).apply{setTextColor(NativeUi.muted)});addView(view)}
    /** Back closes the region chooser. */
    fun back():Boolean{if(chooser==null)return false;closeChooser();return true}
    private fun closeChooser(){frame?.let{root.removeView(it)};chooser?.let{root.removeView(it)};frame=null;chooser=null}
    /** Step 1: 2D north-up map, a page-shaped frame and "Choose region". */
    fun startRegion(){
        closeChooser();settingsDialog?.dismiss()
        if(loader.mapSettings.state.threeD)loader.mapSettings.change{it.copy(threeD=false)}
        terrain.northUp()
        val overlay=RegionFrame(activity);frame=overlay
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val panel=NativeUi.column(activity).apply{
            setPadding(dp(16),dp(4),dp(16),dp(14));isClickable=true;elevation=dp(12).toFloat()
            background=android.graphics.drawable.GradientDrawable().apply{setColor(NativeUi.paper);val r=dp(20).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
        }
        val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(NativeUi.text(activity,"Choose region",17f).apply{setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(TextView(activity).apply{text="✕";textSize=18f;gravity=Gravity.CENTER;setTextColor(NativeUi.muted);contentDescription="Close offline regions";background=NativeUi.pressable(activity,null);isClickable=true;setOnClickListener{closeChooser()}},LinearLayout.LayoutParams(dp(44),dp(44)))
        panel.addView(heading)
        val row=LinearLayout(activity)
        row.addView(labelled("Page size",spinner(pageNames,pageNames.indexOf(pageSize).coerceAtLeast(0)){pageSize=pageNames[it];overlay.page(pageSize,landscape)}),LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(8)})
        row.addView(labelled("Orientation",spinner(listOf("Portrait","Landscape"),if(landscape)1 else 0){landscape=it==1;overlay.page(pageSize,landscape)}),LinearLayout.LayoutParams(0,-2,1f))
        panel.addView(row)
        panel.addView(NativeUi.text(activity,"Move and zoom the map to fit the area inside the frame, or drag its corners.",12f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(6),0,dp(8))})
        panel.addView(NativeUi.button(activity,"Save current view",true){
            val bounds=frameBounds()
            if(bounds==null)Toast.makeText(activity,"Move the frame over New Zealand.",Toast.LENGTH_LONG).show()
            else{chosen=bounds;closeChooser();showSettings()}
        },LinearLayout.LayoutParams(-1,dp(44)))
        root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));chooser=panel
        panel.post{overlay.bottomInset=panel.height;overlay.page(pageSize,landscape)}
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
    private val zooms=listOf(13,14,15,16,17)
    private val zoomNames=listOf("Overview · zoom 13","Standard · zoom 14","Detailed · zoom 15","Very detailed · zoom 16","Maximum imagery · zoom 17")
    private val bases=listOf(MapSettings.Base.LINZ,MapSettings.Base.SATELLITE,MapSettings.Base.TOPO)
    private var zoomChoice=2;private var baseChoice=0;private var includeTopo=true;private var regionName="My region"
    /** Step 2: Capacitor's "Download region" settings, progress and saved regions. */
    private fun showSettings(){
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(4),dp(18),dp(8))}
        content.addView(button("Change area"){settingsDialog?.dismiss();startRegion()},LinearLayout.LayoutParams(-2,dp(42)))
        val name=EditText(activity).apply{setSingleLine();setText(regionName);textSize=14f;filters=arrayOf(android.text.InputFilter.LengthFilter(60))}
        content.addView(labelled("Region name",name))
        val coords=listOf("West","South","East","North").map{EditText(activity).apply{hint=it;textSize=13f;inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}}
        chosen?.let{b->listOf(b.west,b.south,b.east,b.north).forEachIndexed{i,v->coords[i].setText(String.format(java.util.Locale.ROOT,"%.5f",v))}}
        val grid=GridLayout(activity).apply{columnCount=2;visibility=android.view.View.GONE}
        coords.forEachIndexed{i,field->grid.addView(labelled(listOf("west","south","east","north")[i],field),GridLayout.LayoutParams().apply{width=0;columnSpec=GridLayout.spec(i%2,1f)})}
        val advanced=TextView(activity).apply{text="Advanced ▸";textSize=13f;setTextColor(NativeUi.ink);setPadding(0,dp(10),0,dp(6));isClickable=true;background=NativeUi.pressable(activity,null)}
        advanced.setOnClickListener{val open=grid.visibility!=android.view.View.VISIBLE;grid.visibility=if(open)android.view.View.VISIBLE else android.view.View.GONE;advanced.text=if(open)"Advanced ▾" else "Advanced ▸"}
        content.addView(advanced);content.addView(grid)
        val detail=labelled("Download detail",spinner(zoomNames,zoomChoice){zoomChoice=it})
        content.addView(detail)
        val include=CheckBox(activity).apply{text="Include NZ Topo and mapped overlays";isChecked=includeTopo;textSize=13f;setOnCheckedChangeListener{_,c->includeTopo=c}}
        fun topoOnly(){val topo=bases[baseChoice]==MapSettings.Base.TOPO;detail.visibility=if(topo)android.view.View.GONE else android.view.View.VISIBLE;include.isEnabled=!topo;if(topo)include.isChecked=true}
        content.addView(labelled("Map content",spinner(listOf("LINZ aerial","Satellite","NZ Topo only · maximum detail"),baseChoice){baseChoice=it;topoOnly()}))
        content.addView(include);topoOnly()
        val estimate=NativeUi.text(activity,"",12.5f).apply{setTextColor(NativeUi.ink);setPadding(0,dp(6),0,dp(6))}
        fun bounds():TileLoader.Bounds{
            if(grid.visibility==android.view.View.VISIBLE){
                val v=coords.map{it.text.toString().toDoubleOrNull()?:error("Enter valid region coordinates.")}
                val b=TileLoader.Bounds(v[0],v[1],v[2],v[3]);require(b.west>=165&&b.east<=180&&b.south>=-49&&b.north<=-33&&b.west<b.east&&b.south<b.north){"Enter valid region coordinates."};return b
            }
            return chosen?:error("Choose an area first.")
        }
        fun zoom()=if(bases[baseChoice]==MapSettings.Base.TOPO)TOPO_MAX_ZOOM else zooms[zoomChoice]
        fun estimateText():String{val e=loader.regionEstimate(bounds(),zoom(),bases[baseChoice],includeTopo)
            return String.format(java.util.Locale.ROOT,"About %.0f MB · %,d files (%,d image tiles). Allow %.0f MB of space. Sizes are typical, not measured.",e.mib,e.files,e.images,e.mib*1.5)}
        val actions=LinearLayout(activity)
        actions.addView(button("Estimate size"){estimate.text=try{estimateText()}catch(e:Exception){e.message?:"Region could not be estimated."}},LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)})
        val list=NativeUi.column(activity)
        lateinit var refresh:()->Unit
        actions.addView(NativeUi.button(activity,"Download region",true){
            if(downloading){Toast.makeText(activity,"A region is already downloading.",Toast.LENGTH_SHORT).show();return@button}
            try{
                estimate.text=estimateText();regionName=name.text.toString().ifBlank{"My region"}
                downloading=true;progressText="Preparing download…";refresh()
                loader.downloadRegion(regionName,bounds(),zoom(),bases[baseChoice],includeTopo,progress={progressText=it;refresh()}){downloading=false;progressText="";loader.cameraChanged();refresh()}
                Toast.makeText(activity,"Region queued. Download progress appears below.",Toast.LENGTH_SHORT).show()
            }catch(e:Exception){downloading=false;estimate.text=e.message?:"Region could not be started."}
        },LinearLayout.LayoutParams(0,dp(44),1f))
        content.addView(actions);content.addView(estimate)
        content.addView(CheckBox(activity).apply{text="Use downloaded maps only";isChecked=loader.offlineOnly;textSize=13f;setOnCheckedChangeListener{_,c->loader.offlineOnly(c)}})
        content.addView(list)
        val imagery=TextView(activity).apply{text="Included imagery ▸";textSize=13f;setTextColor(NativeUi.ink);setPadding(0,dp(10),0,dp(6));isClickable=true;background=NativeUi.pressable(activity,null)}
        val packs=NativeUi.column(activity).apply{visibility=android.view.View.GONE
            addView(NativeUi.text(activity,"Overview imagery: ${loader.overviews.list().size}/6 packs attached. Zooms 0–8 are included in the app.",12f).apply{setTextColor(NativeUi.muted)})
            addView(button("Add imagery ZIPs"){settingsDialog?.dismiss();addImagery()})}
        imagery.setOnClickListener{val open=packs.visibility!=android.view.View.VISIBLE;packs.visibility=if(open)android.view.View.VISIBLE else android.view.View.GONE;imagery.text=if(open)"Included imagery ▾" else "Included imagery ▸"}
        content.addView(imagery);content.addView(packs)
        val dialog=AlertDialog.Builder(activity).setTitle("Download region").setView(ScrollView(activity).apply{addView(content)}).setNegativeButton("Close",null).create()
        settingsDialog=dialog
        refresh={loader.regions{rows->if(!dialog.isShowing)return@regions
            list.removeAllViews()
            if(downloading)list.addView(LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL
                addView(NativeUi.text(activity,"Downloading · $progressText",12.5f),LinearLayout.LayoutParams(0,-2,1f))
                addView(button("Stop"){loader.cancelDownload()})})
            list.addView(NativeUi.text(activity,if(rows.isEmpty())"No regions downloaded yet." else "Saved regions",13f).apply{setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(10),0,dp(4))})
            for(region in rows){
                val item=NativeUi.column(activity).apply{setPadding(0,dp(6),0,dp(6))}
                item.addView(NativeUi.text(activity,region.name,14f).apply{setTypeface(null,android.graphics.Typeface.BOLD)})
                item.addView(NativeUi.text(activity,String.format(java.util.Locale.ROOT,"%s · %,d files · %.1f MB",region.status,region.resources,region.bytes/1048576.0),12f).apply{setTextColor(NativeUi.muted)})
                val buttons=LinearLayout(activity)
                val meta=region.metadata
                if(meta!=null)buttons.addView(button("View"){dialog.dismiss();val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ);loader.mapSettings.change{it.copy(base=base)};terrain.focus(listOf(Coordinate(meta.bounds.west,meta.bounds.south),Coordinate(meta.bounds.east,meta.bounds.north)))})
                if(meta!=null&&!downloading&&region.status.startsWith("Partial"))buttons.addView(button("Resume"){
                    val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ)
                    downloading=true;progressText="Resuming…";refresh()
                    // The region's own choice, not the checkbox's current state.
                    loader.downloadRegion(region.name,meta.bounds,meta.zoom,base,meta.overlays,resume=region.id,progress={progressText=it;refresh()}){downloading=false;progressText="";loader.cameraChanged();refresh()}
                })
                buttons.addView(button("Remove"){AlertDialog.Builder(activity).setMessage("Delete ${region.name} from this device?").setPositiveButton("Delete"){_,_->loader.removeRegion(region.id){refresh()}}.setNegativeButton("Cancel",null).show()})
                item.addView(buttons);list.addView(item)
            }
        }}
        dialog.show();refresh()
    }
}
