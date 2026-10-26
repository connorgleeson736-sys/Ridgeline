package nz.ridgeline.app.map

import android.graphics.PointF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import nz.ridgeline.app.PlannerState
import nz.ridgeline.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URLEncoder
import kotlin.math.*

enum class Basemap(val label: String) { AERIAL("Satellite"), TOPO("Topographic") }
class MapHandle {
    var map: MapLibreMap? = null
    fun resetNorth() { map?.let { it.animateCamera(CameraUpdateFactory.bearingTo(0.0)) } }
    fun zoom(by: Double) { map?.let { it.animateCamera(CameraUpdateFactory.zoomTo((it.cameraPosition.zoom+by).coerceIn(4.0,18.0))) } }
    fun fit(points: List<Coordinate>) {
        val map=map ?: return
        if(points.isEmpty())return
        if(points.distinct().size==1) { map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first().latLng(),13.0)); return }
        val bounds=org.maplibre.android.geometry.LatLngBounds.Builder().includes(points.map { it.latLng() }).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds,80))
    }
}
private fun Coordinate.latLng() = LatLng(latitude,longitude)

@Composable
fun NativeMap(
    state: PlannerState,key: String,basemap: Basemap,hillshade: Boolean,lowPower: Boolean,gps: Coordinate?,
    handle: MapHandle,topInset: Int,bottomInset: Int,modifier: Modifier=Modifier,
    onTap:(Coordinate)->Unit,onSelect:(Int)->Unit,onMove:(Coordinate)->Unit,onInsert:(Int,Coordinate)->Unit,onError:(String)->Unit,
) {
    val context=LocalContext.current
    val density by rememberUpdatedState(LocalDensity.current.density)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(state)
    val tap by rememberUpdatedState(onTap); val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove); val insert by rememberUpdatedState(onInsert)
    val error by rememberUpdatedState(onError)
    val view=remember {
        MapView(context, MapLibreMapOptions.createFromAttributes(context).apply {
            textureMode(false); compassEnabled(false); logoEnabled(false); attributionEnabled(true)
        }).apply { onCreate(null) }
    }
    var native by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(view,lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        val memory=object: android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration:android.content.res.Configuration) = Unit
            override fun onLowMemory() { view.onLowMemory() }
            override fun onTrimMemory(level:Int) {
                if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)view.onLowMemory()
            }
        }
        context.registerComponentCallbacks(memory)
        lifecycle.addObserver(observer)
        val fail=MapView.OnDidFailLoadingMapListener { _ -> error("Map could not load. Check your connection and LINZ key.") }
        view.addOnDidFailLoadingMapListener(fail)
        view.getMapAsync { map ->
            native=map; handle.map=map
            map.cameraPosition=CameraPosition.Builder().target(LatLng(-43.595,170.141)).zoom(10.0).tilt(0.0).build()
            map.setPrefetchZoomDelta(1)
            map.setMinZoomPreference(4.0); map.setMaxZoomPreference(18.0)
            map.uiSettings.isTiltGesturesEnabled=false
            map.addOnMapClickListener { latLng ->
                val p=Coordinate(latLng.longitude,latLng.latitude)
                val s=latest
                if(s.editing && !s.pickingRegion) {
                    val hit=map.projection.toScreenLocation(latLng)
                    val points=s.route.points.map { map.projection.toScreenLocation(it.latLng()) }
                    val radius=24*density
                    val nearest=points.indices.minByOrNull { i -> hypot(points[i].x-hit.x,points[i].y-hit.y) }
                    if(nearest!=null && hypot(points[nearest].x-hit.x,points[nearest].y-hit.y)<radius) select(nearest)
                    else {
                        var best=-1; var distance=radius*.65
                        for(i in 1 until points.size) {
                            val a=points[i-1]; val b=points[i]
                            val dx=b.x-a.x; val dy=b.y-a.y; val span=dx*dx+dy*dy
                            if(span==0f)continue
                            val t=(((hit.x-a.x)*dx+(hit.y-a.y)*dy)/span).coerceIn(0f,1f)
                            val d=hypot(hit.x-(a.x+t*dx),hit.y-(a.y+t*dy)).toDouble()
                            if(d<distance) { distance=d; best=i }
                        }
                        if(best>=0)insert(best,p) else tap(p)
                    }
                } else tap(p)
                true
            }
            map.addOnMapLongClickListener { latLng ->
                if(latest.editing && latest.selectedPoint>=0) { move(Coordinate(latLng.longitude,latLng.latitude)); true } else false
            }
        }
        onDispose {
            context.unregisterComponentCallbacks(memory)
            lifecycle.removeObserver(observer); view.removeOnDidFailLoadingMapListener(fail)
            view.onPause(); view.onStop(); view.onDestroy(); handle.map=null
        }
    }
    AndroidView(factory={ view },modifier=modifier)
    LaunchedEffect(native,key,basemap,hillshade) {
        native?.setStyle(Style.Builder().fromJson(styleJson(key,basemap,hillshade))) { styleRevision++ }
    }
    LaunchedEffect(native,topInset,bottomInset,density) {
        native?.let { map ->
            val available=(view.height-(96*density).toInt()).coerceAtLeast(0)
            val top=topInset.coerceIn(0,available/2)
            val bottom=bottomInset.coerceIn(0,available-top)
            map.setPadding(0,top,0,bottom)
            map.uiSettings.setAttributionGravity(android.view.Gravity.BOTTOM or android.view.Gravity.START)
            map.uiSettings.setAttributionMargins((12*density).toInt(),0,0,bottomInset+(12*density).toInt())
        }
    }
    LaunchedEffect(native,lowPower) { view.setMaximumFps(if(lowPower)30 else 60) }
    LaunchedEffect(native,state.cameraRevision) {
        state.cameraTarget?.let { p -> native?.animateCamera(CameraUpdateFactory.newLatLngZoom(p.latLng(),13.0)) }
    }
    LaunchedEffect(native,styleRevision,state.route.points,state.selectedPoint,state.regions,state.regionBounds,state.corner,gps) {
        native?.style?.getSourceAs<GeoJsonSource>("planner")?.setGeoJson(plannerJson(state,gps))
    }
}

