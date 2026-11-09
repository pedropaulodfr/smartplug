package com.smartplug.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.prefs.KnownLocation
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.engine.AutomationEngine
import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.engine.EngineTrigger
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.workers.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Converte transições de geofence em eventos do motor, evitando duplicidade. */
@Singleton
class GeofenceEventHandler @Inject constructor(
    private val repository: LocationRepository,
    private val prefs: AppPrefs,
    private val engine: AutomationEngine,
    private val scheduler: WorkScheduler,
    private val clock: Clock,
) {
    /** @return os eventos gerados (já aplicados ao estado persistido). */
    suspend fun toEvents(transition: Int, locationIds: List<Long>, location: Location?): List<EngineEvent> {
        location?.let {
            prefs.saveKnownLocation(KnownLocation(it.latitude, it.longitude, it.accuracy, clock.nowMillis()))
        }
        val events = mutableListOf<EngineEvent>()
        for (id in locationIds) {
            if (repository.byId(id) == null) continue // local removido
            val previous = repository.statusOf(id)
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    repository.setStatus(id, LocationStatus.INSIDE)
                    if (previous != LocationStatus.INSIDE) events += EngineEvent.GeofenceEnter(id)
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    repository.setStatus(id, LocationStatus.OUTSIDE)
                    if (previous != LocationStatus.OUTSIDE) events += EngineEvent.GeofenceExit(id)
                }
            }
        }
        return events
    }

    suspend fun process(events: List<EngineEvent>) {
        if (events.isEmpty()) return
        val report = engine.run(EngineTrigger(TriggerSource.GEOFENCE, events))
        scheduler.scheduleWake(report.nextWakeAtMillis)
        // Falha de comunicação: tenta de novo em segundo plano, preservando os eventos.
        if (report.retryNeeded) scheduler.runSoon(TriggerSource.GEOFENCE, events, delaySeconds = 30)
    }
}

/**
 * Recebe as transições do Google Play Services. O trabalho curto roda no próprio broadcast
 * (goAsync); se estourar o tempo, o WorkManager assume com os mesmos eventos.
 */
@AndroidEntryPoint
class GeofenceReceiver : BroadcastReceiver() {

    @Inject lateinit var handler: GeofenceEventHandler
    @Inject lateinit var scheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "erro de geofencing: ${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
        val location = event.triggeringLocation

        val pending = goAsync()
        scope.launch {
            var events = emptyList<EngineEvent>()
            try {
                events = handler.toEvents(transition, ids, location)
                withTimeout(BUDGET_MS) { handler.process(events) }
            } catch (e: Exception) {
                Log.w(TAG, "geofence: delegando ao WorkManager (${e.javaClass.simpleName})")
                if (events.isNotEmpty()) scheduler.runSoon(TriggerSource.GEOFENCE, events)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.smartplug.app.GEOFENCE_EVENT"
        private const val TAG = "GeofenceReceiver"
        private const val BUDGET_MS = 9_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
