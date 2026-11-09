package com.smartplug.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.smartplug.app.location.GeofenceManager
import com.smartplug.app.notifications.AutomationNotifier
import com.smartplug.app.workers.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OsmConfiguration

@HiltAndroidApp
class SmartPlugApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var notifier: AutomationNotifier
    @Inject lateinit var geofences: GeofenceManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        OsmConfiguration.getInstance().apply {
            load(this@SmartPlugApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        notifier.ensureChannel()
        appScope.launch {
            scheduler.ensurePeriodic()
            geofences.sync() // reabre os registros mesmo após atualização/limpeza do Play Services
        }
    }
}
