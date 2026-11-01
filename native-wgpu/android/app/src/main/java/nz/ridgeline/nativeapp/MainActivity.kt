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
            text="Native terrain prototype · starting GPU"
            textSize=11f
            setPadding(dp(8),dp(5),dp(8),dp(5))
            setBackgroundColor(Color.argb(220,255,255,250))
            setTextColor(Color.rgb(40,55,48))
        }
        mapHost.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(62)})
        terrain.onStatus={status.text=it;status.visibility=if(it.isEmpty())View.GONE else View.VISIBLE}
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun control(label:String,description:String,action:()->Unit){
            controls.addView(NativeUi.button(this,label,action=action).apply{
                textSize=20f;contentDescription=description
                setTextColor(Color.rgb(40,55,48))
                setPadding(0,0,0,0);minimumWidth=0;minimumHeight=0
                setOnClickListener{action()}
            },LinearLayout.LayoutParams(dp(44),dp(44)).apply{bottomMargin=dp(4)})
        }
        control("+","Zoom in"){terrain.zoomBy(1.0)}
        control("−","Zoom out"){terrain.zoomBy(-1.0)}
        control("N","Reset bearing to north"){terrain.northUp()}
        mapHost.addView(controls,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{
            topMargin=dp(85);marginEnd=dp(10)
        })
        val attribution=TextView(this).apply{
            text=Html.fromHtml(
                "<a href=\"https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution\">LINZ imagery &amp; elevation · CC BY 4.0 &amp; contributors</a><br>"+
                "Imagery gaps: <a href=\"https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9\">Esri World Imagery &amp; contributors</a>",
                Html.FROM_HTML_MODE_LEGACY)
            textSize=10f;movementMethod=LinkMovementMethod.getInstance()
            setTextColor(Color.rgb(40,55,48));setLinkTextColor(Color.rgb(40,55,48))
            setPadding(dp(8),dp(4),dp(8),dp(4))
            setBackgroundColor(Color.argb(220,255,255,250))
        }
        mapHost.addView(attribution,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        loader=TileLoader(this,terrain)
        mapLayers=MapLayers(terrain,loader)
        terrain.onCamera={loader.cameraChanged();mapLayers.refresh()};terrain.onData={loader.cameraChanged();mapLayers.refresh()}
        planner=PlannerUi(this,root,terrain,loader,mapHost)
        offlineUi=OfflineUi(this,root,terrain,loader,false)
        planner.onOffline={offlineUi.show()}
        layersUi=LayersUi(this,root,terrain,loader,offlineUi){planner.newRoute()}
        location=LocationUi(this,mapHost,terrain)
        val launcher=NativeUi.button(this,"RIDGELINE ☰"){layersUi.dismiss();planner.showRoutes()}
        mapHost.addView(launcher,FrameLayout.LayoutParams(-2,dp(44),Gravity.TOP or Gravity.START).apply{marginStart=dp(10);topMargin=dp(10)})
        mapHost.addView(NativeUi.button(this,"Layers"){planner.dismissDrawer();layersUi.show()},FrameLayout.LayoutParams(dp(86),dp(44),Gravity.TOP or Gravity.END).apply{marginEnd=dp(10);topMargin=dp(10)})
        controls.addView(NativeUi.button(this,"2D"){terrain.toggleDimension()}.apply{contentDescription="Switch between 2D and 3D"},LinearLayout.LayoutParams(dp(44),dp(44)))
        loader.mapSettings.onChange={before,after->loader.changeMapStyle(before,after);mapLayers.refresh()}
        loader.externalDiagnostics={mapLayers.snapshot()}
        terrain.configure(loader.mapSettings.state)
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){if(!layersUi.dismiss()&&!planner.back())finish()}
    }
    override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true);if(::loader.isInitialized)loader.setActive(true);if(::mapLayers.isInitialized)mapLayers.setActive(true)}
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
