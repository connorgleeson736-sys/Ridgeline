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
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        terrain=TerrainView(this)
        val root=FrameLayout(this)
        root.addView(terrain,FrameLayout.LayoutParams(-1,-1))
        val status=TextView(this).apply{
            text="Native terrain prototype · starting GPU"
            setPadding(dp(12),dp(8),dp(76),dp(8))
            setBackgroundColor(Color.argb(220,255,255,250))
            setTextColor(Color.rgb(40,55,48))
        }
        root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        terrain.onStatus={status.text=it;status.visibility=if(it.isEmpty())View.GONE else View.VISIBLE}
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun control(label:String,description:String,action:()->Unit){
            controls.addView(Button(this).apply{
                text=label;textSize=20f;contentDescription=description
                setTextColor(Color.rgb(40,55,48))
                setPadding(0,0,0,0);minimumWidth=0;minimumHeight=0
                setOnClickListener{action()}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
        }
        control("+","Zoom in"){terrain.zoomBy(1.0)}
        control("−","Zoom out"){terrain.zoomBy(-1.0)}
        control("N","Reset bearing to north"){terrain.northUp()}
        root.addView(controls,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{
            topMargin=dp(12);marginEnd=dp(12)
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
        root.addView(attribution,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        loader=TileLoader(this,terrain)
        terrain.onCamera={loader.cameraChanged()};terrain.onData={loader.cameraChanged()}
        planner=PlannerUi(this,root,terrain,loader)
        offlineUi=OfflineUi(this,root,terrain,loader)
        location=LocationUi(this,root,terrain)
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){if(!planner.back())finish()}
    }
    override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true);if(::loader.isInitialized)loader.setActive(true)}
    @Deprecated("Legacy Android back dispatch")
    override fun onBackPressed(){if(!::planner.isInitialized||!planner.back())super.onBackPressed()}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(request,permissions,grants);if(::location.isInitialized&&grants.any{it==android.content.pm.PackageManager.PERMISSION_GRANTED})location.permission(request)}
    override fun onActivityResult(request:Int,result:Int,data:android.content.Intent?){super.onActivityResult(request,result,data);if(::planner.isInitialized)planner.result(request,result,data);if(::offlineUi.isInitialized)offlineUi.result(request,result,data)}
    override fun onPause(){if(::location.isInitialized)location.stop();if(::planner.isInitialized)planner.pause();if(::terrain.isInitialized)terrain.setActive(false);if(::loader.isInitialized)loader.setActive(false);super.onPause()}
    override fun onDestroy(){if(::planner.isInitialized)planner.close();if(::loader.isInitialized)loader.close();if(::terrain.isInitialized)terrain.close();super.onDestroy()}
}
