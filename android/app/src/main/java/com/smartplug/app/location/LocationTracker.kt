package com.smartplug.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.prefs.KnownLocation
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.model.LocationStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.tasks.await

/**
 * Obtém posições SEM monitoramento contínuo: usa a última posição do sistema (gratuita) e,
 * apenas quando pedido, uma leitura única de precisão equilibrada.
 */
@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val prefs: AppPrefs,
    private val repository: LocationRepository,
    private val clock: Clock,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Location? {
        if (!permissions.hasAnyLocation()) return null
        return runCatching { client.lastLocation.await() }.getOrNull()
    }

    /** Leitura única (balanceada, até [timeoutMs]). Nulo se indisponível ou sem permissão. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMs: Long = 12_000): Location? {
        if (!permissions.hasAnyLocation()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(60_000)
            .setDurationMillis(timeoutMs)
            .build()
        return runCatching { client.getCurrentLocation(request, null).await() }.getOrNull()
            ?: lastKnown()
    }

    /**
     * Atualiza a posição conhecida e reconcilia o status dos locais (cobre geofences perdidos).
     * Só gera evento quando um status JÁ conhecido muda; UNKNOWN -> X apenas define o estado.
     */
    suspend fun refresh(fresh: Boolean): List<EngineEvent> {
        val location = (if (fresh) currentFix() else lastKnown()) ?: return emptyList()
        prefs.saveKnownLocation(
            KnownLocation(location.latitude, location.longitude, location.accuracy, clock.nowMillis()),
        )
        return reconcile(location)
    }

    suspend fun reconcile(location: Location): List<EngineEvent> {
        val events = mutableListOf<EngineEvent>()
        for (place in repository.all()) {
            val distance = LocationRepository.distanceMeters(
                location.latitude, location.longitude, place.latitude, place.longitude,
            )
            // Zona incerta: o erro da posição é maior que a distância até a borda.
            if (abs(distance - place.radiusMeters) < location.accuracy) continue
            val now = if (distance <= place.radiusMeters) LocationStatus.INSIDE else LocationStatus.OUTSIDE
            val previous = repository.statusOf(place.id)
            if (previous == now) continue
            repository.setStatus(place.id, now)
            if (previous != LocationStatus.UNKNOWN) {
                events += if (now == LocationStatus.INSIDE) EngineEvent.GeofenceEnter(place.id)
                else EngineEvent.GeofenceExit(place.id)
            }
        }
        return events
    }
}
