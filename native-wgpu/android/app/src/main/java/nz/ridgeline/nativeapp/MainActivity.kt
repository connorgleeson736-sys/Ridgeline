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
    private lateinit var location:LocationUi
    private lateinit var offlineUi:OfflineUi
    private lateinit var layersUi:LayersUi
    private lateinit var mapLayers:MapLayers
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    override fun onCreate(state:Bundle?){
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
            background=NativeUi.background(this@MainActivity,Color.argb(235,255,255,255),14)
            setTextColor(Color.rgb(40,55,48))
        }
        mapHost.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62)})
        terrain.onStatus={if(status.text.toString()!=it)status.text=it;status.visibility=if(it.isEmpty())View.GONE else View.VISIBLE}
        val attribution=TextView(this).apply{
            text=Html.fromHtml(
                "<a href=\"https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution\">LINZ imagery, elevation &amp; topography · CC BY 4.0 &amp; contributors</a><br>"+
                "Imagery gaps: <a href=\"https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9\">Esri World Imagery &amp; contributors</a>",
                Html.FROM_HTML_MODE_LEGACY)
            textSize=10f;movementMethod=LinkMovementMethod.getInstance()
            setTextColor(Color.rgb(40,55,48));setLinkTextColor(Color.rgb(40,55,48))
            setPadding(dp(8),dp(4),dp(8),dp(4))
            background=NativeUi.background(this@MainActivity,Color.argb(235,255,255,255),12);visibility=View.GONE
        }
        // Capacitor compact attribution: an "i" button toggles the credits.
        val info=MapControls.iconButton(this,MapControls.InfoIcon(this),"Show map attribution"){attribution.visibility=if(attribution.visibility==View.VISIBLE)View.GONE else View.VISIBLE}.apply{background=NativeUi.pressable(this@MainActivity,null);elevation=0f}
        mapHost.addView(attribution,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{marginStart=dp(60);marginEnd=dp(44);bottomMargin=dp(8)})
        mapHost.addView(info,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.BOTTOM or Gravity.END).apply{marginEnd=dp(6);bottomMargin=dp(6)})
        val scale=MapControls.Scale(this)
        mapHost.addView(scale,FrameLayout.LayoutParams(dp(102),dp(20),Gravity.BOTTOM or Gravity.START).apply{marginStart=dp(12);bottomMargin=dp(14)})
        setContentView(root)
        loader=TileLoader(this,terrain)
        mapLayers=MapLayers(terrain,loader)
        terrain.onCamera={loader.cameraChanged();mapLayers.refresh()};terrain.onData={loader.cameraChanged();mapLayers.refresh()}
        planner=PlannerUi(this,root,terrain,loader,mapHost)
        offlineUi=OfflineUi(this,root,terrain,loader,false)
        planner.onOffline={offlineUi.show()}
        planner.onProfilePoint={terrain.inspect(it)}
        layersUi=LayersUi(this,root,terrain,loader,offlineUi){planner.newRoute()}
        location=LocationUi(this,mapHost,terrain,false)
        val drawingBanner=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=NativeUi.background(this@MainActivity,NativeUi.paper,8,true);visibility=View.GONE}
        drawingBanner.addView(NativeUi.text(this,"Drawing route").apply{setPadding(dp(10),0,dp(10),0)})
        drawingBanner.addView(NativeUi.button(this,"Done"){planner.controller.drawing(false)})
        mapHost.addView(drawingBanner,FrameLayout.LayoutParams(-2,dp(44),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62)})
        planner.onDrawing={drawingBanner.visibility=if(it)View.VISIBLE else View.GONE}
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
        planner.onMapCover={cover->
            for((view,margin) in listOf(scale to 14,info to 6,attribution to 8)){val lp=view.layoutParams as FrameLayout.LayoutParams;if(lp.bottomMargin!=dp(margin)+cover){lp.bottomMargin=dp(margin)+cover;view.layoutParams=lp}}
        }
        fun updateChrome(){compass.bearing=terrain.bearing;scale.update(terrain.cameraPose)}
        updateChrome()
        terrain.onCamera={updateChrome();loader.cameraChanged();mapLayers.refresh();planner.cameraChanged()}
        loader.mapSettings.onChange={before,after->perspective.set(after.threeD);terrain.maxZoom=if(after.base==MapSettings.Base.TOPO)16.0 else 19.0;if(before.threeD!=after.threeD)terrain.dimension(after.threeD);loader.changeMapStyle(before,after);mapLayers.refresh()}
        loader.externalDiagnostics={mapLayers.snapshot()}
        if(!loader.mapSettings.state.threeD)terrain.dimension(false)
        if(loader.mapSettings.state.base==MapSettings.Base.TOPO)terrain.maxZoom=16.0
        terrain.configure(loader.mapSettings.state)
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){if(!layersUi.dismiss()&&!planner.back())finish()}
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN&&::layersUi.isInitialized)layersUi.touchDown(event.rawX,event.rawY)
        return super.dispatchTouchEvent(event)
    }
    override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true);if(::loader.isInitialized)loader.setActive(true);if(::mapLayers.isInitialized)mapLayers.setActive(true);if(::planner.isInitialized)planner.resume()}
    @Deprecated("Legacy Android back dispatch")
    // API 33+ uses the OnBackInvokedDispatcher registered in onCreate above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed(){if((!::layersUi.isInitialized||!layersUi.dismiss())&&(!::planner.isInitialized||!planner.back()))super.onBackPressed()}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(request,permissions,grants);if(::location.isInitialized&&grants.any{it==android.content.pm.PackageManager.PERMISSION_GRANTED})location.permission(request)}
    override fun onActivityResult(request:Int,result:Int,data:android.content.Intent?){super.onActivityResult(request,result,data);if(::planner.isInitialized)planner.result(request,result,data);if(::offlineUi.isInitialized)offlineUi.result(request,result,data)}
    override fun onPause(){if(::mapLayers.isInitialized)mapLayers.setActive(false);if(::location.isInitialized)location.stop();if(::planner.isInitialized)planner.pause();if(::terrain.isInitialized)terrain.setActive(false);if(::loader.isInitialized)loader.setActive(false);super.onPause()}
    override fun onTrimMemory(level:Int){
        super.onTrimMemory(level)
        if(level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL||level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND){
            if(::loader.isInitialized)loader.memoryPressure();if(::terrain.isInitialized)terrain.memoryPressure()
        }
    }
    override fun onDestroy(){if(::mapLayers.isInitialized)mapLayers.close();if(::planner.isInitialized)planner.close();if(::loader.isInitialized)loader.close();if(::terrain.isInitialized)terrain.close();super.onDestroy()}
}
