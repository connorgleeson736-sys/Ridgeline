package nz.ridgeline.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import nz.ridgeline.app.R
import nz.ridgeline.app.RidgelineApplication
import nz.ridgeline.app.model.*
import java.io.IOException

class RegionDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as RidgelineApplication
        val id = inputData.getString("regionId") ?: return@withContext Result.failure()
        val region = app.store.read().regions.firstOrNull { it.id == id } ?: return@withContext Result.failure()
        val total = region.tileCount()
        if (total > OfflineRegion.MAX_TILES) return@withContext failure("Region exceeds ${OfflineRegion.MAX_TILES} tiles")
        try {
            setForeground(notification(region.name,0,total))
            require(app.tiles.freeBytes() > 100L*1024*1024) { "At least 100 MB free space is required" }
            var done = 0
            // Bounded concurrency, no unbounded task list or simultaneous bitmap decoding.
            val lock = java.util.concurrent.atomic.AtomicInteger()
            val all = region.tiles().toList()
            coroutineScope {
                repeat(3) {
                    launch {
                        while (true) {
                            ensureActive()
                            val index = lock.getAndIncrement(); if(index >= all.size) break
                            if (app.tiles.freeBytes() < 50L*1024*1024) error("Device storage is nearly full")
                            app.tiles.download(all[index],region.id)
                            synchronized(all) { done++ }
                            val progress = synchronized(all) { done }
                            if (progress % 20 == 0 || progress == total) {
                                setProgress(workDataOf("done" to progress,"total" to total))
                                setForeground(notification(region.name,progress,total))
                            }
                        }
                    }
                }
            }
            app.store.update { snapshot -> snapshot.copy(regions = snapshot.regions.map { if(it.id==id) it.copy(completed=true) else it }) }
            Result.success(workDataOf("done" to total,"total" to total))
        } catch (e: CancellationException) { throw e }
        catch (e: TileStore.HttpFailure) {
            if ((e.status==429 || e.status >= 500) && runAttemptCount < 5) Result.retry()
            else failure(if(e.status==401 || e.status==403) "LINZ rejected the API key or download access" else "Provider HTTP ${e.status}. Try a smaller region or lower detail.")
        } catch (e: IOException) {
            if(runAttemptCount < 5) Result.retry() else failure("Download interrupted. Check your connection, then resume.")
        } catch (e: Exception) { failure(e.message ?: "Download failed") }
    }
    private fun failure(message: String) = Result.failure(workDataOf("error" to message))
    private fun notification(name: String,done: Int,total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("offline-maps","Offline maps",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext,"offline-maps")
            .setSmallIcon(R.drawable.ic_ridgeline).setContentTitle("Downloading $name")
            .setContentText("$done of $total tiles").setProgress(total,done,false).setOngoing(true)
            .addAction(0,"Cancel",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(42,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(42,notification)
    }
    companion object {
        fun enqueue(context: Context,id: String) {
            val request = OneTimeWorkRequestBuilder<RegionDownloadWorker>().setInputData(workDataOf("regionId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("offline-maps").addTag("region:$id").build()
            // One queue across regions prevents concurrent downloads/pruning and limits provider pressure.
            WorkManager.getInstance(context).enqueueUniqueWork("offline-maps",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}
