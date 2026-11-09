package com.smartplug.app.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.smartplug.app.data.repo.LocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

sealed interface GeofenceSync {
    data class Registered(val count: Int, val backgroundReady: Boolean) : GeofenceSync
    data object NothingToRegister : GeofenceSync
    data object PermissionMissing : GeofenceSync
    data class Failed(val message: String) : GeofenceSync
}

/**
 * Registra os locais como geofences (ENTER/EXIT). O sistema avisa por broadcast, sem GPS contínuo.
 * Os registros se perdem em reinício/atualização/revogação: chame [sync] nesses momentos.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LocationRepository,
    private val permissions: PermissionChecker,
    private val tracker: LocationTracker,
) {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION)
        // Geofencing exige PendingIntent mutável para o sistema anexar o evento.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    suspend fun sync(): GeofenceSync {
        if (!permissions.hasFineLocation()) return GeofenceSync.PermissionMissing
        return try {
            val intent = pendingIntent()
            client.removeGeofences(intent).await()

            val places = repository.all()
            if (places.isEmpty()) return GeofenceSync.NothingToRegister

            val geofences = places.map {
                Geofence.Builder()
                    .setRequestId(it.id.toString())
                    .setCircularRegion(it.latitude, it.longitude, it.radiusMeters)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(RESPONSIVENESS_MS)
                    .build()
            }
            // Sem gatilho inicial: eventos só para transições reais (evita "chegou" falso ao registrar).
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofences(geofences)
                .build()
            client.addGeofences(request, intent).await()

            // Descobre onde o usuário está agora (leitura única) para o estado inicial dos locais.
            tracker.refresh(fresh = true)
            GeofenceSync.Registered(places.size, permissions.canGeofenceInBackground())
        } catch (e: SecurityException) {
            GeofenceSync.PermissionMissing
        } catch (e: Exception) {
            GeofenceSync.Failed(e.message ?: "falha ao registrar geofences")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun clear() {
        runCatching { client.removeGeofences(pendingIntent()).await() }
    }

    private companion object {
        const val REQUEST_CODE = 4101
        const val RESPONSIVENESS_MS = 60_000
    }
}
