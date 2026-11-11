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

    // ---- Offline regions: one bottom bar. A page-shaped (or custom) frame
    // over the 2D map; page, map content and detail; Save as PDF and
    // Download; saved regions and advanced options fold away. ----
    private var frame:RegionFrame?=null
    private var bar:View?=null
    private var pageSize="A4";private var landscape=false
    private var progressText=""
    private var progressView:TextView?=null
    private val pageNames=RegionFrame.PAPER.keys.toList()+CUSTOM
    private val zooms=listOf(13,14,15,16,17)
    private val zoomNames=listOf("Zoom 13 · overview","Zoom 14 · standard","Zoom 15 · detailed","Zoom 16 · very detailed","Zoom 17 · maximum")
    private val bases=listOf(MapSettings.Base.LINZ,MapSettings.Base.SATELLITE,MapSettings.Base.TOPO)
    private var zoomChoice=2;private var baseChoice=0;private var includeTopo=true;private var regionName=""
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
    /** Back closes the offline bar. */
    fun back():Boolean{if(bar==null)return false;closeBar();return true}
    private var tick:Runnable?=null
    private fun closeBar(){frame?.let{root.removeView(it)};bar?.let{root.removeView(it)};frame=null;bar=null;progressView=null;tick?.let{root.removeCallbacks(it)};tick=null}
    private fun km(v:Double)=if(v<10)String.format(java.util.Locale.ROOT,"%.1f",v)else String.format(java.util.Locale.ROOT,"%.0f",v)
    /** Width and height of [b] in kilometres (at its middle latitude). */
    private fun extentKm(b:TileLoader.Bounds):Pair<Double,Double>{
        val lat=Math.toRadians((b.south+b.north)/2)
        return (b.east-b.west)*111.32*cos(lat) to (b.north-b.south)*110.57
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
    /** Opens the offline bar over a 2D north-up map. */
    fun startRegion(){
        closeBar()
        if(loader.mapSettings.state.threeD)loader.mapSettings.change{it.copy(threeD=false)}
        terrain.northUp()
        val overlay=RegionFrame(activity);frame=overlay
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val content=NativeUi.column(activity)
        // Page and orientation.
        val orientation=spinner(listOf("Portrait","Landscape"),if(landscape)1 else 0){landscape=it==1;if(pageSize!=CUSTOM)overlay.page(pageSize,landscape)}
        fun applyPage(){
            if(pageSize==CUSTOM)overlay.custom()else overlay.page(pageSize,landscape)
            orientation.isEnabled=pageSize!=CUSTOM;orientation.alpha=if(pageSize==CUSTOM).4f else 1f
        }
        content.addView(row(spinner(pageNames,pageNames.indexOf(pageSize).coerceAtLeast(0)){pageSize=pageNames[it];applyPage()},orientation))
        // Name, map content and detail.
        val name=EditText(activity).apply{setSingleLine();hint="Region name";setText(regionName);textSize=14f;filters=arrayOf(android.text.InputFilter.LengthFilter(60))}
        content.addView(name,LinearLayout.LayoutParams(-1,-2))
        val detail=spinner(zoomNames,zoomChoice){zoomChoice=it;refreshInfo()}
        val include=CheckBox(activity).apply{text="NZ Topo & overlays";isChecked=includeTopo;textSize=13f;setOnCheckedChangeListener{_,c->includeTopo=c;refreshInfo()}}
        fun topoOnly(){val topo=bases[baseChoice]==MapSettings.Base.TOPO;detail.visibility=if(topo)View.INVISIBLE else View.VISIBLE;include.visibility=if(topo)View.GONE else View.VISIBLE}
        content.addView(row(spinner(listOf("LINZ aerial","Satellite","NZ Topo"),baseChoice){baseChoice=it;topoOnly();refreshInfo()},detail))
        content.addView(include);topoOnly()
        val info=NativeUi.text(activity,"",12.5f).apply{setPadding(0,dp(4),0,dp(4))};infoView=info
        content.addView(info)
        // Advanced: exact coordinates, downloaded-only mode and overview imagery.
        val coords=listOf("West","South","East","North").map{EditText(activity).apply{hint=it;textSize=13f;inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED}}
        var coordsEdited=false
        fun showCoords(b:TileLoader.Bounds){listOf(b.west,b.south,b.east,b.north).forEachIndexed{i,v->coords[i].setText(String.format(java.util.Locale.ROOT,"%.5f",v))};coordsEdited=false}
        for(field in coords)field.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(a:CharSequence?,b:Int,c:Int,d:Int){};override fun onTextChanged(a:CharSequence?,b:Int,c:Int,d:Int){if(field.hasFocus())coordsEdited=true};override fun afterTextChanged(e:android.text.Editable?){if(field.hasFocus())refreshInfo()}})
        fun bounds():TileLoader.Bounds{
            if(coordsEdited){
                val v=coords.map{it.text.toString().toDoubleOrNull()?:error("Enter valid coordinates.")}
                val b=TileLoader.Bounds(v[0],v[1],v[2],v[3]);require(b.west>=165&&b.east<=180&&b.south>=-49&&b.north<=-33&&b.west<b.east&&b.south<b.north){"Enter valid coordinates."};return b
            }
            return frameBounds()?:error("Move the frame over New Zealand.")
        }
        fun zoom()=if(bases[baseChoice]==MapSettings.Base.TOPO)TOPO_MAX_ZOOM else zooms[zoomChoice]
        var lastBounds:TileLoader.Bounds?=null
        refreshInfo={
            info.setTextColor(NativeUi.ink)
            info.text=try{
                val b=bounds();val (w,h)=extentKm(b)
                if(!coordsEdited&&b!=lastBounds)showCoords(b);lastBounds=b
                val e=loader.regionEstimate(b,zoom(),bases[baseChoice],includeTopo)
                String.format(java.util.Locale.ROOT,"%s × %s km · about %.0f MB · %,d files",km(w),km(h),e.mib,e.files)
            }catch(e:Exception){info.setTextColor(NativeUi.orange);e.message?:"Choose a smaller area."}
        }
        val grid=GridLayout(activity).apply{columnCount=2}
        coords.forEachIndexed{i,field->grid.addView(field,GridLayout.LayoutParams().apply{width=0;columnSpec=GridLayout.spec(i%2,1f)})}
        val packs=NativeUi.column(activity)
        fun showPacks(){packs.removeAllViews()
            packs.addView(NativeUi.text(activity,"Overview imagery: ${loader.overviews.list().size}/6 packs",12.5f).apply{setTextColor(NativeUi.muted)})
            for(pack in loader.overviews.list())packs.addView(button("Detach ${pack.name}"){loader.detachOverview(pack.payload){showPacks()}},LinearLayout.LayoutParams(-2,dp(40)))
            packs.addView(button("Add imagery ZIPs"){closeBar();addImagery()},LinearLayout.LayoutParams(-2,dp(40)))}
        showPacks()
        val advanced=NativeUi.column(activity).apply{
            addView(bold("Coordinates"));addView(grid)
            addView(CheckBox(activity).apply{text="Use downloaded maps only";isChecked=loader.offlineOnly;textSize=13f;setOnCheckedChangeListener{_,c->loader.offlineOnly(c)}})
            addView(bold("Included imagery"));addView(packs)
        }
        // Actions and progress.
        val pdf=button("Save as PDF"){
            regionName=name.text.toString()
            try{savePdf(bounds(),regionName.ifBlank{"Ridgeline map"})}catch(e:Exception){Toast.makeText(activity,e.message?:"Could not save the PDF.",Toast.LENGTH_LONG).show()}
        }
        lateinit var refreshRegions:()->Unit
        val download=NativeUi.button(activity,"Download",true){
            if(downloading){Toast.makeText(activity,"A region is already downloading.",Toast.LENGTH_SHORT).show();return@button}
            try{
                val b=bounds();loader.regionEstimate(b,zoom(),bases[baseChoice],includeTopo)
                regionName=name.text.toString();val title=regionName.ifBlank{"Region "+java.text.SimpleDateFormat("d MMM HH:mm",java.util.Locale.getDefault()).format(java.util.Date())}
                downloading=true;progressText="Preparing…";showProgress()
                loader.downloadRegion(title,b,zoom(),bases[baseChoice],includeTopo,progress={progressText=it;showProgress()}){downloading=false;progressText="";showProgress();loader.cameraChanged();refreshRegions()}
            }catch(e:Exception){downloading=false;showProgress();info.setTextColor(NativeUi.orange);info.text=e.message?:"Region could not be started."}
        }
        content.addView(LinearLayout(activity).apply{addView(pdf,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)});addView(download,LinearLayout.LayoutParams(0,dp(44),1f))})
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
            for(region in rows){
                val item=NativeUi.column(activity).apply{setPadding(0,dp(4),0,dp(4))}
                item.addView(NativeUi.text(activity,region.name,14f).apply{setTypeface(null,android.graphics.Typeface.BOLD)})
                item.addView(NativeUi.text(activity,String.format(java.util.Locale.ROOT,"%s · %.1f MB",region.status,region.bytes/1048576.0),12f).apply{setTextColor(NativeUi.muted)})
                val buttons=LinearLayout(activity)
                val meta=region.metadata
                if(meta!=null)buttons.addView(button("View"){closeBar();val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ);loader.mapSettings.change{it.copy(base=base)};terrain.focus(listOf(Coordinate(meta.bounds.west,meta.bounds.south),Coordinate(meta.bounds.east,meta.bounds.north)))})
                if(meta!=null&&!downloading&&region.status.startsWith("Partial"))buttons.addView(button("Resume"){
                    val base=runCatching{MapSettings.Base.valueOf(meta.basemap)}.getOrDefault(MapSettings.Base.LINZ)
                    downloading=true;progressText="Resuming…";showProgress()
                    // The region's own choice, not the checkbox's current state.
                    loader.downloadRegion(region.name,meta.bounds,meta.zoom,base,meta.overlays,resume=region.id,progress={progressText=it;showProgress()}){downloading=false;progressText="";showProgress();loader.cameraChanged();refreshRegions()}
                })
                buttons.addView(button("Remove"){AlertDialog.Builder(activity).setMessage("Delete ${region.name} from this device?").setPositiveButton("Delete"){_,_->loader.removeRegion(region.id){refreshRegions()}}.setNegativeButton("Cancel",null).show()})
                item.addView(buttons);saved.addView(item)
            }
        }}
        savedHeader.setOnClickListener{val open=saved.visibility!=View.VISIBLE;saved.visibility=if(open)View.VISIBLE else View.GONE;refreshRegions()}
        content.addView(savedHeader);content.addView(saved)
        content.addView(toggle("Advanced",advanced));content.addView(advanced)
        val panel=bottomPanel("Offline region","Close offline region",.5f,{closeBar()},content)
        root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));bar=panel
        // The frame stays above the bar, refitting when the bar changes height.
        var fitted=-1
        panel.addOnLayoutChangeListener{_,_,top,_,bottom,_,_,_,_->val h=bottom-top;if(h>0&&h!=fitted){fitted=h;overlay.bottomInset=h;if(pageSize==CUSTOM){if(overlay.isCustom())overlay.custom()else{overlay.page("A4",landscape);overlay.custom()}}else overlay.page(pageSize,landscape);refreshInfo()}}
        orientation.isEnabled=pageSize!=CUSTOM;orientation.alpha=if(pageSize==CUSTOM).4f else 1f
        overlay.onChange={refreshInfo()}
        val poll=object:Runnable{override fun run(){if(bar!==panel)return;if(!coordsEdited)refreshInfo();root.postDelayed(this,500)}}
        tick=poll;root.postDelayed(poll,500)
        refreshRegions()
    }
    private var infoView:TextView?=null
    private var refreshInfo:()->Unit={}
    private var progressRow:View?=null
    private fun showProgress(){progressRow?.visibility=if(downloading)View.VISIBLE else View.GONE;progressView?.text=if(downloading)"Downloading · $progressText" else ""}
    /** Bottom bar in the route bar's style: paper, rounded top, title and ✕,
     * scrolling content up to [maxFraction] of the screen. */
    private fun bottomPanel(title:String,closeLabel:String,maxFraction:Float,close:()->Unit,content:LinearLayout):LinearLayout{
        val panel=NativeUi.column(activity).apply{
            setPadding(dp(16),dp(2),dp(16),dp(10));isClickable=true;elevation=dp(12).toFloat()
            background=android.graphics.drawable.GradientDrawable().apply{setColor(NativeUi.paper);val r=dp(20).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
        }
        val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(NativeUi.text(activity,title,16f).apply{setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(TextView(activity).apply{text="✕";textSize=18f;gravity=Gravity.CENTER;setTextColor(NativeUi.muted);contentDescription=closeLabel;background=NativeUi.pressable(activity,null);isClickable=true;setOnClickListener{close()}},LinearLayout.LayoutParams(dp(44),dp(40)))
        panel.addView(heading)
        val limit={((root.height-root.paddingTop-root.paddingBottom).takeIf{it>0}?:activity.resources.displayMetrics.heightPixels)*maxFraction}
        panel.addView(object:ScrollView(activity){override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec(limit().toInt(),MeasureSpec.AT_MOST))}}.apply{addView(content)},LinearLayout.LayoutParams(-1,-2))
        return panel
    }

    // ---- Save as PDF: the framed map on the chosen page size. ----
    private val pdfRequest=4302
    private class PendingPdf(val map:android.graphics.Bitmap,val title:String,val paper:String,val landscape:Boolean,val widthKm:Double,val base:MapSettings.Base)
    private var pendingPdf:PendingPdf?=null
    private fun savePdf(bounds:TileLoader.Bounds,title:String){
        val view=frame?:return;val c=view.corners()
        val rect=android.graphics.Rect(c[0].roundToInt(),c[1].roundToInt(),c[2].roundToInt(),c[3].roundToInt())
        if(!rect.intersect(0,0,terrain.width,terrain.height)||rect.width()<16||rect.height()<16)error("Move the frame over the map.")
        val bitmap=android.graphics.Bitmap.createBitmap(rect.width(),rect.height(),android.graphics.Bitmap.Config.ARGB_8888)
        val paper=if(pageSize==CUSTOM)"A4" else pageSize
        val wide=if(pageSize==CUSTOM)rect.width()>rect.height()else landscape
        val (widthKm,_)=extentKm(bounds);val base=loader.mapSettings.state.base
        // The renderer's own pixels (map, routes and names; not the frame or buttons).
        android.view.PixelCopy.request(terrain,rect,bitmap,{result->
            if(result!=android.view.PixelCopy.SUCCESS){bitmap.recycle();Toast.makeText(activity,"Could not capture the map. Try again once it has loaded.",Toast.LENGTH_LONG).show();return@request}
            pendingPdf?.map?.recycle();pendingPdf=PendingPdf(bitmap,title,paper,wide,widthKm,base)
            val intent=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(android.content.Intent.CATEGORY_OPENABLE);type="application/pdf";putExtra(android.content.Intent.EXTRA_TITLE,title.replace(Regex("[\\\\/:*?\"<>|]"),"_")+".pdf")}
            runCatching{activity.startActivityForResult(intent,pdfRequest)}.onFailure{Toast.makeText(activity,"No file picker is available.",Toast.LENGTH_LONG).show()}
        },android.os.Handler(android.os.Looper.getMainLooper()))
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
    private companion object{const val CUSTOM="Custom"}
}
