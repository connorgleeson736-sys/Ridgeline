@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package nz.ridgeline.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import nz.ridgeline.app.*
import nz.ridgeline.app.map.*
import nz.ridgeline.app.model.*
import org.json.JSONObject
import java.util.Locale

private val Warm=Color(0xFFD77548)
private enum class Panel { ROUTES, OFFLINE, SETTINGS, SEARCH, ROUTE }
private fun distance(m:Double)=if(m>=1000)String.format(Locale.ROOT,"%.2f km",m/1000) else "${m.toInt()} m"
private fun number(v:Double)=String.format(Locale.ROOT,"%.4f",v)

@Composable
fun PlannerScreen(model:PlannerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val handle=remember { MapHandle() }
    var headerPixels by remember { mutableIntStateOf(0) }
    var footerPixels by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var basemap by rememberSaveable { mutableStateOf(Basemap.AERIAL) }
    var shade by rememberSaveable { mutableStateOf(true) }
    var lowPower by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var gpsEnabled by rememberSaveable { mutableStateOf(false) }
    var gps by remember { mutableStateOf<Coordinate?>(null) }
    var gpsAccuracy by remember { mutableStateOf<Float?>(null) }
    var centerNextFix by remember { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    val key=remember(state.keyConfigured,state.cameraRevision) { model.key() }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importGpx { context.contentResolver.openInputStream(it) } } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { model.exportGpx { context.contentResolver.openOutputStream(it,"wt") } } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true || grants[Manifest.permission.ACCESS_COARSE_LOCATION]==true) {
            gpsEnabled=true; centerNextFix=true
        } else model.message("Location permission was declined. You can still plan routes.")
    }
    fun locate() {
        val granted=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }
        if(granted) { gpsEnabled=true; centerNextFix=true; model.message("Finding your location…") }
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    GpsLocation(gpsEnabled,onFix={ location ->
        if(location.latitude in -85.0..85.0) {
            gps=Coordinate(location.longitude,location.latitude); gpsAccuracy=location.accuracy
            if(centerNextFix) { model.flyTo(gps!!); centerNextFix=false }
        }
    },onError=model::message)
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
    LaunchedEffect(state.regionBounds) { if(state.regionBounds!=null)panel=Panel.OFFLINE }
    val routeLength=remember(state.route.points) { Geography.length(state.route.points) }
    val stats=remember(state.profile) { Geography.elevationStats(state.profile) }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            NativeMap(state,key,basemap,shade,lowPower,gps,handle,headerPixels,footerPixels,Modifier.fillMaxSize(),model::onMapTap,model::selectPoint,model::moveSelected,model::insertPoint,model::message)
            Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerPixels=it.height }.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Surface(shape=RoundedCornerShape(20.dp),tonalElevation=3.dp,shadowElevation=4.dp) {
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=6.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Landscape,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp))
                        Column(Modifier.weight(1f).padding(start=10.dp)) {
                            Text("RIDGELINE",fontWeight=FontWeight.Bold,letterSpacing=3.sp,fontSize=18.sp)
                            Text("NEW ZEALAND · ROUTE PLANNER",style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp)
                        }
                        IconButton(onClick={ panel=Panel.SEARCH }) { Icon(Icons.Outlined.Search,"Search peaks") }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Basemap.entries.forEach { b -> FilterChip(selected=basemap==b,onClick={ basemap=b },label={ Text(b.label) },colors=FilterChipDefaults.filterChipColors(containerColor=MaterialTheme.colorScheme.surface)) }
                    FilterChip(selected=shade,onClick={ shade=!shade },label={ Text("Relief") },leadingIcon={ Icon(Icons.Outlined.Terrain,null,Modifier.size(16.dp)) },colors=FilterChipDefaults.filterChipColors(containerColor=MaterialTheme.colorScheme.surface))
                }
                if(!state.keyConfigured) Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().clickable { panel=Panel.SETTINGS }.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("Add LINZ key for elevation & offline maps",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp))
                    }
                }
                if(state.editing || state.pickingRegion) Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primary) {
                    Row(Modifier.fillMaxWidth().padding(start=14.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(state.pickingRegion)if(state.corner==null)"Tap the first corner of your region" else "Tap the opposite corner" else
                            if(state.selectedPoint>=0)"Point ${state.selectedPoint+1} · long press to move" else "Tap to add · tap line to insert",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                        TextButton(onClick={ if(state.pickingRegion)model.cancelRegion() else model.setEditing(false) }) { Text(if(state.pickingRegion)"Cancel" else "Done",color=Color.White) }
                    }
                }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                MapButton("My location",Icons.Outlined.MyLocation,::locate)
                MapButton("North up",Icons.Outlined.Explore,handle::resetNorth)
                MapButton("Zoom in",Icons.Outlined.Add) { handle.zoom(1.0) }
                MapButton("Zoom out",Icons.Outlined.Remove) { handle.zoom(-1.0) }
            }
            Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { footerPixels=it.height },verticalArrangement=Arrangement.spacedBy(8.dp)) {
                state.storageError?.let { Text(it,Modifier.background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),color=MaterialTheme.colorScheme.onErrorContainer) }
                if(state.route.points.isNotEmpty() && !state.pickingRegion) Surface(Modifier.padding(horizontal=12.dp),shape=RoundedCornerShape(20.dp),shadowElevation=6.dp) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { panel=Panel.ROUTE }) {
                                Text(state.route.name.ifBlank { "Untitled route" },fontWeight=FontWeight.SemiBold,maxLines=1)
                                Text("${state.route.points.size} points · saved on device",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick={ expanded=!expanded }) { Icon(if(expanded)Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.ShowChart,if(expanded)"Hide profile" else "Show elevation profile") }
                            IconButton(onClick={ handle.fit(state.route.points) }) { Icon(Icons.Outlined.CenterFocusStrong,"Fit route") }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Stat("DISTANCE",distance(routeLength))
                            Stat("ASCENT",if(stats.highest==null)"—" else "${if(stats.complete)"" else "≥"}${stats.ascent.toInt()} m")
                            Stat("DESCENT",if(stats.highest==null)"—" else "${if(stats.complete)"" else "≥"}${stats.descent.toInt()} m")
                        }
                        if(expanded) {
                            if(state.profiling) LinearProgressIndicator(Modifier.fillMaxWidth())
                            ElevationChart(state.profile)
                            Text(if(!state.keyConfigured)"Add a LINZ key to load elevation." else if(state.profile.any { it.height==null })"Elevation incomplete. Gaps have no measurement; refresh after downloading or reconnecting." else "Unexaggerated ground estimates · not a navigation guarantee",style=MaterialTheme.typography.labelSmall)
                            TextButton(onClick=model::refreshProfile,contentPadding=PaddingValues(0.dp)) { Text("Refresh elevation") }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            FilledTonalButton(onClick={ model.setEditing(!state.editing) },Modifier.weight(1f),enabled=state.storageError==null) { Icon(Icons.Outlined.Edit,null,Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if(state.editing)"Finish editing" else "Edit route") }
                            if(state.editing) {
                                IconButton(onClick=model::undo) { Icon(Icons.AutoMirrored.Outlined.Undo,"Undo route edit") }
                                if(state.selectedPoint>=0) IconButton(onClick=model::removeSelected) { Icon(Icons.Outlined.DeleteOutline,"Delete selected waypoint") }
                            } else IconButton(onClick={ export.launch("${state.route.name.replace(Regex("[^A-Za-z0-9 _-]"),"_").take(70)}.gpx") }) { Icon(Icons.Outlined.IosShare,"Export GPX") }
                        }
                    }
                }
                if(state.route.points.isEmpty() && !state.pickingRegion) Surface(Modifier.padding(horizontal=12.dp),shape=RoundedCornerShape(20.dp),shadowElevation=4.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Find your next ridgeline",fontWeight=FontWeight.SemiBold); Text(if(state.editing)"Tap the map to start your route" else "Plan a route, one waypoint at a time",style=MaterialTheme.typography.bodySmall) }
                        FilledIconButton(onClick={ model.setEditing(!state.editing) },enabled=!state.loading && state.storageError==null) { Icon(Icons.Outlined.Add,"Start drawing a route") }
                    }
                }
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=3.dp,windowInsets=WindowInsets(0,0,0,0)) {
                    NavigationBarItem(selected=state.editing,onClick={ if(state.route.points.isEmpty())model.setEditing(!state.editing) else model.newRoute() },icon={ Icon(Icons.Outlined.Route,null) },label={ Text("New route") },enabled=!state.loading && state.storageError==null)
                    NavigationBarItem(selected=panel==Panel.ROUTES,onClick={ panel=Panel.ROUTES },icon={ Icon(Icons.Outlined.Bookmarks,null) },label={ Text("Routes") })
                    NavigationBarItem(selected=panel==Panel.OFFLINE,onClick={ panel=Panel.OFFLINE },icon={ Icon(Icons.Outlined.DownloadForOffline,null) },label={ Text("Offline") })
                    NavigationBarItem(selected=panel==Panel.SETTINGS,onClick={ panel=Panel.SETTINGS },icon={ Icon(Icons.Outlined.Tune,null) },label={ Text("Settings") })
                }
            }
            SnackbarHost(snackbar,Modifier.align(Alignment.Center).padding(16.dp))
            if(state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    panel?.let { selected -> ModalBottomSheet(onDismissRequest={ panel=null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            when(selected) {
                Panel.SETTINGS -> SettingsPanel(model,key,lowPower,{ lowPower=it },gpsEnabled,{ gpsEnabled=it; if(!it)gps=null },gpsAccuracy)
                Panel.ROUTES -> {
                    PanelTitle("Your routes","Saved privately on this device")
                    OutlinedButton(onClick={ import.launch(arrayOf("application/gpx+xml","application/xml","text/xml","application/octet-stream")); panel=null }) { Icon(Icons.Outlined.FileOpen,null); Spacer(Modifier.width(8.dp)); Text("Import GPX") }
                    if(state.routes.isEmpty())Text("Draw a route or import a GPX file to get started.")
                    state.routes.forEach { route ->
                        RouteRow(route,onOpen={ model.openRoute(route); panel=null },onDelete={ model.deleteRoute(route.id) })
                    }
                }
                Panel.OFFLINE -> OfflinePanel(state,model,onDraw={ panel=null; model.pickRegion() })
                Panel.SEARCH -> PeakSearch { point -> model.flyTo(point); panel=null }
                Panel.ROUTE -> {
                    PanelTitle("Route details","Changes save automatically")
                    var name by remember(state.route.id) { mutableStateOf(state.route.name) }
                    OutlinedTextField(name,{ name=it.take(80) },label={ Text("Route name") },singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={ model.rename(name.ifBlank { "Untitled route" }); panel=null }) { Text("Save name") }
                    OutlinedButton(onClick={ export.launch("Ridgeline-route.gpx"); panel=null }) { Text("Export GPX") }
                    TextButton(onClick={ model.deleteRoute(state.route.id); panel=null }) { Text("Delete this route",color=MaterialTheme.colorScheme.error) }
                }
            }
        }
    } }
}

