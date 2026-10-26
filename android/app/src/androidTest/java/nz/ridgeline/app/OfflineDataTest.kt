package nz.ridgeline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import nz.ridgeline.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Platform data checks run without launching an Activity or depending on a working System UI. */
@RunWith(AndroidJUnit4::class)
class OfflineDataTest {
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
    @Test fun deletingActiveRouteClearsSelectionAndPreservesSavedData() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RidgelineApplication
        val original=app.store.read()
        val model=PlannerViewModel(app)
        try {
            withTimeout(120000) { model.state.first { !it.loading } }
            model.newRoute()
            val route=withTimeout(120000) { model.state.first { it.editing && it.route.points.isEmpty() } }.route
            model.onMapTap(Coordinate(170.141,-43.595));model.onMapTap(Coordinate(170.15,-43.60))
            withTimeout(120000) { model.state.first { it.route.id==route.id && it.route.points.size==2 } }
            assertEquals(2,app.store.read().routes.first { it.id==route.id }.points.size)
            model.selectPoint(0);model.deleteRoute(route.id)
            val cleared=withTimeout(120000) { model.state.first { it.route.id!=route.id } }
            assertFalse(cleared.editing);assertEquals(-1,cleared.selectedPoint)
            assertTrue(cleared.route.points.isEmpty())
            assertFalse(app.store.read().routes.any { it.id==route.id })
        } finally {
            model.viewModelScope.cancel()
            app.store.update { original }
        }
    }
}
