package com.smartplug.app.integrations.tuya

import com.smartplug.app.domain.model.PlugState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Data centers da Tuya (a região é definida na criação do projeto e não pode ser movida). */
enum class TuyaRegion(val code: String, val label: String, val baseUrl: String) {
    US("us", "Western America", "https://openapi.tuyaus.com"),
    US_EAST("us-east", "Eastern America", "https://openapi-ueaz.tuyaus.com"),
    EU("eu", "Central Europe", "https://openapi.tuyaeu.com"),
    EU_WEST("eu-west", "Western Europe", "https://openapi-weaz.tuyaeu.com"),
    CN("cn", "China", "https://openapi.tuyacn.com"),
    IN("in", "India", "https://openapi.tuyain.com"),
    SG("sg", "Singapore", "https://openapi-sg.iotbing.com");

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code } ?: US
    }
}

/** Credenciais do projeto Tuya Cloud. Ficam apenas criptografadas (Keystore) no aparelho. */
@Serializable
data class TuyaCredentials(
    val clientId: String,
    val clientSecret: String,
    val region: String = TuyaRegion.US.code,
    val deviceId: String,
) {
    // Nunca exponha o secret em logs.
    override fun toString() = "TuyaCredentials(clientId=***, region=$region, deviceId=$deviceId)"
}

/**
 * Mapa de DPs do modelo, lido da especificação do produto (`/v1.1/devices/{id}/specifications`).
 * Os padrões abaixo correspondem ao WGHINE T34 (verificados na especificação real).
 */
@Serializable
data class DeviceProfile(
    val deviceName: String = "Smart Plug",
    val switchCode: String = "switch_1",
    val powerCode: String? = "cur_power",
    val voltageCode: String? = "cur_voltage",
    val currentCode: String? = "cur_current",
    val energyCode: String? = "add_ele",
    /** code -> escala (valor_real = bruto × 10^-escala). */
    val scales: Map<String, Int> = mapOf(
        "cur_power" to 1, "cur_voltage" to 1, "cur_current" to 0, "add_ele" to 3,
    ),
    /** code -> unidade informada pela Tuya (ex.: "mA", "W", "kW·h"). */
    val units: Map<String, String> = mapOf(
        "cur_power" to "W", "cur_voltage" to "V", "cur_current" to "mA", "add_ele" to "kW·h",
    ),
) {
    private fun scaled(code: String?, raw: JsonElement?, target: Target): Double? {
        if (code == null || raw == null) return null
        val number = (raw as? JsonPrimitive)?.doubleOrNull ?: return null
        var value = number / Math.pow(10.0, (scales[code] ?: 0).toDouble())
        val unit = units[code]?.lowercase()?.replace(" ", "")
        value = when (target) {
            Target.CURRENT -> if (unit == "ma") value / 1000.0 else value
            Target.POWER -> if (unit == "kw") value * 1000.0 else value
            Target.ENERGY -> if (unit == "wh") value / 1000.0 else value
            Target.VOLTAGE -> value
        }
        return value
    }

    private enum class Target { VOLTAGE, CURRENT, POWER, ENERGY }

    fun toPlugState(
        status: Map<String, JsonElement>,
        online: Boolean,
        latencyMs: Long,
        nowMs: Long,
    ): PlugState? {
        val on = (status[switchCode] as? JsonPrimitive)?.booleanOrNull ?: return null
        return PlugState(
            on = on,
            powerW = scaled(powerCode, status[powerCode], Target.POWER),
            voltageV = scaled(voltageCode, status[voltageCode], Target.VOLTAGE),
            currentA = scaled(currentCode, status[currentCode], Target.CURRENT),
            energyKwh = scaled(energyCode, status[energyCode], Target.ENERGY),
            online = online,
            latencyMs = latencyMs,
            readAtMillis = nowMs,
        )
    }

    companion object {
        /** Monta o perfil a partir de `result.status` da especificação. */
        fun fromSpecification(name: String, specs: List<SpecEntry>): DeviceProfile {
            fun pick(vararg codes: String) = codes.firstOrNull { c -> specs.any { it.code == c } }
            val switchCode = specs.firstOrNull { it.code == "switch_1" || it.code == "switch" }?.code
                ?: specs.firstOrNull { it.code.startsWith("switch") }?.code
                ?: "switch_1"
            val power = pick("cur_power")
            val voltage = pick("cur_voltage")
            val current = pick("cur_current")
            val energy = pick("add_ele")
            val relevant = listOfNotNull(power, voltage, current, energy)
            return DeviceProfile(
                deviceName = name,
                switchCode = switchCode,
                powerCode = power,
                voltageCode = voltage,
                currentCode = current,
                energyCode = energy,
                scales = relevant.associateWith { code -> specs.first { it.code == code }.scale },
                units = relevant.associateWith { code -> specs.first { it.code == code }.unit },
            )
        }
    }
}

data class SpecEntry(val code: String, val scale: Int, val unit: String)
