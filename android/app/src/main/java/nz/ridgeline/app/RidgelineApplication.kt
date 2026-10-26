package nz.ridgeline.app

import android.app.Application
import nz.ridgeline.app.data.*
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class RidgelineApplication : Application() {
    lateinit var store: AppStore; private set
    lateinit var tiles: TileStore; private set
    lateinit var elevation: ElevationRepository; private set
    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        tiles = TileStore(this, store)
        elevation = ElevationRepository(tiles,store)
        MapLibre.getInstance(this)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(tiles.mapClient)
    }
}