@Composable private fun MapButton(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,click:()->Unit) {
    Surface(shape=RoundedCornerShape(14.dp),shadowElevation=3.dp) { IconButton(onClick=click) { Icon(icon,label) } }
}
@Composable private fun Stat(label:String,value:String) {
    Column { Text(label,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(value,fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable internal fun PanelTitle(title:String,subtitle:String) {
    Column { Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold); Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable private fun ElevationChart(profile:List<ElevationSample>) {
    val valid=profile.mapNotNull { it.height }; val min=valid.minOrNull() ?: 0.0; val max=valid.maxOrNull() ?: 1.0
    val span=(max-min).coerceAtLeast(20.0); val total=profile.lastOrNull()?.distance?.coerceAtLeast(1.0) ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription="Elevation profile. ${if(valid.isEmpty())"No elevation available" else "Lowest ${min.toInt()} metres, highest ${max.toInt()} metres"}" }) {
        val path=Path(); var pen=false
        profile.forEach { sample ->
            val h=sample.height
            if(h==null)pen=false else {
                val x=(sample.distance/total*size.width).toFloat(); val y=(size.height-8-((h-min)/span*(size.height-16))).toFloat()
                if(pen)path.lineTo(x,y) else { path.moveTo(x,y); pen=true }
            }
        }
        drawLine(Color(0xFFDCE2D8),androidx.compose.ui.geometry.Offset(0f,size.height-4),androidx.compose.ui.geometry.Offset(size.width,size.height-4))
        drawPath(path,Warm,style=Stroke(2.dp.toPx()))
    }
}
@Composable private fun RouteRow(route:Route,onOpen:()->Unit,onDelete:()->Unit) {
    var confirm by remember { mutableStateOf(false) }
    Surface(shape=RoundedCornerShape(14.dp),tonalElevation=2.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick=onOpen).padding(8.dp)) {
                Text(route.name,fontWeight=FontWeight.Medium); Text("${distance(Geography.length(route.points))} · ${route.points.size} points",style=MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick={ confirm=true }) { Icon(Icons.Outlined.DeleteOutline,"Delete ${route.name}") }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text("Delete route?") },text={ Text("Export a GPX first if you want to keep a copy of ${route.name}.") },
        confirmButton={ TextButton(onClick={ onDelete();confirm=false }) { Text("Delete") } },dismissButton={ TextButton(onClick={confirm=false}) {Text("Cancel")} })
}
@Composable private fun SettingsPanel(model:PlannerViewModel,key:String,lowPower:Boolean,onLowPower:(Boolean)->Unit,gpsEnabled:Boolean,onGps:(Boolean)->Unit,accuracy:Float?) {
    var input by remember(key) { mutableStateOf(key) }
    PanelTitle("Map settings","One native renderer · original imagery colours")
    Text("LINZ Basemaps",fontWeight=FontWeight.SemiBold)
    Text("The website's LINZ Basemaps key is connected by default. Replace it here with your own key to use separate provider access and quota. Your override stays in app-private storage.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(input,{ input=it.trim() },label={ Text("LINZ API key") },visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ model.setKey(input) }) { Text("Save key") }
        OutlinedButton(onClick=model::useDefaultKey) { Text("Use website key") }
    }
    Text("Clearing the key switches to online Esri imagery. Offline downloads use LINZ only. Default access shares the website’s provider quota.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Low-power rendering"); Text("Limit map rendering to 30 fps",style=MaterialTheme.typography.bodySmall) }; Switch(lowPower,onLowPower) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Foreground GPS"); Text(accuracy?.let { "Last fix accuracy: ±${it.toInt()} m" } ?: "Use the location button to grant access",style=MaterialTheme.typography.bodySmall) }; Switch(gpsEnabled,{ if(!it)onGps(false) else model.message("Use the location button on the map to enable GPS") }) }
    HorizontalDivider()
    Text("Sources & attribution",fontWeight=FontWeight.SemiBold)
    Text("LINZ imagery, elevation and topo: CC BY 4.0 and Basemap contributors.\nOnline fallback: Esri World Imagery / World Topographic Map & contributors. Hillshade fallback: Mapzen / AWS Open Data.\nPeak directory adapted from NZAC / ClimbNZ, CC BY-NC-SA 3.0 NZ; Aoraki Tai Poutini (Rob Frost, 2018).\nMap renderer: MapLibre Native (BSD).",style=MaterialTheme.typography.bodySmall)
    Text("Saved routes and downloads stay on this device. Uninstalling removes them; export GPX to keep a separate copy.",style=MaterialTheme.typography.bodySmall)
}
@Composable private fun PeakSearch(onSelect:(Coordinate)->Unit) {
    val context=LocalContext.current
    val peaks=remember { context.assets.open("peaks.json").use { input -> val list=JSONObject(input.readBytes().toString(Charsets.UTF_8)).getJSONArray("peaks"); (0 until list.length()).map { list.getJSONObject(it) } } }
    var query by remember { mutableStateOf("") }
    PanelTitle("Find a summit","Explore the New Zealand peak directory")
    OutlinedTextField(query,{query=it},label={Text("Search peaks")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Outlined.Search,null)})
    val results=peaks.filter { it.getString("name").contains(query,true) }.take(60)
    if(results.isEmpty())Text("No matching peaks")
    results.forEach { peak ->
        Row(Modifier.fillMaxWidth().clickable { val p=peak.getJSONArray("p"); onSelect(Coordinate(p.getDouble(0),p.getDouble(1))) }.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Landscape,null,tint=MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Column { Text(peak.getString("name"),fontWeight=FontWeight.Medium); Text("${peak.getInt("height")} m",style=MaterialTheme.typography.bodySmall) }
        }
    }
}
