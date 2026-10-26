package nz.ridgeline.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import nz.ridgeline.app.data.*
import nz.ridgeline.app.model.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class PlannerState(
    val loading: Boolean = true,
    val routes: List<Route> = emptyList(),
    val route: Route = Route(),
    val editing: Boolean = false,
    val selectedPoint: Int = -1,
    val profile: List<ElevationSample> = emptyList(),
    val profiling: Boolean = false,
    val regions: List<OfflineRegion> = emptyList(),
    val keyConfigured: Boolean = false,
    val storageError: String? = null,
    val message: String? = null,
    val pickingRegion: Boolean = false,
    val corner: Coordinate? = null,
    val regionBounds: Bounds? = null,
    val cameraTarget: Coordinate? = null,
    val cameraRevision: Int = 0,
)

class PlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RidgelineApplication
    private val mutable = MutableStateFlow(PlannerState())
    val state = mutable.asStateFlow()
    private var profileJob: Job? = null
    private val history = ArrayDeque<List<Coordinate>>()
    private val operations = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            for(operation in operations) try { operation() } catch(e: CancellationException) { throw e }
                catch(e: Exception) { mutable.update { it.copy(message=e.message ?: "Operation failed") } }
        }
        act {
            try {
                val data = withContext(Dispatchers.IO) { app.store.read() }
                mutable.update { it.copy(loading=false,routes=data.routes,regions=data.regions,
                    route=data.routes.firstOrNull { r -> r.id==data.activeId } ?: Route(),keyConfigured=app.store.linzKey.isNotBlank()) }
                profile()
            } catch(e: Exception) { mutable.update { it.copy(loading=false,storageError="Route storage could not be read. Original data has been preserved; export or recover planner.json before editing.") } }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow("offline-maps").map { jobs -> jobs.map { it.id to it.state } }.distinctUntilChanged().collect {
                runCatching { withContext(Dispatchers.IO) { app.store.read().regions } }.getOrNull()?.let { regions -> mutable.update { it.copy(regions=regions) } }
            }
        }
    }
    private fun act(block: suspend () -> Unit) { operations.trySend(block) }
    fun message(text: String) { mutable.update { it.copy(message=text) } }
    fun dismissMessage() { mutable.update { it.copy(message=null) } }
    fun setEditing(value: Boolean) { mutable.update { it.copy(editing=value, pickingRegion=false,corner=null, selectedPoint=-1) } }
    fun selectPoint(index: Int) { mutable.update { it.copy(selectedPoint=index) } }
    fun onMapTap(point: Coordinate) {
        val s = state.value
        if(s.loading)return
        if(s.pickingRegion) {
            if(s.corner==null) mutable.update { it.copy(corner=point) }
            else runCatching { Bounds.between(s.corner,point) }.onSuccess { b -> mutable.update { it.copy(regionBounds=b,pickingRegion=false,corner=null) } }.onFailure { message(it.message ?: "Invalid bounds") }
        } else if(s.editing) edit { it + point }
    }
    fun insertPoint(index: Int, point: Coordinate) { edit { it.toMutableList().apply { add(index.coerceIn(0,size),point) } } }
    fun moveSelected(point: Coordinate) { val index=state.value.selectedPoint; if(index>=0) edit { it.toMutableList().apply { this[index]=point } } }
    fun removeSelected() { val index=state.value.selectedPoint; if(index>=0) edit { it.filterIndexed { i,_ -> i!=index } } }
    private fun edit(change: (List<Coordinate>) -> List<Coordinate>) = act {
        check(state.value.storageError==null) { "Storage is unavailable" }
        val old=state.value.route
        val points = change(old.points)
        require(points.size<=Route.MAX_POINTS) { "${Route.MAX_POINTS} point limit reached" }
        val route=old.copy(points=points,updated=System.currentTimeMillis())
        save(route)
        history.addLast(old.points); if(history.size>50)history.removeFirst()
        mutable.update { it.copy(route=route,selectedPoint=-1) }; profile()
    }
    private suspend fun save(route: Route) {
        val data=withContext(Dispatchers.IO) {
            app.store.update { s -> s.copy(activeId=route.id,routes=listOf(route)+s.routes.filterNot { it.id==route.id }) }
        }
        mutable.update { it.copy(routes=data.routes) }
    }
    fun undo() = act {
        if(history.isNotEmpty()) {
            val points=history.last(); val route=state.value.route.copy(points=points,updated=System.currentTimeMillis())
            save(route); history.removeLast(); mutable.update { it.copy(route=route,selectedPoint=-1) }; profile()
        }
    }
    fun rename(name: String) = act {
        val route=state.value.route.copy(name=name.take(80),updated=System.currentTimeMillis()); save(route)
        mutable.update { it.copy(route=route) }
    }
    fun newRoute() = act {
        val route=Route(); history.clear(); mutable.update { it.copy(route=route,editing=true,pickingRegion=false,corner=null,regionBounds=null,selectedPoint=-1) }; profile()
    }
    fun openRoute(route: Route) = act {
        withContext(Dispatchers.IO) { app.store.update { it.copy(activeId=route.id) } }
        history.clear(); mutable.update { it.copy(route=route,editing=false,pickingRegion=false,corner=null,regionBounds=null,selectedPoint=-1,cameraTarget=route.points.firstOrNull(),cameraRevision=it.cameraRevision+1) }; profile()
    }
    fun deleteRoute(id: String) = act {
        val data=withContext(Dispatchers.IO) { app.store.update { it.copy(routes=it.routes.filterNot { r -> r.id==id },activeId=if(it.activeId==id)null else it.activeId) } }
        mutable.update { it.copy(routes=data.routes,route=if(it.route.id==id)Route() else it.route) }; history.clear(); profile()
    }
    fun importGpx(open: () -> InputStream?) = act {
        mutable.update { it.copy(loading=true) }
        try {
        val imported=withContext(Dispatchers.IO) { open()?.use(Gpx::read) ?: error("Unable to open GPX") }
        val data=withContext(Dispatchers.IO) { app.store.update { it.copy(routes=imported+it.routes,activeId=imported.first().id) } }
        history.clear(); mutable.update { it.copy(routes=data.routes,route=imported.first(),editing=false,selectedPoint=-1,
            cameraTarget=imported.first().points.firstOrNull(),cameraRevision=it.cameraRevision+1,message="Imported ${imported.size} route segment(s)") }; profile()
        } finally { mutable.update { it.copy(loading=false) } }
    }
    fun exportGpx(open: () -> OutputStream?) = act {
        val route=state.value.route
        require(route.points.isNotEmpty()) { "Add some route points first" }
        withContext(Dispatchers.IO) { open()?.use { Gpx.write(route,it) } ?: error("Unable to write GPX") }
        message("GPX exported")
    }
    fun setKey(key: String) = act {
        withContext(Dispatchers.IO) { app.store.linzKey=key }
        mutable.update { it.copy(keyConfigured=key.isNotBlank(),cameraRevision=it.cameraRevision+1) }; profile()
    }
    fun useDefaultKey() { setKey(app.store.defaultLinzKey) }
    fun key() = app.store.linzKey
    fun refreshProfile() { profile() }
    private fun profile() {
        profileJob?.cancel()
        val points=state.value.route.points
        mutable.update { it.copy(profile=emptyList(),profiling=points.size>=2 && it.keyConfigured) }
        if(points.size<2 || !state.value.keyConfigured)return
        profileJob=viewModelScope.launch {
            delay(350)
            val data=withContext(Dispatchers.IO) { app.elevation.profile(points) }
            mutable.update { it.copy(profile=data,profiling=false) }
        }
    }
    fun flyTo(point: Coordinate) { mutable.update { it.copy(cameraTarget=point,cameraRevision=it.cameraRevision+1) } }
    fun pickRegion() { mutable.update { it.copy(pickingRegion=true,corner=null,regionBounds=null,editing=false) } }
    fun cancelRegion() { mutable.update { it.copy(pickingRegion=false,corner=null,regionBounds=null) } }
    fun regionBounds(bounds: Bounds) { mutable.update { it.copy(regionBounds=bounds,pickingRegion=false,corner=null) } }
    fun downloadRegion(name: String,bounds: Bounds,zoom: Int) = act {
        require(app.store.linzKey.isNotBlank()) { "Add a LINZ API key in Settings before downloading" }
        val region=OfflineRegion(UUID.randomUUID().toString(),name.ifBlank { "Offline region" }.take(80),bounds,zoom)
        require(region.tileCount() <= OfflineRegion.MAX_TILES) { "Region too large; lower detail or draw a smaller region" }
        val snapshot=withContext(Dispatchers.IO) { app.store.update { it.copy(regions=it.regions+region) } }
        mutable.update { it.copy(regions=snapshot.regions,regionBounds=null) }
        RegionDownloadWorker.enqueue(app,region.id)
    }
    fun resumeRegion(id: String) { RegionDownloadWorker.enqueue(app,id) }
    fun deleteRegion(id: String) = act {
        val jobs=WorkManager.getInstance(app).getWorkInfosByTagFlow("offline-maps").first()
        require(jobs.none { !it.state.isFinished }) { "Cancel or finish downloads before deleting a region" }
        val data=withContext(Dispatchers.IO) {
            val s=app.store.update { it.copy(regions=it.regions.filterNot { r -> r.id==id }) }
            app.tiles.prune(s.regions); s
        }
        mutable.update { it.copy(regions=data.regions) }
    }
}
