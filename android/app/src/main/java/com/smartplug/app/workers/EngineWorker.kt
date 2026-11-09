package com.smartplug.app.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.smartplug.app.domain.engine.AutomationEngine
import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.engine.EngineTrigger
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.location.LocationTracker
import com.smartplug.app.location.PermissionChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Roda o Automation Engine em segundo plano (periódico, despertar, boot, geofence com falha...). */
@HiltWorker
class EngineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: AutomationEngine,
    private val tracker: LocationTracker,
    private val permissions: PermissionChecker,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE)
            ?.let { runCatching { TriggerSource.valueOf(it) }.getOrNull() } ?: TriggerSource.PERIODIC
        val events = decode(inputData.getStringArray(KEY_EVENTS))

        // Reconcilia a localização com a última posição do sistema (sem ligar GPS).
        val reconciled = if (permissions.hasBackgroundLocation() && permissions.hasAnyLocation()) {
            runCatching { tracker.refresh(fresh = false) }.getOrDefault(emptyList())
        } else emptyList()

        val report = engine.run(EngineTrigger(source, events + reconciled))
        scheduler.scheduleWake(report.nextWakeAtMillis)

        return if (report.retryNeeded && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_EVENTS = "events"
        private const val MAX_RETRIES = 4

        fun input(source: TriggerSource, events: List<EngineEvent>): Data = Data.Builder()
            .putString(KEY_SOURCE, source.name)
            .putStringArray(KEY_EVENTS, encode(events).map<String, String?> { it }.toTypedArray())
            .build()

        private fun encode(events: List<EngineEvent>): Array<String> = events.mapNotNull {
            when (it) {
                is EngineEvent.GeofenceEnter -> "enter:${it.locationId}"
                is EngineEvent.GeofenceExit -> "exit:${it.locationId}"
                is EngineEvent.PlugChanged -> null // derivado do estado real a cada rodada
            }
        }.toTypedArray()

        private fun decode(values: Array<String>?): List<EngineEvent> = values.orEmpty().mapNotNull {
            val (kind, id) = it.split(":", limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            val locationId = id.toLongOrNull() ?: return@mapNotNull null
            when (kind) {
                "enter" -> EngineEvent.GeofenceEnter(locationId)
                "exit" -> EngineEvent.GeofenceExit(locationId)
                else -> null
            }
        }
    }
}
