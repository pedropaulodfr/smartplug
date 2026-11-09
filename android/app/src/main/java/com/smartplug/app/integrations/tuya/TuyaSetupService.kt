package com.smartplug.app.integrations.tuya

import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.gateway.FailureKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

sealed interface SetupResult {
    data class Success(val profile: DeviceProfile, val online: Boolean) : SetupResult
    data class Failure(val message: String, val kind: FailureKind) : SetupResult
}

/**
 * Valida as credenciais com chamadas reais, descobre o mapa de DPs do modelo e só então
 * grava tudo (credenciais criptografadas + perfil).
 */
@Singleton
class TuyaSetupService @Inject constructor(
    private val api: TuyaApiClient,
    private val prefs: AppPrefs,
    private val json: Json,
) {
    suspend fun verifyAndSave(creds: TuyaCredentials): SetupResult {
        return try {
            val details = api.get(creds, "/v1.0/devices/${creds.deviceId}").result?.jsonObject
                ?: return SetupResult.Failure("Dispositivo não encontrado.", FailureKind.REJECTED)
            val name = (details["name"] as? JsonPrimitive)?.contentOrNull ?: "Smart Plug"
            val online = (details["online"] as? JsonPrimitive)?.contentOrNull == "true"

            val specEntries = runCatching {
                val spec = api.get(creds, "/v1.1/devices/${creds.deviceId}/specifications").result?.jsonObject
                spec?.get("status")?.jsonArray.toSpecEntries()
            }.getOrNull().orEmpty()

            val profile = if (specEntries.isEmpty()) DeviceProfile(deviceName = name)
            else DeviceProfile.fromSpecification(name, specEntries)

            prefs.saveCredentials(creds)
            prefs.saveProfile(profile)
            SetupResult.Success(profile, online)
        } catch (e: TuyaException) {
            SetupResult.Failure(e.message ?: "Erro na Tuya", e.kind)
        }
    }

    private fun JsonArray?.toSpecEntries(): List<SpecEntry> {
        if (this == null) return emptyList()
        return mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val values = (obj["values"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val scale = (values?.get("scale") as? JsonPrimitive)?.intOrNull ?: 0
            val unit = (values?.get("unit") as? JsonPrimitive)?.contentOrNull.orEmpty()
            SpecEntry(code, scale, unit)
        }
    }
}
