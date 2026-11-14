package com.smartplug.app.widget

import android.content.Context

enum class PlugWidgetPhase { IDLE, PENDING, ERROR }

/** O que o widget deve mostrar. `on == null` significa "ainda nao sabemos". */
data class PlugWidgetUi(
    val on: Boolean? = null,
    val powerW: Double? = null,
    val voltageV: Double? = null,
    val phase: PlugWidgetPhase = PlugWidgetPhase.IDLE,
    val message: String? = null,
    val updatedAtMillis: Long = 0L,
)

/**
 * Cache do estado exibido no widget.
 *
 * Fica em SharedPreferences (e nao no DataStore) porque o AppWidgetProvider
 * precisa ler de forma sincrona, no thread principal, para desenhar a tela.
 */
object PlugWidgetState {

    private const val FILE = "smartplug_widget"
    private const val KEY_ON = "on"
    private const val KEY_PHASE = "phase"
    private const val KEY_POWER = "power"
    private const val KEY_VOLTAGE = "voltage"
    private const val KEY_MESSAGE = "message"
    private const val KEY_UPDATED_AT = "updated_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): PlugWidgetUi {
        val p = prefs(context)
        val phase = runCatching {
            PlugWidgetPhase.valueOf(p.getString(KEY_PHASE, null) ?: PlugWidgetPhase.IDLE.name)
        }.getOrDefault(PlugWidgetPhase.IDLE)

        return PlugWidgetUi(
            on = if (p.contains(KEY_ON)) p.getBoolean(KEY_ON, false) else null,
            phase = phase,
            powerW = if (p.contains(KEY_POWER)) p.getFloat(KEY_POWER, 0f).toDouble() else null,
            voltageV = if (p.contains(KEY_VOLTAGE)) p.getFloat(KEY_VOLTAGE, 0f).toDouble() else null,
            message = p.getString(KEY_MESSAGE, null),
            updatedAtMillis = p.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    fun write(context: Context, ui: PlugWidgetUi) {
        prefs(context).edit().apply {
            if (ui.on == null) remove(KEY_ON) else putBoolean(KEY_ON, ui.on)
            putString(KEY_PHASE, ui.phase.name)
            if (ui.powerW == null) remove(KEY_POWER) else putFloat(KEY_POWER, ui.powerW.toFloat())
            if (ui.voltageV == null) remove(KEY_VOLTAGE) else putFloat(KEY_VOLTAGE, ui.voltageV.toFloat())
            if (ui.message == null) remove(KEY_MESSAGE) else putString(KEY_MESSAGE, ui.message)
            putLong(KEY_UPDATED_AT, ui.updatedAtMillis)
        }.apply()
    }
}
