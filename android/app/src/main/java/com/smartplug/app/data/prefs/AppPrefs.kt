package com.smartplug.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smartplug.app.domain.gateway.PlugMemory
import com.smartplug.app.integrations.tuya.DeviceProfile
import com.smartplug.app.integrations.tuya.TuyaConfigSource
import com.smartplug.app.integrations.tuya.TuyaCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

data class KnownLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val atMillis: Long,
)

/** Preferências e pequenos estados (DataStore). Credenciais ficam criptografadas. */
@Singleton
class AppPrefs @Inject constructor(
    private val store: DataStore<Preferences>,
    private val secrets: SecretBox,
    private val json: Json,
) : PlugMemory, TuyaConfigSource {

    // ---------------------------------------------------------------- credenciais
    override suspend fun credentials(): TuyaCredentials? {
        val encrypted = store.data.first()[CREDENTIALS] ?: return null
        val plain = secrets.decrypt(encrypted) ?: return null
        return runCatching { json.decodeFromString(TuyaCredentials.serializer(), plain) }.getOrNull()
    }

    suspend fun saveCredentials(credentials: TuyaCredentials) {
        val encrypted = secrets.encrypt(json.encodeToString(TuyaCredentials.serializer(), credentials))
        store.edit { it[CREDENTIALS] = encrypted }
    }

    suspend fun clearCredentials() {
        store.edit {
            it.remove(CREDENTIALS)
            it.remove(PROFILE)
        }
    }

    val configured: Flow<Boolean> = store.data.map { it[CREDENTIALS] != null }

    override suspend fun profile(): DeviceProfile? =
        store.data.first()[PROFILE]?.let { runCatching { json.decodeFromString(DeviceProfile.serializer(), it) }.getOrNull() }

    val profileFlow: Flow<DeviceProfile?> = store.data.map { prefs ->
        prefs[PROFILE]?.let { runCatching { json.decodeFromString(DeviceProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun saveProfile(profile: DeviceProfile) {
        store.edit { it[PROFILE] = json.encodeToString(DeviceProfile.serializer(), profile) }
    }

    /** Dados não sensíveis para preencher o formulário (nunca o secret). */
    suspend fun credentialsSummary(): TuyaCredentials? = credentials()?.copy(clientSecret = "")

    // ----------------------------------------------------------------- PlugMemory
    override suspend fun lastObservedOn(): Boolean? = store.data.first()[OBSERVED_ON]
    override suspend fun setObservedOn(on: Boolean) { store.edit { it[OBSERVED_ON] = on } }
    override suspend fun expectedOn(): Boolean? = store.data.first()[EXPECTED_ON]
    override suspend fun setExpectedOn(on: Boolean, atMillis: Long) {
        store.edit {
            it[EXPECTED_ON] = on
            it[EXPECTED_AT] = atMillis
        }
    }

    // ------------------------------------------------------------------ localização
    suspend fun knownLocation(): KnownLocation? {
        val p = store.data.first()
        val lat = p[LAST_LAT] ?: return null
        val lng = p[LAST_LNG] ?: return null
        return KnownLocation(lat, lng, p[LAST_ACC] ?: 0f, p[LAST_AT] ?: 0L)
    }

    suspend fun saveKnownLocation(location: KnownLocation) {
        store.edit {
            it[LAST_LAT] = location.latitude
            it[LAST_LNG] = location.longitude
            it[LAST_ACC] = location.accuracyMeters
            it[LAST_AT] = location.atMillis
        }
    }

    // ---------------------------------------------------------------------- ajustes
    val notificationsEnabled: Flow<Boolean> = store.data.map { it[NOTIFICATIONS] ?: false }
    suspend fun setNotificationsEnabled(enabled: Boolean) { store.edit { it[NOTIFICATIONS] = enabled } }
    suspend fun notificationsEnabledNow(): Boolean = notificationsEnabled.first()

    val periodicMinutes: Flow<Int> = store.data.map { it[PERIODIC_MINUTES] ?: 15 }
    suspend fun setPeriodicMinutes(minutes: Int) { store.edit { it[PERIODIC_MINUTES] = minutes } }
    suspend fun periodicMinutesNow(): Int = periodicMinutes.first()

    private companion object {
        val CREDENTIALS = stringPreferencesKey("tuya_credentials_enc")
        val PROFILE = stringPreferencesKey("device_profile")
        val OBSERVED_ON = booleanPreferencesKey("observed_on")
        val EXPECTED_ON = booleanPreferencesKey("expected_on")
        val EXPECTED_AT = longPreferencesKey("expected_at")
        val LAST_LAT = doublePreferencesKey("last_lat")
        val LAST_LNG = doublePreferencesKey("last_lng")
        val LAST_ACC = floatPreferencesKey("last_acc")
        val LAST_AT = longPreferencesKey("last_at")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val PERIODIC_MINUTES = intPreferencesKey("periodic_minutes")
    }
}