private fun styleJson(key:String,basemap:Basemap,shade:Boolean):String {
    val api=URLEncoder.encode(key,"UTF-8")
    val aerial=if(key.isBlank())"https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}" else
        "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=$api"
    val topo=if(key.isBlank())"https://services.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}" else
        "https://basemaps.linz.govt.nz/v1/tiles/topo-raster/WebMercatorQuad/{z}/{x}/{y}.webp?api=$api"
    val elevation=if(key.isBlank())"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png" else
        "https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/{z}/{x}/{y}.png?pipeline=terrain-rgb&api=$api"
    val attribution=if(key.isBlank())"© Esri World Imagery / World Topographic Map & contributors · Elevation: Mapzen / AWS Open Data" else
        "<a href=\"https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution\">LINZ CC BY 4.0 & Basemap contributors</a>"
    val sources=JSONObject().put("basemap",JSONObject().put("type","raster").put("tiles",JSONArray(listOf(if(basemap==Basemap.AERIAL)aerial else topo)))
        .put("tileSize",256).put("maxzoom",if(key.isBlank())18 else 19).put("attribution",attribution))
        .put("planner",JSONObject().put("type","geojson").put("data",JSONObject("{\"type\":\"FeatureCollection\",\"features\":[]}")))
    if(shade)sources.put("dem",JSONObject().put("type","raster-dem").put("tiles",JSONArray(listOf(elevation))).put("tileSize",256)
        .put("maxzoom",14).put("encoding",if(key.isBlank())"terrarium" else "mapbox"))
    val layers=JSONArray().put(JSONObject("""{"id":"background","type":"background","paint":{"background-color":"#dce2db"}}"""))
        .put(JSONObject("""{"id":"photo","type":"raster","source":"basemap","paint":{"raster-fade-duration":160}}"""))
    if(shade)layers.put(JSONObject("""{"id":"shade","type":"hillshade","source":"dem","paint":{"hillshade-exaggeration":0.28,"hillshade-shadow-color":"#304842","hillshade-highlight-color":"#fffaf0"}}"""))
    fun layer(id:String,type:String,kind:String,paint:String,layout:String="{}") {
        layers.put(JSONObject().put("id",id).put("type",type).put("source","planner").put("filter",JSONArray(listOf("==","kind",kind)))
            .put("paint",JSONObject(paint)).put("layout",JSONObject(layout)))
    }
    layer("regions","fill","region","""{"fill-color":"#315e4b","fill-opacity":0.08}""")
    layer("region-outline","line","region","""{"line-color":"#edf2df","line-width":2,"line-dasharray":[3,2]}""")
    layer("selection","fill","selection","""{"fill-color":"#db865a","fill-opacity":0.18}""")
    layer("selection-outline","line","selection","""{"line-color":"#db865a","line-width":3}""")
    layer("route-halo","line","route","""{"line-color":"#fffdf6","line-width":7}""","""{"line-cap":"round","line-join":"round"}""")
    layer("route-line","line","route","""{"line-color":"#d76c3c","line-width":3.5}""","""{"line-cap":"round","line-join":"round"}""")
    layer("waypoints","circle","point","""{"circle-radius":4,"circle-color":"#fffaf0","circle-stroke-color":"#b95630","circle-stroke-width":1.5}""")
    layer("selected","circle","selected","""{"circle-radius":8,"circle-color":"#d76c3c","circle-stroke-color":"#ffffff","circle-stroke-width":2}""")
    layer("gps-halo","circle","gps","""{"circle-radius":14,"circle-color":"#4a9fe3","circle-opacity":0.20}""")
    layer("gps","circle","gps","""{"circle-radius":6,"circle-color":"#2878be","circle-stroke-color":"#ffffff","circle-stroke-width":2}""")
    return JSONObject().put("version",8).put("sources",sources).put("layers",layers).toString()
}

private fun plannerJson(state:PlannerState,gps:Coordinate?):String {
    val features=JSONArray()
    fun coord(p:Coordinate)=JSONArray(listOf(p.longitude,p.latitude))
    fun feature(kind:String,type:String,coordinates:JSONArray) {
        features.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("kind",kind))
            .put("geometry",JSONObject().put("type",type).put("coordinates",coordinates)))
    }
    if(state.route.points.size>=2) feature("route","LineString",JSONArray(state.route.points.map(::coord)))
    state.route.points.forEachIndexed { i,p -> feature(if(i==state.selectedPoint)"selected" else "point","Point",coord(p)) }
    fun rectangle(kind:String,b:Bounds) {
        feature(kind,"Polygon",JSONArray(listOf(JSONArray(listOf(
            JSONArray(listOf(b.west,b.south)),JSONArray(listOf(b.east,b.south)),JSONArray(listOf(b.east,b.north)),
            JSONArray(listOf(b.west,b.north)),JSONArray(listOf(b.west,b.south)))))))
    }
    state.regions.filter { it.completed }.forEach { rectangle("region",it.bounds) }
    state.regionBounds?.let { rectangle("selection",it) }
    state.corner?.let { feature("selected","Point",coord(it)) }
    gps?.let { feature("gps","Point",coord(it)) }
    return JSONObject().put("type","FeatureCollection").put("features",features).toString()
}
