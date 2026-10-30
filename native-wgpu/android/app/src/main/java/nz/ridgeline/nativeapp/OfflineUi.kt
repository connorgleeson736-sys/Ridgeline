package nz.ridgeline.nativeapp
import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.*
import kotlin.math.*

class OfflineUi(private val activity:Activity,root:FrameLayout,private val terrain:TerrainView,private val loader:TileLoader){
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
    init{root.addView(button("Offline / imagery"){show()},FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.START).apply{leftMargin=dp(12);topMargin=dp(114)})}
    private fun show(){
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8))}
        val list=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val status=TextView(activity)
        val dialog=AlertDialog.Builder(activity).setTitle("Offline maps").setView(ScrollView(activity).apply{addView(content)}).setNegativeButton("Close",null).create()
        content.addView(TextView(activity).apply{text="Automatic cache: up to ${loader.automaticCacheBytes/1_000_000} MB, adjusted for free storage. Saved regions are kept separately.";textSize=12f})
        content.addView(CheckBox(activity).apply{text="Prepare nearby terrain while idle";isChecked=loader.prepareAhead;setOnCheckedChangeListener{_,checked->loader.prepareAhead(checked)}})
        content.addView(CheckBox(activity).apply{text="Offline only";isChecked=loader.offlineOnly;setOnCheckedChangeListener{_,checked->loader.offlineOnly(checked)}})
        content.addView(button("Add imagery ZIPs"){dialog.dismiss();addImagery()})
        content.addView(TextView(activity).apply{text="Overview imagery: ${loader.overviews.list().size}/6 packs attached. Keep the ZIPs at their selected location. Overviews contain imagery; terrain needs a connection or a saved region.";textSize=12f})
        for(pack in loader.overviews.list())content.addView(button("Detach ${pack.name}"){loader.detachOverview(pack.payload){dialog.dismiss();show()}})
        content.addView(TextView(activity).apply{text="Saved regions include imagery and terrain. Saved data stays on this device until you delete it.";textSize=12f})
        content.addView(button("Download around map center"){if(downloading)Toast.makeText(activity,"A region is already downloading.",Toast.LENGTH_SHORT).show()else{dialog.dismiss();newRegion()}})
        if(downloading)content.addView(button("Cancel current download"){loader.cancelDownload()})
        content.addView(status);content.addView(list)
        fun refresh(){loader.regions{rows->if(dialog.isShowing){list.removeAllViews();status.text=if(rows.isEmpty())"No saved regions"else"${rows.size} saved regions";for(region in rows){
            val line=LinearLayout(activity)
            line.addView(TextView(activity).apply{text="${region.name}\n${region.status} · ${region.bytes/1048576} MB · ${region.resources} resources";setPadding(0,dp(8),0,dp(8))},LinearLayout.LayoutParams(0,-2,1f))
            line.addView(button("Delete"){AlertDialog.Builder(activity).setMessage("Delete ${region.name} from this device?").setPositiveButton("Delete"){_,_->loader.removeRegion(region.id){refresh()}}.setNegativeButton("Cancel",null).show()});list.addView(line)
        }}}}
        dialog.show();refresh()
    }
    private fun newRegion(){
        val pose=terrain.cameraPose
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        val name=EditText(activity).apply{hint="Region name";setSingleLine();setText("Saved region")}
        val radius=EditText(activity).apply{hint="Radius in km";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;setText("1")}
        val detail=SeekBar(activity).apply{max=11;progress=7}
        val estimate=TextView(activity)
        content.addView(name);content.addView(TextView(activity).apply{text="Area around current map center: radius (km)"});content.addView(radius)
        content.addView(TextView(activity).apply{text="Offline detail: higher levels retain finer imagery and need more storage.";textSize=12f});content.addView(detail);content.addView(estimate)
        fun bounds():TileLoader.Bounds{val km=radius.text.toString().toDouble();require(km in .1..50.0){"Choose a radius from 0.1 to 50 km."};val dy=km/111.195;val dx=dy/cos(Math.toRadians(pose.latitude));return TileLoader.Bounds(max(165.0,pose.longitude-dx),max(-49.0,pose.latitude-dy),min(180.0,pose.longitude+dx),min(-33.0,pose.latitude+dy))}
        fun update(){estimate.text=try{"Detail ${detail.progress+8} · ${loader.regionEstimate(bounds(),detail.progress+8)} image tiles, plus terrain"}catch(e:Exception){e.message?:"Enter a valid region"}}
        radius.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){update()};override fun afterTextChanged(s:android.text.Editable?){} })
        detail.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){update()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){} });update()
        val dialog=AlertDialog.Builder(activity).setTitle("Save region").setView(content).setPositiveButton("Download",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                val area=bounds();loader.regionEstimate(area,detail.progress+8);downloading=true;dialog.dismiss()
                val progress=TextView(activity).apply{text="Preparing download…";setPadding(dp(16),dp(12),dp(16),dp(12))}
                val current=AlertDialog.Builder(activity).setTitle(name.text.toString().ifBlank{"Saved region"}).setView(progress).setNegativeButton("Cancel"){_,_->loader.cancelDownload()}.create();current.setCancelable(false);current.show()
                loader.downloadRegion(name.text.toString().ifBlank{"Saved region"},area,detail.progress+8,{progress.text=it}){downloading=false;current.dismiss();loader.cameraChanged();show()}
            }catch(e:Exception){downloading=false;Toast.makeText(activity,e.message?:"Region could not be started",Toast.LENGTH_LONG).show()}
        }};dialog.show()
    }
}
