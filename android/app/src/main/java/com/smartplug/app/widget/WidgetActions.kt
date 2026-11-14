package com.smartplug.app.widget

import android.content.Context
import com.smartplug.app.R
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.model.PlugState
import kotlinx.coroutines.withTimeout

/**
 * Logica do widget, executada a partir do `goAsync()` do receptor.
 *
 * Nao usa WorkManager de proposito: o widget precisa de resposta imediata ao
 * toque e um trabalho unico pendente poderia bloquear toques seguintes.
 * Os timeouts mantem a execucao dentro da janela do broadcast (~10 s).
 */
class WidgetActions(
    private val gateway: PlugGateway,
    private val prefs: AppPrefs,
    private val clock: Clock,
) {

    suspend fun toggle(context: Context) {
        markPending(context)

        val current = readSafely()
        if (current is GatewayResult.Failure) {
            fail(context, current)
            return
        }

        val target = !(current as GatewayResult.Success).value.on
        when (val result = setSafely(target)) {
            is GatewayResult.Success -> persist(context, result.value, manual = true)
            is GatewayResult.Failure -> fail(context, result)
        }
    }

    suspend fun refresh(context: Context) {
        when (val result = readSafely()) {
            is GatewayResult.Success -> persist(context, result.value, manual = false)
            is GatewayResult.Failure -> fail(context, result)
        }
    }

    private suspend fun readSafely(): GatewayResult<PlugState> = try {
        withTimeout(READ_TIMEOUT_MS) { gateway.readState() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        GatewayResult.Failure(FailureKind.TIMEOUT, "A leitura excedeu o tempo limite.")
    } catch (e: Exception) {
        GatewayResult.Failure(FailureKind.UNKNOWN, e.message ?: "Falha ao ler o estado.")
    }

    private suspend fun setSafely(on: Boolean): GatewayResult<PlugState> = try {
        withTimeout(WRITE_TIMEOUT_MS) { gateway.setPower(on) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        GatewayResult.Failure(FailureKind.TIMEOUT, "O comando excedeu o tempo limite.")
    } catch (e: Exception) {
        GatewayResult.Failure(FailureKind.UNKNOWN, e.message ?: "Falha ao enviar o comando.")
    }

    private suspend fun persist(context: Context, state: PlugState, manual: Boolean) {
        val now = clock.nowMillis()
        if (manual) runCatching { prefs.setExpectedOn(state.on, now) }
        runCatching { prefs.setObservedOn(state.on) }

        PlugWidgetState.write(
            context,
            PlugWidgetUi(
                on = state.on,
                powerW = state.powerW,
                voltageV = state.voltageV,
                phase = PlugWidgetPhase.IDLE,
                message = null,
                updatedAtMillis = now,
            ),
        )
        PlugWidgetRenderer.updateAll(context)
    }

    private fun markPending(context: Context) {
        val current = PlugWidgetState.read(context)
        PlugWidgetState.write(
            context,
            current.copy(phase = PlugWidgetPhase.PENDING, message = null),
        )
        PlugWidgetRenderer.updateAll(context)
    }

    private fun fail(context: Context, failure: GatewayResult.Failure) {
        val current = PlugWidgetState.read(context)
        PlugWidgetState.write(
            context,
            current.copy(
                phase = PlugWidgetPhase.ERROR,
                message = context.getString(messageFor(failure.kind)),
                updatedAtMillis = clock.nowMillis(),
            ),
        )
        PlugWidgetRenderer.updateAll(context)
    }

    private fun messageFor(kind: FailureKind): Int = when (kind) {
        FailureKind.NOT_CONFIGURED -> R.string.widget_error_not_configured
        FailureKind.NO_NETWORK -> R.string.widget_error_network
        FailureKind.TIMEOUT -> R.string.widget_error_timeout
        FailureKind.AUTH -> R.string.widget_error_auth
        FailureKind.DEVICE_OFFLINE -> R.string.widget_error_offline
        FailureKind.REJECTED -> R.string.widget_error_rejected
        FailureKind.UNCONFIRMED -> R.string.widget_error_unconfirmed
        FailureKind.UNKNOWN -> R.string.widget_error_unknown
    }

    companion object {
        private const val READ_TIMEOUT_MS = 7_000L
        private const val WRITE_TIMEOUT_MS = 9_000L
    }
}
