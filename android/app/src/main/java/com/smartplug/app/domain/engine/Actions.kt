package com.smartplug.app.domain.engine

import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.PlugState
import com.smartplug.app.domain.model.ToggleAction
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import kotlinx.coroutines.delay

sealed interface ActionResult {
    data class Done(val targetOn: Boolean, val after: PlugState) : ActionResult
    data class AlreadyInState(val on: Boolean) : ActionResult
    data class Failed(val kind: FailureKind, val message: String) : ActionResult
}

/** Executa uma [ActionSpec]. Novas ações: implemente e registre em `EngineModule`. */
interface ActionExecutor {
    fun supports(action: ActionSpec): Boolean

    /** [before] é o estado REAL lido imediatamente antes de agir. */
    suspend fun execute(action: ActionSpec, before: PlugState, gateway: PlugGateway): ActionResult
}

/** Ligar / desligar / alternar usando a mesma [PlugGateway] do controle manual. */
class PlugPowerExecutor(
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 1_500,
) : ActionExecutor {

    override fun supports(action: ActionSpec) =
        action is TurnOnAction || action is TurnOffAction || action is ToggleAction

    override suspend fun execute(action: ActionSpec, before: PlugState, gateway: PlugGateway): ActionResult {
        val target = when (action) {
            TurnOnAction -> true
            TurnOffAction -> false
            ToggleAction -> !before.on
            else -> error("ação não suportada: $action")
        }
        // Alternar sempre muda; ligar/desligar sobre o mesmo estado é duplicidade.
        if (action !is ToggleAction && before.on == target) return ActionResult.AlreadyInState(target)

        var last: GatewayResult.Failure? = null
        repeat(maxAttempts) { attempt ->
            when (val result = gateway.setPower(target)) {
                is GatewayResult.Success -> {
                    val after = result.value
                    return if (after.on == target) {
                        ActionResult.Done(target, after)
                    } else {
                        ActionResult.Failed(
                            FailureKind.UNCONFIRMED,
                            "Comando enviado, mas o plug não confirmou o novo estado.",
                        )
                    }
                }
                is GatewayResult.Failure -> {
                    last = result
                    val transient = result.kind in setOf(FailureKind.NO_NETWORK, FailureKind.TIMEOUT, FailureKind.UNKNOWN)
                    if (!transient || attempt == maxAttempts - 1) {
                        return ActionResult.Failed(result.kind, result.message)
                    }
                    delay(retryDelayMs * (attempt + 1))
                }
            }
        }
        return ActionResult.Failed(last?.kind ?: FailureKind.UNKNOWN, last?.message ?: "falha desconhecida")
    }
}
