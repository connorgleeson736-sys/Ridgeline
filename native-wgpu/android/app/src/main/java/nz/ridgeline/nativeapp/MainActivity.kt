package nz.ridgeline.nativeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity:Activity(){
    private lateinit var terrain:TerrainView
    private lateinit var loader:TileLoader
    private lateinit var planner:PlannerUi
    private lateinit var trackSheet:TrackSheet
    private lateinit var location:LocationUi
    private lateinit var offlineUi:OfflineUi
    private lateinit var layersUi:LayersUi
    private lateinit var mapLayers:MapLayers
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun night(config:android.content.res.Configuration)=(config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES
    /** The phone's status and navigation bars over the app's background, their icons
     * dark on the light theme and light on the dark one (white on white was unreadable). */
    private fun systemBars(){
        val light=!NativeUi.dark
        @Suppress("DEPRECATION") run{window.statusBarColor=NativeUi.paper;window.navigationBarColor=NativeUi.paper}
        if(android.os.Build.VERSION.SDK_INT>=30){
            val mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(light)mask else 0,mask)
        }else @Suppress("DEPRECATION") run{window.decorView.systemUiVisibility=if(light)View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0}
    }
    /** The app follows the phone's dark mode, also when it changes while open. */
    override fun onConfigurationChanged(config:android.content.res.Configuration){
        super.onConfigurationChanged(config)
        if(night(config)!=NativeUi.dark)recreate()
    }
    override fun onCreate(state:Bundle?){
        NativeUi.dark=night(resources.configuration)
        super.onCreate(state)
        terrain=TerrainView(this)
        val root=FrameLayout(this)
        val mapHost=FrameLayout(this)
        root.addView(mapHost,FrameLayout.LayoutParams(-1,-1))
        mapHost.addView(terrain,FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener{view,insets->if(android.os.Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom)}else{view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)};insets}
        val status=TextView(this).apply{
            text="Loading map…"
            textSize=11f
            setPadding(dp(8),dp(5),dp(8),dp(5))
            background=NativeUi.background(this@MainActivity,NativeUi.glass,14)
            setTextColor(NativeUi.ink)
        }
        // Small notes at the top of the map, one under another: loading and
        // connection messages, and "Drawing route" while a route is drawn.
        topNotes=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
        topNotes.addView(status,LinearLayout.LayoutParams(-2,-2))
        mapHost.addView(topNotes,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62)})
        // A missing source (no connection): shown once, for a few seconds, then
        // not again until the phone's connection comes back.
        val hideFailure=Runnable{status.visibility=View.GONE}
        terrain.onStatus={message->
            val failure=message.startsWith("Terrain load failed")||message=="Imagery metadata unavailable"
            if(!(failure&&failureShown)){
                if(failure){failureShown=true;status.removeCallbacks(hideFailure);status.postDelayed(hideFailure,FAILURE_MILLIS)}
                if(status.text.toString()!=message)status.text=message;status.visibility=if(message.isEmpty())View.GONE else View.VISIBLE
            }
        }
        runCatching{getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network)}
        val attribution=TextView(this).apply{
            text=Html.fromHtml(
                "<a href=\"https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution\">LINZ imagery, elevation &amp; topography · CC BY 4.0 &amp; contributors</a><br>"+
                "Imagery gaps: <a href=\"https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9\">Esri World Imagery &amp; contributors</a><br>"+
                "Hunting areas, tracks &amp; huts: <a href=\"https://www.doc.govt.nz/about-us/our-policies-and-profile/copyright/\">Department of Conservation · CC BY 4.0</a>",
                Html.FROM_HTML_MODE_LEGACY)
            textSize=10f;movementMethod=LinkMovementMethod.getInstance()
            setTextColor(NativeUi.ink);setLinkTextColor(NativeUi.ink)
            setPadding(dp(8),dp(4),dp(8),dp(4))
            background=NativeUi.background(this@MainActivity,NativeUi.glass,12);visibility=View.GONE
        }
        // Capacitor compact attribution: an "i" button toggles the credits.
        val info=MapControls.iconButton(this,MapControls.InfoIcon(this),"Show map attribution"){attribution.visibility=if(attribution.visibility==View.VISIBLE)View.GONE else View.VISIBLE}.apply{background=NativeUi.pressable(this@MainActivity,null);elevation=0f}
        mapHost.addView(attribution,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{marginStart=dp(60);marginEnd=dp(44);bottomMargin=dp(8)})
        mapHost.addView(info,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.BOTTOM or Gravity.END).apply{marginEnd=dp(6);bottomMargin=dp(6)})
        val scale=MapControls.Scale(this)
        mapHost.addView(scale,FrameLayout.LayoutParams(dp(102),dp(20),Gravity.BOTTOM or Gravity.START).apply{marginStart=dp(12);bottomMargin=dp(14)})
        setContentView(root)
        // Behind the status and navigation bars (the root's padding): the app's background.
        root.setBackgroundColor(NativeUi.paper);systemBars()
        loader=TileLoader(this,terrain)
        mapLayers=MapLayers(terrain,loader)
        terrain.onCamera={loader.cameraChanged();mapLayers.refresh()};terrain.onData={loader.cameraChanged();mapLayers.refresh()}
        planner=PlannerUi(this,root,terrain,loader,mapHost)
        offlineUi=OfflineUi(this,root,terrain,loader,false).also{it.routes=planner.controller}
        planner.onOffline={layersUi.dismiss();offlineUi.startRegion()}
        planner.savedRegions={list,close->offlineUi.fillSavedRegions(list,close)}
        // Tap a track name for its summary; tap inside a hunting permit area for its details.
        // A tap elsewhere leaves an open summary as it is (it closes with its X).
        trackSheet=TrackSheet(this,root,loader,mapHost)
        trackSheet.onLine={line,part->planner.highlightTrack(line,part)}
        trackSheet.onClosed={planner.relayout()}
        trackSheet.onPoint={terrain.inspect(it)}
        trackSheet.onRange={terrain.rangeMarkers(it)}
        trackSheet.mapLine={name,at,callback->mapLayers.trackLine(name,at,callback)}
        trackSheet.walkRoute={guid,page,callback->mapLayers.walkRoute(guid,page,null,callback=callback)}
        trackSheet.alongLine={corridor,callback->mapLayers.alongLine(corridor,callback)}
        trackSheet.walkOptions={guid,legs,starts,walk,time,doc,callback->mapLayers.walkOptions(guid,legs,starts,walk,time,doc,callback)}
        trackSheet.extendWalk={line,target,callback->mapLayers.extendWalk(line,target,callback)}
        trackSheet.closeWalk={line,target,way,callback->mapLayers.closeWalk(line,target,way,callback)}
        val walkRecords=WalkRecordStore(assets)
        trackSheet.walkRecord={guid,callback->walkRecords.get(guid,callback)}
        trackSheet.followWalk={sections,walk,distance,time,doc,current,callback->mapLayers.followWalk(sections,walk,distance,time,doc,current,callback)}
        // Frame the track once the map has its new size (the sheet resizes it).
        trackSheet.onFit={line->mapHost.post{terrain.post{terrain.focus(line)}}}
        // Search: DOC walks (by their page's id) and huts open their summaries.
        planner.onPlace={place->
            val at=Coordinate(place.longitude,place.latitude)
            val guid=DocTracks.guidOf(place.link)
            when{
                place.kind=="walk"&&guid!=null->{trackSheet.showWalk(guid,place.name,at,true);true}
                place.kind=="hut"->{trackSheet.showHut(place.name,at);true}
                else->false
            }
        }
        trackSheet.walkInfo={mapLayers.walkInfo(it)}
        planner.hutAt={x,y->mapLayers.hutAt(x,y)}
        planner.onIdleTap={x,y->
            val label=mapLayers.labelAt(x,y)
            val at=label?.let{Coordinate(it.point.x*360-180,CameraMath.latitude(it.point.y))}
            // A hut's dot (not only its name) opens the hut, marked on the map.
            val hut=if(label==null)mapLayers.hutAt(x,y)else null
            when{
                hut!=null->trackSheet.showHut(hut.first,hut.second)
                label!=null&&at!=null&&label.key.startsWith(TopographyStyle.HUT_LABELS+":")->trackSheet.showHut(label.text,at)
                label!=null&&at!=null&&label.ref.isNotEmpty()&&!label.section->trackSheet.showWalk(label.ref,label.text,at)
                // A section under its own name: in the walk the map gives it.
                label!=null&&at!=null->trackSheet.show(label.text.removeSuffix(" (mapped closed)"),at,label.ref.takeIf{label.section})
                // The line itself, not only its name: the track or walk nearest the tap,
                // else the hunting areas there.
                else->terrain.pick(x,y){hit->if(hit==null)return@pick
                    val tapped=Coordinate(hit[0],hit[1])
                    mapLayers.trackAt(hit[0],hit[1],terrain.cameraPose.zoom){track->when{
                        track!=null&&track.walk->trackSheet.showWalk(track.ref,track.name,tapped)
                        track!=null->trackSheet.show(track.name.removeSuffix(" (mapped closed)"),tapped,track.ref.takeIf{track.section})
                        loader.mapSettings.state.hunting->mapLayers.huntingAt(hit[0],hit[1]){areas->HuntingDetails.show(this,areas)}
                    }}}
            }
        }
        planner.onProfilePoint={terrain.inspect(it)}
        layersUi=LayersUi(this,root,terrain,loader,offlineUi){planner.newRoute()}
        location=LocationUi(this,mapHost,terrain,false)
        // While drawing: a small note like the status message; tap it to finish.
        val drawingNote=TextView(this).apply{
            text="Drawing route  ·  Done";textSize=11f;setPadding(dp(10),dp(5),dp(10),dp(5))
            background=NativeUi.background(this@MainActivity,NativeUi.glass,14);setTextColor(NativeUi.ink);visibility=View.GONE
            contentDescription="Drawing route. Tap to finish.";isClickable=true;setOnClickListener{planner.controller.drawing(false)}}
        topNotes.addView(drawingNote,LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(4)})
        planner.trackWay={from,to,reach,done->mapLayers.followTracks(from,to,reach,done)}
        planner.onDrawing={drawingNote.visibility=if(it)View.VISIBLE else View.GONE}
        // Capacitor layout: kea launcher and locate (top left), 2D/3D switch,
        // compass and layers (top right). No zoom buttons: pinch and double-tap zoom.
        val launcher=MapControls.logo(this){layersUi.dismiss();planner.showRoutes()}
        mapHost.addView(launcher,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.START).apply{marginStart=dp(10);topMargin=dp(10)})
        mapHost.addView(location.button,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.START).apply{marginStart=dp(10);topMargin=dp(62)})
        val perspective=MapControls.Perspective(this){enabled->loader.mapSettings.change{it.copy(threeD=enabled,base=if(enabled&&it.base==MapSettings.Base.TOPO)it.lastPhoto else it.base)}}
        perspective.set(loader.mapSettings.state.threeD)
        mapHost.addView(perspective,FrameLayout.LayoutParams(-2,dp(34),Gravity.TOP or Gravity.END).apply{marginEnd=dp(54);topMargin=dp(10)})
        val compass=MapControls.Compass(this){terrain.northUp()}
        mapHost.addView(compass,FrameLayout.LayoutParams(dp(34),dp(34),Gravity.TOP or Gravity.END).apply{marginEnd=dp(10);topMargin=dp(10)})
        mapHost.addView(MapControls.iconButton(this,MapControls.LayersIcon(this),"Map layers"){planner.dismissDrawer();layersUi.show()},FrameLayout.LayoutParams(dp(34),dp(34),Gravity.TOP or Gravity.END).apply{marginEnd=dp(10);topMargin=dp(52)})
        // Keep the scale bar, "i" and credits clear of the route sheet's top edge.
        trackSheet.onCover={cover->planner.onMapCover(cover)}
        planner.onMapCover={cover->
            for((view,margin) in listOf(scale to 14,info to 6,attribution to 8)){val lp=view.layoutParams as FrameLayout.LayoutParams;if(lp.bottomMargin!=dp(margin)+cover){lp.bottomMargin=dp(margin)+cover;view.layoutParams=lp}}
        }
        // Android redraws (and recomposites) the controls layer whenever a view
        // changes. While moving, the scale bar updates at most 4 times a second,
        // plus once when the camera settles; the compass only when the bearing changes.
        var scaleAt=0L
        val settleScale=Runnable{scaleAt=android.os.SystemClock.uptimeMillis();scale.update(terrain.cameraPose)}
        fun updateChrome(){
            compass.bearing=terrain.bearing
            val now=android.os.SystemClock.uptimeMillis()
            scale.removeCallbacks(settleScale)
            if(!terrain.gestureInProgress||now-scaleAt>=250){scaleAt=now;scale.update(terrain.cameraPose)}
            scale.postDelayed(settleScale,300)
        }
        updateChrome()
        terrain.onCamera={updateChrome();loader.cameraChanged();mapLayers.refresh();planner.cameraChanged()}
        loader.mapSettings.onChange={before,after->perspective.set(after.threeD);terrain.maxZoom=if(after.base==MapSettings.Base.TOPO)16.0 else 19.0;if(before.threeD!=after.threeD)terrain.dimension(after.threeD);loader.changeMapStyle(before,after);mapLayers.refresh()}
        loader.externalDiagnostics={mapLayers.snapshot()}
        if(!loader.mapSettings.state.threeD)terrain.dimension(false)
        if(loader.mapSettings.state.base==MapSettings.Base.TOPO)terrain.maxZoom=16.0
        terrain.configure(loader.mapSettings.state)
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){if(!layersUi.dismiss()&&!trackSheet.back()&&!offlineUi.back()&&!planner.back())finish()}
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN&&::layersUi.isInitialized)layersUi.touchDown(event.rawX,event.rawY)
        return super.dispatchTouchEvent(event)
    }
    override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true);if(::loader.isInitialized)loader.setActive(true);if(::mapLayers.isInitialized)mapLayers.setActive(true);if(::planner.isInitialized)planner.resume()}
    @Deprecated("Legacy Android back dispatch")
    // API 33+ uses the OnBackInvokedDispatcher registered in onCreate above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed(){if((!::layersUi.isInitialized||!layersUi.dismiss())&&(!::trackSheet.isInitialized||!trackSheet.back())&&(!::offlineUi.isInitialized||!offlineUi.back())&&(!::planner.isInitialized||!planner.back()))super.onBackPressed()}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(request,permissions,grants);if(::location.isInitialized&&grants.any{it==android.content.pm.PackageManager.PERMISSION_GRANTED})location.permission(request)}
    override fun onActivityResult(request:Int,result:Int,data:android.content.Intent?){super.onActivityResult(request,result,data);if(::planner.isInitialized)planner.result(request,result,data);if(::offlineUi.isInitialized)offlineUi.result(request,result,data)}
    override fun onPause(){if(::mapLayers.isInitialized)mapLayers.setActive(false);if(::location.isInitialized)location.stop();if(::planner.isInitialized)planner.pause();if(::terrain.isInitialized)terrain.setActive(false);if(::loader.isInitialized)loader.setActive(false);super.onPause()}
    override fun onTrimMemory(level:Int){
        super.onTrimMemory(level)
        if(level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL||level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND){
            if(::loader.isInitialized)loader.memoryPressure();if(::terrain.isInitialized)terrain.memoryPressure()
        }
    }
    private lateinit var topNotes:LinearLayout
    /** A connection failure note was shown: not again until the connection returns. */
    @Volatile private var failureShown=false
    private val network=object:android.net.ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:android.net.Network){failureShown=false}
    }
    private companion object{const val FAILURE_MILLIS=4000L}
    override fun onDestroy(){runCatching{getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(network)};if(::trackSheet.isInitialized)trackSheet.close();if(::mapLayers.isInitialized)mapLayers.close();if(::planner.isInitialized)planner.close();if(::loader.isInitialized)loader.close();if(::terrain.isInitialized)terrain.close();super.onDestroy()}
}
