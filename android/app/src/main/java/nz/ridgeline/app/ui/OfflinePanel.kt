@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package nz.ridgeline.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import nz.ridgeline.app.*
import nz.ridgeline.app.model.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun OfflinePanel(state:PlannerState,model:PlannerViewModel,onDraw:()->Unit) {
    val context=LocalContext.current
    val manager=remember { WorkManager.getInstance(context) }
    val jobs by manager.getWorkInfosByTagFlow("offline-maps").collectAsStateWithLifecycle(initialValue=emptyList())
    val busy=jobs.any { !it.state.isFinished }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var manual by remember { mutableStateOf(false) }
    var storage by remember { mutableLongStateOf(0L) }
    LaunchedEffect(jobs.map { it.id to it.state },state.regions) { storage=withContext(Dispatchers.IO) { (context.applicationContext as RidgelineApplication).tiles.bytesUsed() } }
    PanelTitle("Offline regions","Download only the places you choose")
    Text("Draw a rectangle with two corner taps, or enter its coordinates. Each download includes satellite imagery, topographic maps, and ground elevation up to your chosen detail. Outside saved tiles, maps need a connection.",style=MaterialTheme.typography.bodySmall)
    Text(String.format(Locale.ROOT,"Saved tile storage: %.1f MB",storage/1_000_000.0),style=MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=onDraw,enabled=state.keyConfigured) { Text("Draw region") }
        OutlinedButton(onClick={manual=!manual},enabled=state.keyConfigured) { Text("Enter bounds") }
    }
    if(!state.keyConfigured) Text("Add your LINZ API key in Settings to enable offline downloads.",color=MaterialTheme.colorScheme.secondary)
    if(manual) {
        var west by remember { mutableStateOf("170.05") }; var east by remember { mutableStateOf("170.25") }
        var south by remember { mutableStateOf("-43.70") }; var north by remember { mutableStateOf("-43.50") }
        @Composable fun field(value:String,label:String,change:(String)->Unit,modifier:Modifier) {
            OutlinedTextField(value,change,label={Text(label)},singleLine=true,modifier=modifier,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { field(west,"West longitude",{west=it},Modifier.weight(1f));field(east,"East longitude",{east=it},Modifier.weight(1f)) }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { field(south,"South latitude",{south=it},Modifier.weight(1f));field(north,"North latitude",{north=it},Modifier.weight(1f)) }
        OutlinedButton(onClick={ runCatching { Bounds(west.toDouble(),south.toDouble(),east.toDouble(),north.toDouble()) }
            .onSuccess { model.regionBounds(it);manual=false }.onFailure { model.message(it.message ?: "Check the coordinates") } }) { Text("Review region") }
    }
    state.regionBounds?.let { bounds ->
        var name by remember(bounds) { mutableStateOf("Offline region") }
        var zoom by remember(bounds) { mutableFloatStateOf(14f) }
        val region=remember(bounds,zoom) { OfflineRegion("preview",name,bounds,zoom.toInt()) }
        val count=remember(region) { region.tileCount() }
        HorizontalDivider()
        Text("Review download",fontWeight=FontWeight.SemiBold)
        Text("${bounds.south} to ${bounds.north}° latitude\n${bounds.west} to ${bounds.east}° longitude",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{ name=it.take(80) },label={Text("Region name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Maximum tile zoom: ${zoom.toInt()} · ${if(zoom>=15)"Fine" else if(zoom>=13)"Standard" else "Overview"}")
        Slider(zoom,{zoom=it},valueRange=10f..16f,steps=5)
        if(count>OfflineRegion.MAX_TILES) Text("More than ${OfflineRegion.MAX_TILES} tiles. Lower detail or draw a smaller region.",color=MaterialTheme.colorScheme.error)
        else Text("$count tiles · roughly ${count*15/1024}–${count*100/1024} MB. Actual size depends on imagery. Higher zoom increases downloads quickly.",style=MaterialTheme.typography.bodySmall)
        Text("Saved detail is finite. Zooming closer than level ${zoom.toInt()} reuses saved parent tiles while offline. Downloads respect provider access; an invalid key or unavailable tile stops the download.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={
                if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                model.downloadRegion(name,bounds,zoom.toInt())
            },enabled=count<=OfflineRegion.MAX_TILES && state.keyConfigured) {Text("Download region")}
            TextButton(onClick=model::cancelRegion) {Text("Cancel")}
        }
    }
    if(busy) OutlinedButton(onClick={ manager.cancelUniqueWork("offline-maps") }) {Text("Pause downloads")}
    if(state.regions.isNotEmpty()) { HorizontalDivider();Text("On this device",fontWeight=FontWeight.SemiBold) }
    state.regions.forEach { region ->
        val related=jobs.filter { "region:${region.id}" in it.tags }
        val work=related.firstOrNull { it.state==androidx.work.WorkInfo.State.RUNNING }
            ?: related.firstOrNull { !it.state.isFinished } ?: related.lastOrNull()
        val active=jobs.any { "region:${region.id}" in it.tags && !it.state.isFinished }
        val done=work?.progress?.getInt("done",0) ?: 0
        val total=work?.progress?.getInt("total",0) ?: 0
        val error=work?.outputData?.getString("error")
        Text(region.name,fontWeight=FontWeight.Medium)
        Text(if(region.completed)"Ready offline · zoom ${region.maxZoom}" else if(active)"${work?.state?.name?.lowercase()} · $done / ${if(total>0)total else region.tileCount()} tiles" else error ?: "Incomplete · resume to finish",style=MaterialTheme.typography.bodySmall)
        if(active)LinearProgressIndicator(progress={ if(total>0)done.toFloat()/total else 0f },modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!region.completed)TextButton(onClick={model.resumeRegion(region.id)},enabled=!active && state.keyConfigured) {Text("Resume")}
            TextButton(onClick={model.flyTo(Coordinate((region.bounds.west+region.bounds.east)/2,(region.bounds.south+region.bounds.north)/2))}) {Text("Show on map")}
            TextButton(onClick={model.deleteRegion(region.id)},enabled=!busy) {Text("Delete download")}
        }
    }
}
