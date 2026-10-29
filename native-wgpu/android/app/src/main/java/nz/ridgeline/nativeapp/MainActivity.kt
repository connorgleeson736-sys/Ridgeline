package nz.ridgeline.nativeapp
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
class MainActivity:Activity(){
 private lateinit var terrain:TerrainView
 private lateinit var loader:TileLoader
 override fun onCreate(state:Bundle?){super.onCreate(state);terrain=TerrainView(this);val root=FrameLayout(this);root.addView(terrain,FrameLayout.LayoutParams(-1,-1));val status=TextView(this).apply{text="Native terrain prototype · starting GPU";setPadding(24,16,24,16);setBackgroundColor(Color.argb(220,255,255,250));setTextColor(Color.rgb(40,55,48))};root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP));terrain.onStatus={status.text=it;status.visibility=if(it.isEmpty())android.view.View.GONE else android.view.View.VISIBLE};setContentView(root);loader=TileLoader(this,terrain);terrain.onCamera={loader.cameraChanged()};terrain.onData={loader.cameraChanged()}}
 override fun onResume(){super.onResume();if(::terrain.isInitialized)terrain.setActive(true)}
 override fun onPause(){if(::terrain.isInitialized)terrain.setActive(false);super.onPause()}
 override fun onDestroy(){if(::loader.isInitialized)loader.close();if(::terrain.isInitialized)terrain.close();super.onDestroy()}
}
