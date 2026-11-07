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
    private var dismissedAt=0L
    fun dismiss():Boolean{val view=overlay?:return false;root.removeView(view);overlay=null;dismissedAt=android.os.SystemClock.uptimeMillis();return true}
    /** Toggles. A tap outside closes the panel and still reaches what was tapped;
     * when that was the Layers button itself, it must not reopen the panel. */
    fun show(){if(overlay!=null){dismiss();return};if(android.os.SystemClock.uptimeMillis()-dismissedAt<400)return;render()}
    private val bounds=android.graphics.Rect()
    /** Called by the activity for every touch-down, before normal dispatch: a
     * touch outside the panel closes it and still reaches what was touched
     * (Capacitor's layers panel has no modal cover). */
    fun touchDown(x:Float,y:Float){
        val panel=(overlay as? FrameLayout)?.getChildAt(0)?:return
        if(!panel.getGlobalVisibleRect(bounds)||!bounds.contains(x.toInt(),y.toInt()))dismiss()
    }
    private fun render(){
        dismiss();val cover=FrameLayout(activity)
        val panel=NativeUi.column(activity).apply{setPadding(dp(16),dp(8),dp(16),dp(12));background=NativeUi.background(activity,NativeUi.paper,12,true);elevation=dp(10).toFloat();isClickable=true}
        val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.addView(NativeUi.text(activity,"Map layers",16f).apply{setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(closeButton{dismiss()}.apply{contentDescription="Close layers"},LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(heading)
        val tabs=LinearLayout(activity)
        for((label,flag)in listOf("Layers" to false,"Advanced" to true))tabs.addView(TextView(activity).apply{
            text=label;textSize=14f;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;isSelected=advanced==flag
            setTextColor(if(advanced==flag)Color.WHITE else NativeUi.ink)
            background=if(advanced==flag)android.graphics.drawable.GradientDrawable().apply{setColor(NativeUi.ink);val r=dp(8).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}else NativeUi.pressable(activity,null)
            setOnClickListener{if(advanced!=flag){advanced=flag;render()}}
        },LinearLayout.LayoutParams(0,dp(36),1f))
        panel.addView(tabs);panel.addView(View(activity).apply{setBackgroundColor(NativeUi.line)},LinearLayout.LayoutParams(-1,dp(1)))
        val content=NativeUi.column(activity);panel.addView(ScrollView(activity).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        if(advanced)advanced(content)else basic(content)
        cover.addView(panel,FrameLayout.LayoutParams(minOf(dp(296),activity.resources.displayMetrics.widthPixels-dp(82)),-1,Gravity.TOP or Gravity.END).apply{topMargin=dp(52);bottomMargin=dp(16);marginEnd=dp(10)})
        // The holder does not intercept touches; only the panel is clickable.
        root.addView(cover,FrameLayout.LayoutParams(-1,-1));overlay=cover
    }
    private fun closeButton(action:()->Unit)=TextView(activity).apply{text="✕";textSize=18f;gravity=Gravity.CENTER;setTextColor(NativeUi.ink);background=NativeUi.pressable(activity,null);isClickable=true;setOnClickListener{action()}}
    private fun legend(parent:LinearLayout,label:String){
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(14),0,dp(6))}
        row.addView(NativeUi.text(activity,label,14f).apply{setTypeface(null,android.graphics.Typeface.BOLD)})
        row.addView(View(activity).apply{setBackgroundColor(NativeUi.line)},LinearLayout.LayoutParams(0,dp(1),1f).apply{marginStart=dp(8)})
        parent.addView(row)
    }
    /** Capacitor ".setting" row: label on the left, checkbox on the right. */
    private fun check(parent:LinearLayout,label:String,value:Boolean,key:android.graphics.drawable.Drawable?=null,change:(Boolean)->Unit){
        val box=CheckBox(activity).apply{isChecked=value;buttonTintList=android.content.res.ColorStateList.valueOf(NativeUi.ink);contentDescription=label;setOnCheckedChangeListener{_,checked->change(checked)}}
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(42);isClickable=true;background=NativeUi.pressable(activity,null);setOnClickListener{box.toggle()}}
        if(key!=null)row.addView(ImageView(activity).apply{setImageDrawable(key)},LinearLayout.LayoutParams(dp(22),dp(14)).apply{marginEnd=dp(6)})
        row.addView(NativeUi.text(activity,label,14f),LinearLayout.LayoutParams(0,-2,1f));row.addView(box)
        parent.addView(row)
    }
    private fun slider(parent:LinearLayout,label:String,max:Int,value:Int,format:(Int)->String={it.toString()},change:(Int)->Unit){
        val row=LinearLayout(activity).apply{setPadding(0,dp(10),0,0)};val shown=NativeUi.text(activity,format(value),14f)
        row.addView(NativeUi.text(activity,label,14f),LinearLayout.LayoutParams(0,-2,1f));row.addView(shown);parent.addView(row)
        parent.addView(SeekBar(activity).apply{this.max=max;progress=value;contentDescription=label
            progressTintList=android.content.res.ColorStateList.valueOf(NativeUi.ink);thumbTintList=progressTintList
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(bar:SeekBar?,v:Int,user:Boolean){shown.text=format(v);if(user)change(v)};override fun onStartTrackingTouch(bar:SeekBar?){};override fun onStopTrackingTouch(bar:SeekBar?){} })},LinearLayout.LayoutParams(-1,dp(40)))
    }
    private fun swatch(topo:Boolean):android.graphics.drawable.Drawable=if(!topo)android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(122,134,96),Color.rgb(83,104,74),Color.rgb(35,61,52))).apply{cornerRadius=dp(4).toFloat()}
        else object:android.graphics.drawable.Drawable(){
            private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas:android.graphics.Canvas){val b=bounds;paint.style=android.graphics.Paint.Style.FILL;paint.color=Color.rgb(236,234,214);canvas.drawRoundRect(android.graphics.RectF(b),dp(4).toFloat(),dp(4).toFloat(),paint)
                paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat();paint.color=Color.rgb(196,190,160)
                for(i in 1..5){val w=b.width()*.13f*i;val h=b.height()*.13f*i;canvas.drawOval(b.exactCenterX()-w,b.exactCenterY()-h*.6f,b.exactCenterX()+w,b.exactCenterY()+h*.6f,paint)}}
            override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:android.graphics.ColorFilter?){}
            @Deprecated("Drawable API") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
        }
    private fun basic(parent:LinearLayout){
        val state=settings.state
        val bases=LinearLayout(activity).apply{setPadding(0,dp(12),0,0)}
        for((label,mode)in listOf("LINZ aerial" to MapSettings.Base.LINZ,"Satellite" to MapSettings.Base.SATELLITE,"NZ Topo · 2D" to MapSettings.Base.TOPO)){
            val selected=state.base==mode
            val card=NativeUi.column(activity).apply{
                setPadding(dp(6),dp(6),dp(6),dp(6));isClickable=true;isSelected=selected;contentDescription=label
                background=NativeUi.pressable(activity,android.graphics.drawable.GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(8).toFloat();setStroke(dp(if(selected)2 else 1),if(selected)NativeUi.ink else NativeUi.line)})
                addView(View(activity).apply{background=swatch(mode==MapSettings.Base.TOPO)},LinearLayout.LayoutParams(-1,dp(32)))
                addView(NativeUi.text(activity,label,13f).apply{setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(6),0,0)})
                setOnClickListener{settings.change{it.copy(base=mode)};render()}
            }
            bases.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply{if(mode!=MapSettings.Base.TOPO)marginEnd=dp(6)})
        }
        parent.addView(bases)
        legend(parent,"Map detail")
        slider(parent,"Imagery detail",DetailPolicy.MAX,loader.imageryDetail,{String.format(java.util.Locale.ROOT,"%.1f×",DetailPolicy.multiplier(it))}){loader.detail(false,it)}
        slider(parent,"Terrain detail",DetailPolicy.MAX,loader.terrainDetail,{String.format(java.util.Locale.ROOT,"%.1f×",DetailPolicy.multiplier(it))}){loader.detail(true,it)}
        legend(parent,"Topographic overlays")
        if(state.base==MapSettings.Base.TOPO)parent.addView(NativeUi.text(activity,"NZ Topo includes printed labels. Overlay choices resume on aerial imagery.",13f).apply{setTextColor(NativeUi.muted)})
        check(parent,"Roads & road names",state.roads){v->settings.change{it.copy(roads=v)}}
        check(parent,"Place & water names",state.names){v->settings.change{it.copy(names=v)}}
        check(parent,"Mapped walking tracks",state.tracks,trackKey()){v->settings.change{it.copy(tracks=v)}}
        check(parent,"Huts & bivouacs",state.huts,hutKey()){v->settings.change{it.copy(huts=v)}}
        check(parent,"Contour lines & heights",state.contours){v->settings.change{it.copy(contours=v)}}
        parent.addView(NativeUi.text(activity,"Highways and major names appear first. Zoom in for smaller roads, road names, lesser peaks, local tracks and bivouacs.",14f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(10),0,dp(8))})
        parent.addView(NativeUi.text(activity,"Check DOC alerts ↗",14f).apply{
            paintFlags=paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG;setTextColor(Color.rgb(58,110,76));minHeight=dp(40);gravity=Gravity.CENTER_VERTICAL;isClickable=true
            setOnClickListener{runCatching{activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://www.doc.govt.nz/parks-and-recreation/know-before-you-go/alerts/")))}}
        })
        parent.addView(NativeUi.text(activity,(if(state.threeD)"3D terrain"else"2D map")+" · Native",12f).apply{setTextColor(NativeUi.muted);setPadding(0,dp(10),0,0)})
    }
    /** Capacitor legend keys: dashed orange track, ochre hut dot. */
    private fun trackKey()=object:android.graphics.drawable.Drawable(){
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(201,122,46);strokeWidth=dp(2).toFloat()}
        override fun draw(canvas:android.graphics.Canvas){val y=bounds.exactCenterY();val w=bounds.width()/5f;canvas.drawLine(bounds.left.toFloat(),y,bounds.left+w*2,y,paint);canvas.drawLine(bounds.left+w*3,y,bounds.left+w*5,y,paint)}
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:android.graphics.ColorFilter?){}
        @Deprecated("Drawable API") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
    private fun hutKey()=object:android.graphics.drawable.Drawable(){
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:android.graphics.Canvas){val r=dp(5).toFloat();val cx=bounds.left+r+dp(1);val cy=bounds.exactCenterY();paint.style=android.graphics.Paint.Style.FILL;paint.color=Color.rgb(196,160,58);canvas.drawCircle(cx,cy,r,paint);paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=dp(1).toFloat();paint.color=Color.rgb(60,60,40);canvas.drawCircle(cx,cy,r,paint)}
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:android.graphics.ColorFilter?){}
        @Deprecated("Drawable API") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
    private fun advanced(parent:LinearLayout){
        val state=settings.state;NativeUi.space(parent,6)
        check(parent,"Imagery source borders",state.borders){v->settings.change{it.copy(borders=v)}}
        parent.addView(NativeUi.text(activity,"Subtle source boundaries and labels along the line. Saved on this device.",12f).apply{setTextColor(NativeUi.muted)})
        check(parent,"Peak labels",state.peaks){v->settings.change{it.copy(peaks=v)}}
        check(parent,"Natural imagery colour",state.natural){v->settings.change{it.copy(natural=v)}}
        check(parent,"Soft terrain shading",state.hillshade){v->settings.change{it.copy(hillshade=v)}}
        legend(parent,"Sky colour")
        val names=arrayOf("Mist","Alpine blue","Clear blue","Warm haze","Slate","Night","Custom colour")
        val colors=intArrayOf(0xffbdcbd0.toInt(),0xffadc9db.toInt(),0xff88c6fc.toInt(),0xffe8d9c4.toInt(),0xff697b89.toInt(),0xff17242f.toInt())
        parent.addView(NativeUi.button(activity,names[colors.indexOf(state.sky).takeIf{it>=0}?:6]){
            AlertDialog.Builder(activity).setTitle("Sky colour").setItems(names){_,i->if(i<colors.size){settings.change{it.copy(sky=colors[i])};render()}else{val input=EditText(activity).apply{setSingleLine();hint="#bdcbd0";setText(String.format("#%06x",state.sky and 0xffffff))};val dialog=AlertDialog.Builder(activity).setTitle("Custom sky colour").setView(input).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val color=Color.parseColor(input.text.toString());settings.change{it.copy(sky=color)};dialog.dismiss();render()}catch(_:Exception){input.error="Enter a colour such as #bdcbd0"}}};dialog.show()}}.show()
        })
        legend(parent,"Terrain relief");slider(parent,"Terrain relief",10,((state.relief-1)*10).toInt(),{String.format(java.util.Locale.ROOT,"%.1f×",1+it/10f)}){v->settings.change{it.copy(relief=1+v/10f)}}
        parent.addView(NativeUi.text(activity,"Relief affects the 3D view. Route distances and elevation statistics use real heights.",11f));NativeUi.divider(parent)
        for(mode in DownloadLimits.Mode.entries){val label=when(mode){DownloadLimits.Mode.MOVING->"Download jobs while moving";DownloadLimits.Mode.STATIONARY->"Download jobs while stationary";DownloadLimits.Mode.BACKGROUND->"Additional background jobs"};slider(parent,label,49,loader.downloadJobs(mode)-1,{(it+1).toString()}){loader.downloadJobs(mode,it+1)}}
        parent.addView(NativeUi.button(activity,"Reset download jobs"){loader.resetDownloadJobs();render()})
        parent.addView(NativeUi.text(activity,"More jobs share bandwidth. Routes, names and map backgrounds use the same bounded transport and cache.",11f))
        NativeUi.divider(parent)
        slider(parent,"Combined RAM tile cache",1000,loader.ramCacheMiB,{"$it MiB"}){loader.ramCacheMiB(it)}
        slider(parent,"Automatic disk cache",1000,loader.automaticCacheMiB,{"$it MiB · applies after restart"}){loader.automaticCacheMiB(it)}
        check(parent,"Progressive terrain",settings.progressiveTerrain){loader.progressiveTerrain(it)}
        check(parent,"Prepare nearby terrain while idle",loader.prepareAhead){loader.prepareAhead(it)}
        check(parent,"Preload coarser surrounding tiles",loader.parentPreload){loader.parentPreload(it)}
        parent.addView(NativeUi.text(activity,"After the view loads, keeps lower-detail terrain and imagery for the surrounding area ready, so moving stays smooth while sharp tiles load.",12f).apply{setTextColor(NativeUi.muted)})
        check(parent,"Lower resolution while moving",terrain.motionResolution){terrain.motionResolution=it}
        parent.addView(NativeUi.text(activity,"Draws fewer pixels while you pan, zoom or rotate, then a full-resolution frame as soon as the map stops. Smoother movement on slower phones.",12f).apply{setTextColor(NativeUi.muted)})
        check(parent,"Downloaded maps only",loader.offlineOnly){loader.offlineOnly(it)}
        parent.addView(NativeUi.button(activity,"Offline maps, imagery ZIPs & diagnostics"){dismiss();offline.show()})
        parent.addView(NativeUi.button(activity,"About & licences"){
            val text=NativeUi.text(activity,"Ridgeline Native\nNative map and route planner\n\nLINZ imagery, elevation and topographic data · CC BY 4.0. Esri World Imagery and contributors.\n\nMapLibre adaptation notice:\n"+activity.assets.open("licenses/maplibre-gl-js.txt").bufferedReader().use{it.readText()},12f).apply{setPadding(dp(16),dp(12),dp(16),dp(12));setTextIsSelectable(true)}
            AlertDialog.Builder(activity).setTitle("About Ridgeline").setView(ScrollView(activity).apply{addView(text)}).setPositiveButton("Close",null).show()
        })
        parent.addView(NativeUi.text(activity,"LINZ topographic data · CC BY 4.0. Mapped tracks and huts are not a condition or availability report.",11f))
    }
}
