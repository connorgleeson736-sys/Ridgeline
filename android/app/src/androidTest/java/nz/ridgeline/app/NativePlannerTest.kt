package nz.ridgeline.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nz.ridgeline.app.data.AppStore
import nz.ridgeline.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NativePlannerTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativePlannerSavesEditsAndShowsStatistics() {
        lateinit var model:PlannerViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[PlannerViewModel::class.java] }
        compose.waitUntil(15000) { !model.state.value.loading }
        compose.onNodeWithText("RIDGELINE").assertIsDisplayed()
        compose.runOnIdle { model.newRoute() }
        compose.waitUntil(5000) { model.state.value.editing }
        compose.runOnIdle {
            model.onMapTap(Coordinate(170.141,-43.595))
            model.onMapTap(Coordinate(170.15,-43.60))
        }
        compose.waitUntil(10000) { model.state.value.route.points.size==2 }
        compose.onNodeWithText("DISTANCE").assertIsDisplayed()
        compose.onNodeWithText("2 points · saved on device").assertIsDisplayed()
        val stored=AppStore(compose.activity).read()
        assertEquals(model.state.value.route.points,stored.routes.first().points)
        compose.runOnIdle { model.undo() }
        compose.waitUntil(10000) { model.state.value.route.points.size==1 }
        assertEquals(1,AppStore(compose.activity).read().routes.first().points.size)
    }
    @Test fun realNativeMapLoadsThePlannerStyle() {
        fun find(view: android.view.View): org.maplibre.android.maps.MapView? {
            if(view is org.maplibre.android.maps.MapView)return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        compose.waitUntil(120000) {
            compose.activityRule.scenario.onActivity { activity ->
                find(activity.window.decorView)?.getMapAsync { map ->
                    ready.set(map.style?.getLayer("route-line")!=null && map.style?.getSource("basemap")!=null)
                }
            }
            ready.get()
        }
        assertTrue(ready.get())
    }
    @Test fun androidGpxParserRoundTripsAndRejectsEntities() {
        val route=Route(name="Cook & Tasman",points=listOf(Coordinate(170.141,-43.595),Coordinate(170.15,-43.60)))
        val output=ByteArrayOutputStream();Gpx.write(route,output)
        assertEquals(route.points,Gpx.read(output.toByteArray().inputStream()).single().points)
        assertThrows(IllegalArgumentException::class.java) { Gpx.read("<!DOCTYPE gpx><gpx/>".byteInputStream()) }
    }
    @Test fun elevationProfilerReadsSavedTerrainRgbWithoutNetwork() = kotlinx.coroutines.runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RidgelineApplication
        val region=OfflineRegion("instrumented-elevation","Test",Bounds(170.13,-43.61,170.16,-43.58),10,true)
        val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(1,138,136)) // Terrain RGB = exactly 100 m.
        val output=ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()
        val originals=mutableMapOf<java.io.File,ByteArray?>()
        try {
            region.tiles().filter { it.z==10 && it.kind==TileKind.DEM }.forEach { tile ->
                val file=java.io.File(app.filesDir,"offline-tiles/${tile.path}")
                originals[file]=file.takeIf { it.exists() }?.readBytes()
                file.parentFile!!.mkdirs();file.writeBytes(output.toByteArray())
            }
            app.store.update { it.copy(regions=it.regions+region) }
            val profile=app.elevation.profile(listOf(Coordinate(170.141,-43.595),Coordinate(170.142,-43.595)))
            assertTrue(profile.size>=2)
            profile.forEach { assertEquals(100.0,it.height!!,.01) }
            assertEquals(0.0,Geography.elevationStats(profile).ascent,.01)
        } finally {
            app.store.update { it.copy(regions=it.regions.filterNot { r -> r.id==region.id }) }
            originals.forEach { (file,bytes) -> if(bytes==null)file.delete() else file.writeBytes(bytes) }
        }
    }
    @Test fun offlineFilesReachTheNativeMapHttpClientWithoutNetwork() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RidgelineApplication
        val id=TileId(TileKind.AERIAL,14,15935,10400)
        val file=java.io.File(app.filesDir,"offline-tiles/${id.path}")
        file.parentFile!!.mkdirs()
        val bytes=byteArrayOf(82,73,70,70,1,2,3,4)
        file.writeBytes(bytes)
        try {
            val request=okhttp3.Request.Builder().url("https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/14/15935/10400.webp?api=test").build()
            app.tiles.mapClient.newCall(request).execute().use { response ->
                assertEquals(200,response.code);assertArrayEquals(bytes,response.body!!.bytes());assertEquals("Offline tile",response.message)
            }
        } finally { file.delete() }
    }
}
