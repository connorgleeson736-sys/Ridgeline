package nz.ridgeline.app.data

import android.content.Context
import android.util.AtomicFile
import nz.ridgeline.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A single atomically replaced document keeps active route, saved routes and regions consistent. */
class AppStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "planner.json"))
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Public client key reused from the website with the owner's explicit approval.
    val defaultLinzKey = context.assets.open("map-defaults.json").use {
        JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("linzApiKey")
    }
    var linzKey: String
        get() = preferences.getString("linzKey", defaultLinzKey) ?: defaultLinzKey
        set(value) { check(preferences.edit().putString("linzKey", value.trim()).commit()) { "Couldn't save settings" } }
    data class Snapshot(val routes: List<Route> = emptyList(), val activeId: String? = null, val regions: List<OfflineRegion> = emptyList())

    @Synchronized fun read(): Snapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return Snapshot()
        val root = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val routes = root.getJSONArray("routes").objects().map { obj ->
            Route(obj.getString("id"),obj.getString("name"),obj.getJSONArray("points").let { points ->
                (0 until points.length()).map { i -> points.getJSONArray(i).let { Coordinate(it.getDouble(0),it.getDouble(1)) } }
            },obj.getLong("updated"))
        }
        val regions = root.optJSONArray("regions")?.objects()?.map { obj ->
            val b = obj.getJSONArray("bounds")
            OfflineRegion(obj.getString("id"),obj.getString("name"),Bounds(b.getDouble(0),b.getDouble(1),b.getDouble(2),b.getDouble(3)),obj.getInt("maxZoom"),obj.optBoolean("completed"))
        } ?: emptyList()
        return Snapshot(routes, root.optString("activeId").takeIf { it.isNotEmpty() }, regions)
    }
    @Synchronized fun update(transform: (Snapshot) -> Snapshot): Snapshot {
        val next = transform(read())
        require(next.routes.size <= 100) { "100 route limit reached. Export or delete an existing route." }
        val root = JSONObject().put("version",1).put("activeId",next.activeId ?: "")
            .put("routes",JSONArray(next.routes.map { route -> JSONObject().put("id",route.id).put("name",route.name)
                .put("updated",route.updated).put("points",JSONArray(route.points.map { JSONArray(listOf(it.longitude,it.latitude)) })) }))
            .put("regions",JSONArray(next.regions.map { r -> JSONObject().put("id",r.id).put("name",r.name).put("maxZoom",r.maxZoom)
                .put("completed",r.completed).put("bounds",JSONArray(listOf(r.bounds.west,r.bounds.south,r.bounds.east,r.bounds.north))) }))
        val output = file.startWrite()
        try { output.write(root.toString().toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        return next
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
