package nz.ridgeline.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

/** Capacitor Layers/Advanced structure, backed by native settings and services. */
internal class LayersUi(private val activity:Activity,private val root:FrameLayout,private val terrain:TerrainView,private val loader:TileLoader,private val offline:OfflineUi,private val newRoute:()->Unit){
    private val settings get()=loader.mapSettings
    private var overlay:View?=null;private var advanced=false
    private fun dp(v:Int)=NativeUi.dp(activity,v)
    fun dismiss():Boolean{val view=overlay?:return false;root.removeView(view);overlay=null;return true}
    fun show(){if(overlay!=null){dismiss();return};render()}
    private fun render(){
        dismiss();val cover=FrameLayout(activity);cover.addView(View(activity).apply{setOnClickListener{dismiss()}},FrameLayout.LayoutParams(-1,-1))
        val panel=NativeUi.column(activity).apply{setPadding(dp(14),dp(12),dp(14),dp(12));background=NativeUi.background(activity,NativeUi.paper,12,true);elevation=dp(10).toFloat();isClickable=true}
        val heading=LinearLayout(activity);heading.addView(NativeUi.heading(activity,"Layers"),LinearLayout.LayoutParams(0,-2,1f));heading.addView(NativeUi.button(activity,"×"){dismiss()}.apply{contentDescription="Close layers"},LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(heading)
        val tabs=LinearLayout(activity)
        for((label,flag)in listOf("Layers" to false,"Advanced" to true))tabs.addView(NativeUi.button(activity,label,advanced==flag){advanced=flag;render()},LinearLayout.LayoutParams(0,dp(44),1f))
        panel.addView(tabs);val content=NativeUi.column(activity);panel.addView(ScrollView(activity).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        if(advanced)advanced(content)else basic(content)
        cover.addView(panel,FrameLayout.LayoutParams(minOf(dp(330),activity.resources.displayMetrics.widthPixels-dp(40)),-1,Gravity.TOP or Gravity.END).apply{topMargin=dp(62);bottomMargin=dp(16);marginEnd=dp(10)})
        root.addView(cover,FrameLayout.LayoutParams(-1,-1));overlay=cover
    }
    private fun check(parent:LinearLayout,label:String,value:Boolean,change:(Boolean)->Unit){parent.addView(CheckBox(activity).apply{text=label;textSize=13f;setTextColor(NativeUi.ink);isChecked=value;minHeight=dp(44);setOnCheckedChangeListener{_,checked->change(checked)}})}
    private fun slider(parent:LinearLayout,label:String,max:Int,value:Int,format:(Int)->String={it.toString()},change:(Int)->Unit){
        val text=NativeUi.text(activity,"$label · ${format(value)}");NativeUi.space(parent);parent.addView(text)
        parent.addView(SeekBar(activity).apply{this.max=max;progress=value;contentDescription=label;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(bar:SeekBar?,v:Int,user:Boolean){text.text="$label · ${format(v)}";if(user)change(v)};override fun onStartTrackingTouch(bar:SeekBar?){};override fun onStopTrackingTouch(bar:SeekBar?){} })})
    }
    private fun basic(parent:LinearLayout){
        val state=settings.state;NativeUi.space(parent)
        parent.addView(NativeUi.text(activity,"Map background"))
        val bases=LinearLayout(activity)
        for((label,mode)in listOf("Aerial" to MapSettings.Base.LINZ,"Satellite" to MapSettings.Base.SATELLITE,"NZ Topo" to MapSettings.Base.TOPO))bases.addView(NativeUi.button(activity,label,state.base==mode){settings.change{it.copy(base=mode)};render()},LinearLayout.LayoutParams(0,dp(44),1f))
        parent.addView(bases);NativeUi.divider(parent)
        parent.addView(NativeUi.text(activity,"Topographic overlays"))
        if(state.base==MapSettings.Base.TOPO)parent.addView(NativeUi.text(activity,"NZ Topo includes printed labels. Overlay choices resume on aerial imagery.",11f))
        check(parent,"Place and water names",state.names){v->settings.change{it.copy(names=v)}}
        check(parent,"Roads",state.roads){v->settings.change{it.copy(roads=v)}}
        check(parent,"Walking tracks",state.tracks){v->settings.change{it.copy(tracks=v)}}
        check(parent,"Huts and bivouacs",state.huts){v->settings.change{it.copy(huts=v)}}
        check(parent,"Contours",state.contours){v->settings.change{it.copy(contours=v)}}
        check(parent,"Imagery source boundaries",state.borders){v->settings.change{it.copy(borders=v)}}
        NativeUi.divider(parent)
        slider(parent,"Imagery detail",DetailPolicy.MAX,loader.imageryDetail,{String.format(java.util.Locale.ROOT,"%.1f×",DetailPolicy.multiplier(it))}){loader.detail(false,it)}
        slider(parent,"Terrain detail",DetailPolicy.MAX,loader.terrainDetail,{String.format(java.util.Locale.ROOT,"%.1f×",DetailPolicy.multiplier(it))}){loader.detail(true,it)}
        parent.addView(NativeUi.button(activity,"＋ Create route",true){dismiss();newRoute()})
    }
    private fun advanced(parent:LinearLayout){
        val state=settings.state
        check(parent,"LINZ peak labels",state.peaks){v->settings.change{it.copy(peaks=v)}}
        check(parent,"Natural imagery colour",state.natural){v->settings.change{it.copy(natural=v)}}
        check(parent,"Soft terrain shading · aerial backgrounds",state.hillshade){v->settings.change{it.copy(hillshade=v)}}
        parent.addView(NativeUi.text(activity,"Sky colour"))
        val names=arrayOf("Mist","Alpine blue","Clear blue","Warm haze","Slate","Night","Custom colour")
        val colors=intArrayOf(0xffbdcbd0.toInt(),0xffadc9db.toInt(),0xff88c6fc.toInt(),0xffe8d9c4.toInt(),0xff697b89.toInt(),0xff17242f.toInt())
        parent.addView(NativeUi.button(activity,names[colors.indexOf(state.sky).takeIf{it>=0}?:6]){
            AlertDialog.Builder(activity).setTitle("Sky colour").setItems(names){_,i->if(i<colors.size){settings.change{it.copy(sky=colors[i])};render()}else{val input=EditText(activity).apply{setSingleLine();hint="#bdcbd0";setText(String.format("#%06x",state.sky and 0xffffff))};val dialog=AlertDialog.Builder(activity).setTitle("Custom sky colour").setView(input).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val color=Color.parseColor(input.text.toString());settings.change{it.copy(sky=color)};dialog.dismiss();render()}catch(_:Exception){input.error="Enter a colour such as #bdcbd0"}}};dialog.show()}}.show()
        })
        slider(parent,"Terrain relief",10,((state.relief-1)*10).toInt(),{String.format(java.util.Locale.ROOT,"%.1f×",1+it/10f)}){v->settings.change{it.copy(relief=1+v/10f)}}
        parent.addView(NativeUi.text(activity,"Relief affects the 3D view. Route distances and elevation statistics use real heights.",11f));NativeUi.divider(parent)
        for(mode in DownloadLimits.Mode.entries){val label=when(mode){DownloadLimits.Mode.MOVING->"Download jobs while moving";DownloadLimits.Mode.STATIONARY->"Download jobs while stationary";DownloadLimits.Mode.BACKGROUND->"Additional background jobs"};slider(parent,label,49,loader.downloadJobs(mode)-1,{(it+1).toString()}){loader.downloadJobs(mode,it+1)}}
        parent.addView(NativeUi.button(activity,"Reset download jobs"){loader.resetDownloadJobs();render()})
        parent.addView(NativeUi.text(activity,"More jobs share bandwidth. Routes, names and map backgrounds use the same bounded transport and cache.",11f))
        NativeUi.divider(parent)
        slider(parent,"Combined RAM tile cache",1000,loader.ramCacheMiB,{"$it MiB"}){loader.ramCacheMiB(it)}
        slider(parent,"Automatic disk cache",1000,loader.automaticCacheMiB,{"$it MiB · applies after restart"}){loader.automaticCacheMiB(it)}
        check(parent,"Prepare nearby terrain while idle",loader.prepareAhead){loader.prepareAhead(it)}
        check(parent,"Downloaded maps only",loader.offlineOnly){loader.offlineOnly(it)}
        parent.addView(NativeUi.button(activity,"Offline maps, imagery ZIPs & diagnostics"){dismiss();offline.show()})
        parent.addView(NativeUi.button(activity,"About & licences"){
            val text=NativeUi.text(activity,"Ridgeline Native\nNative map and route planner\n\nLINZ imagery, elevation and topographic data · CC BY 4.0. Esri World Imagery and contributors.\n\nMapLibre adaptation notice:\n"+activity.assets.open("licenses/maplibre-gl-js.txt").bufferedReader().use{it.readText()},12f).apply{setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
            AlertDialog.Builder(activity).setTitle("About Ridgeline").setView(ScrollView(activity).apply{addView(text)}).setPositiveButton("Close",null).show()
        })
        parent.addView(NativeUi.text(activity,"LINZ topographic data · CC BY 4.0. Mapped tracks and huts are not a condition or availability report.",11f))
    }
}
