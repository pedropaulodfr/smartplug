package com.smartplug.app.integrations.tuya

import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.model.PlugState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** [PlugGateway] sobre a Tuya Cloud OpenAPI. Usado por controle manual e automações. */
@Singleton
class TuyaPlugGateway @Inject constructor(
    private val api: TuyaApiClient,
    private val config: TuyaConfigSource,
    private val clock: Clock,
) : PlugGateway {

    override suspend fun readState(): GatewayResult<PlugState> = guarded {
        val creds = config.credentials() ?: return@guarded notConfigured()
        val profile = config.profile() ?: DeviceProfile()
        GatewayResult.Success(fetch(creds, profile))
    }

    override suspend fun setPower(on: Boolean): GatewayResult<PlugState> = guarded {
        val creds = config.credentials() ?: return@guarded notConfigured()
        val profile = config.profile() ?: DeviceProfile()

        val body = buildJsonObject {
            put("commands", buildJsonArray {
                add(buildJsonObject {
                    put("code", profile.switchCode)
                    put("value", on)
                })
            })
        }.toString()
        val response = api.post(creds, "/v1.0/devices/${creds.deviceId}/commands", body)
        val accepted = (response.result as? JsonPrimitive)?.booleanOrNull
        if (accepted == false) {
            return@guarded GatewayResult.Failure(FailureKind.REJECTED, "A Tuya recusou o comando.")
        }

        // Confirmação: a nuvem leva ~1–3 s para refletir o novo estado.
        var latest: PlugState? = null
        repeat(CONFIRM_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) 700 else 900)
            latest = fetch(creds, profile)
            if (latest!!.on == on) return@guarded GatewayResult.Success(latest!!)
        }
        GatewayResult.Success(latest!!) // devolve o estado real, mesmo que não tenha confirmado
    }

    private suspend fun fetch(creds: TuyaCredentials, profile: DeviceProfile): PlugState {
        val response = api.get(creds, "/v1.0/devices/${creds.deviceId}")
        val result = response.result?.jsonObject
            ?: throw TuyaException(FailureKind.UNKNOWN, null, "Resposta sem dados do dispositivo.")
        val online = (result["online"] as? JsonPrimitive)?.booleanOrNull ?: false
        val status = result["status"]?.jsonArray.toStatusMap()
        return profile.toPlugState(status, online, response.latencyMs, clock.nowMillis())
            ?: throw TuyaException(
                FailureKind.UNKNOWN, null,
                "O dispositivo não expõe o DP '${profile.switchCode}'. Refaça a configuração.",
            )
    }

    private fun JsonArray?.toStatusMap(): Map<String, JsonElement> {
        if (this == null) return emptyMap()
        val map = mutableMapOf<String, JsonElement>()
        for (item in this) {
            val obj = item as? JsonObject ?: continue
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull ?: continue
            obj["value"]?.let { map[code] = it }
        }
        return map
    }

    private fun notConfigured() = GatewayResult.Failure(
        FailureKind.NOT_CONFIGURED,
        "Configure a conta Tuya em Ajustes.",
    )

    private suspend fun guarded(block: suspend () -> GatewayResult<PlugState>): GatewayResult<PlugState> =
        try {
            withTimeout(OPERATION_TIMEOUT_MS) { block() }
        } catch (e: TuyaException) {
            GatewayResult.Failure(e.kind, e.message ?: "Erro na Tuya")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            GatewayResult.Failure(FailureKind.TIMEOUT, "A operação excedeu o tempo limite.")
        }

    private companion object {
        const val OPERATION_TIMEOUT_MS = 25_000L
        const val CONFIRM_ATTEMPTS = 4
    }
}
