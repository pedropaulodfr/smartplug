package com.smartplug.app.domain.gateway

import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.AutomationRuntime
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.PlugState

enum class FailureKind { NOT_CONFIGURED, NO_NETWORK, TIMEOUT, AUTH, REJECTED, DEVICE_OFFLINE, UNCONFIRMED, UNKNOWN }

sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>
    data class Failure(val kind: FailureKind, val message: String) : GatewayResult<Nothing>
}

/**
 * Única porta de comunicação com o Smart Plug. O controle manual e as ações das
 * automações usam exatamente esta interface (implementada em integrations/tuya).
 */
interface PlugGateway {
    suspend fun readState(): GatewayResult<PlugState>

    /** Envia o comando e devolve o estado lido DEPOIS (confirmação). */
    suspend fun setPower(on: Boolean): GatewayResult<PlugState>
}

interface AutomationStore {
    suspend fun enabledAutomations(): List<Automation>
    suspend fun automation(id: Long): Automation?
    suspend fun runtime(automationId: Long): AutomationRuntime
    suspend fun saveRuntime(runtime: AutomationRuntime)
}

interface ExecutionLog {
    suspend fun append(record: ExecutionRecord)
    suspend fun successCountSince(automationId: Long, sinceMillis: Long): Int
}

interface LocationStateSource {
    /** Locais cadastrados com status atual; status UNKNOWN quando não há dado confiável. */
    suspend fun snapshots(): List<LocationInfo>
}

/** Memória do último estado observado e do último comando enviado por este app. */
interface PlugMemory {
    suspend fun lastObservedOn(): Boolean?
    suspend fun setObservedOn(on: Boolean)
    suspend fun expectedOn(): Boolean?
    suspend fun setExpectedOn(on: Boolean, atMillis: Long)
}

interface Clock {
    fun nowMillis(): Long

    /** Fuso lido a cada avaliação: mudanças de fuso são refletidas sem reiniciar nada. */
    fun zone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
}
