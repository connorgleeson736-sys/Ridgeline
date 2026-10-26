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



}
