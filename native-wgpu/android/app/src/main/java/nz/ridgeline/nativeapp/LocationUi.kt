package nz.ridgeline.nativeapp
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
/** One-shot native location avoids background tracking and its battery cost. */
class LocationUi(private val activity:Activity,root:FrameLayout,private val terrain:TerrainView):LocationListener{
    private val manager=activity.getSystemService(LocationManager::class.java)
    private val main=Handler(Looper.getMainLooper());private var waiting=false
    private val button=Button(activity).apply{text="Locate";textSize=12f;contentDescription="Center map on current location";setOnClickListener{if(waiting)stop()else locate()}}
    private val timeout=Runnable{stop();Toast.makeText(activity,"No location fix yet. Check location services and try again.",Toast.LENGTH_LONG).show()}
    init{val scale=activity.resources.displayMetrics.density;root.addView(button,FrameLayout.LayoutParams(-2,(48*scale).toInt(),Gravity.TOP or Gravity.START).apply{leftMargin=(12*scale).toInt();topMargin=(166*scale).toInt()})}
    private fun locate(){
        if(activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),4201);return}
        try{
            val fine=activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val providers=manager.getProviders(true).filter{it==LocationManager.NETWORK_PROVIDER||(fine&&it==LocationManager.GPS_PROVIDER)}
            if(providers.isEmpty()){Toast.makeText(activity,"Enable location services to locate yourself.",Toast.LENGTH_LONG).show();return}
            val recent=providers.mapNotNull{manager.getLastKnownLocation(it)}.filter{System.currentTimeMillis()-it.time<60000&&it.accuracy<100}.minByOrNull{it.accuracy}
            if(recent!=null){onLocationChanged(recent);return}
            waiting=true;button.text="Cancel GPS"
            for(provider in providers)manager.requestLocationUpdates(provider,1000,0f,this,Looper.getMainLooper())
            main.postDelayed(timeout,30000)
        }catch(_:SecurityException){stop();Toast.makeText(activity,"Location permission is needed.",Toast.LENGTH_LONG).show()}
    }
    fun permission(request:Int){if(request==4201)locate()}
    override fun onLocationChanged(location:Location){
        stop();val p=runCatching{Coordinate(location.longitude,location.latitude)}.getOrNull()?:return
        if(p.inNewZealand()){terrain.goTo(p);Toast.makeText(activity,"Location accuracy: about ${location.accuracy.toInt()} m",Toast.LENGTH_SHORT).show()}
        else Toast.makeText(activity,"Your location is outside the New Zealand map area.",Toast.LENGTH_LONG).show()
    }
    override fun onProviderDisabled(provider:String){}
    override fun onProviderEnabled(provider:String){}
    override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
    fun stop(){waiting=false;button.text="Locate";main.removeCallbacks(timeout);runCatching{manager.removeUpdates(this)}}
}
