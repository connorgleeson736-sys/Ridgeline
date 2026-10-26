package nz.ridgeline.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Foreground-only GPS. Never keeps a listener running after the activity stops. */
@Composable
fun GpsLocation(enabled:Boolean,onFix:(Location)->Unit,onError:(String)->Unit) {
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current.lifecycle
    val fix by rememberUpdatedState(onFix); val error by rememberUpdatedState(onError)
    DisposableEffect(enabled,lifecycle) {
        val manager=context.getSystemService(LocationManager::class.java)
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) { fix(location) }
            override fun onProviderEnabled(provider:String) = Unit
            @Deprecated("Required for Android 8–10 LocationListener compatibility")
            override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?) = Unit
            override fun onProviderDisabled(provider:String) { error("Location is disabled. Enable it in Android settings.") }
        }
        fun start() {
            if(!enabled)return
            val fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val coarse=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(!fine && !coarse) { error("Location permission is required to show your position"); return }
            val provider=if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER))LocationManager.GPS_PROVIDER
                else if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))LocationManager.NETWORK_PROVIDER else null
            if(provider==null) { error("Enable device location to find your position"); return }
            try { manager.requestLocationUpdates(provider,3000L,5f,listener,Looper.getMainLooper()) }
            catch(_:SecurityException) { error("Location permission is unavailable") }
        }
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_RESUME -> start()
            Lifecycle.Event.ON_PAUSE -> manager.removeUpdates(listener)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); manager.removeUpdates(listener) }
    }
}
