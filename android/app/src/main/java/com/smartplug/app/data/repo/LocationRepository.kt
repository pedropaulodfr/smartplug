package com.smartplug.app.data.repo

import android.location.Location
import com.smartplug.app.data.db.AppDatabase
import com.smartplug.app.data.db.AutomationLocationEntity
import com.smartplug.app.data.db.LocationStateEntity
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.LocationStateSource
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.domain.model.SavedLocation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class LocationRepository @Inject constructor(
    private val db: AppDatabase,
    private val prefs: AppPrefs,
    private val clock: Clock,
) : LocationStateSource {

    private val dao get() = db.locations()

    fun observeAll(): Flow<List<SavedLocation>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeInfo(): Flow<List<LocationInfo>> =
        combine(dao.observeAll(), dao.observeStates()) { locations, states ->
            val byId = states.associateBy { it.locationId }
            locations.map {
                LocationInfo(it.toDomain(), byId[it.id]?.status.toStatus(), distanceMeters = null)
            }
        }

    suspend fun all(): List<SavedLocation> = dao.all().map { it.toDomain() }
    suspend fun byId(id: Long): SavedLocation? = dao.byId(id)?.toDomain()

    suspend fun save(location: SavedLocation): Long =
        if (location.id == 0L) {
            dao.insert(location.toEntity())
        } else {
            dao.update(location.toEntity())
            location.id
        }

    suspend fun delete(id: Long) {
        dao.delete(id)
        dao.deleteState(id)
    }

    suspend fun setStatus(locationId: Long, status: LocationStatus) {
        dao.saveState(LocationStateEntity(locationId, status.name, clock.nowMillis()))
    }

    suspend fun statusOf(locationId: Long): LocationStatus =
        dao.states().firstOrNull { it.locationId == locationId }?.status.toStatus()

    /** Locais + status persistido + distância até a última posição conhecida (se recente). */
    override suspend fun snapshots(): List<LocationInfo> {
        val states = dao.states().associateBy { it.locationId }
        val known = prefs.knownLocation()
        val fresh = known != null && clock.nowMillis() - known.atMillis <= MAX_FIX_AGE_MS
        return dao.all().map { entity ->
            val location = entity.toDomain()
            val distance = if (fresh && known != null) {
                distanceMeters(known.latitude, known.longitude, location.latitude, location.longitude)
            } else null
            LocationInfo(location, states[entity.id]?.status.toStatus(), distance)
        }
    }

    private fun String?.toStatus() =
        this?.let { runCatching { LocationStatus.valueOf(it) }.getOrNull() } ?: LocationStatus.UNKNOWN

    private fun AutomationLocationEntity.toDomain() = SavedLocation(id, name, latitude, longitude, radiusMeters)
    private fun SavedLocation.toEntity() = AutomationLocationEntity(id, name, latitude, longitude, radiusMeters)

    companion object {
        /** Posição mais velha que isto não é confiável para distância. */
        const val MAX_FIX_AGE_MS = 6 * 3_600_000L

        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val out = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, out)
            return out[0].toDouble()
        }
    }
}
