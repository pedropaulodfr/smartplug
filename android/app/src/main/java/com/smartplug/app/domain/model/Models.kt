package com.smartplug.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Leitura do Smart Plug já normalizada (V, A, W, kWh). */
data class PlugState(
    val on: Boolean,
    val powerW: Double?,
    val voltageV: Double?,
    val currentA: Double?,
    val energyKwh: Double?,
    val online: Boolean,
    val latencyMs: Long,
    val readAtMillis: Long,
)

enum class LogicOperator(val label: String) { AND("E"), OR("OU") }

/** Nó da árvore de condições: folha (condição) ou grupo (AND/OR). */
sealed interface ConditionNode {
    val id: Long
}

data class ConditionLeaf(
    override val id: Long = 0,
    val condition: Condition,
    /** Tempo que a condição precisa permanecer verdadeira antes de valer (0 = imediato). */
    val holdSeconds: Int = 0,
) : ConditionNode

data class ConditionGroup(
    override val id: Long = 0,
    val operator: LogicOperator,
    val children: List<ConditionNode>,
) : ConditionNode

@Serializable
sealed interface ActionSpec

@Serializable @SerialName("turn_on")
data object TurnOnAction : ActionSpec

@Serializable @SerialName("turn_off")
data object TurnOffAction : ActionSpec

@Serializable @SerialName("toggle")
data object ToggleAction : ActionSpec

data class Automation(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val icon: String = "bolt",
    val enabled: Boolean = true,
    val root: ConditionGroup,
    val action: ActionSpec,
    /** Intervalo mínimo entre duas execuções desta automação. */
    val minIntervalSeconds: Int = 60,
    /** Se definido, reexecuta a cada N segundos enquanto a condição continuar verdadeira. */
    val repeatEverySeconds: Int? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun leaves(): List<ConditionLeaf> = root.leaves()
    fun usesPlugData(): Boolean = leaves().any { it.condition.usesPlugData() }
}

fun ConditionNode.leaves(): List<ConditionLeaf> = when (this) {
    is ConditionLeaf -> listOf(this)
    is ConditionGroup -> children.flatMap { it.leaves() }
}

data class SavedLocation(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

enum class LocationStatus { INSIDE, OUTSIDE, UNKNOWN }

/** Local cadastrado + estado atual conhecido. */
data class LocationInfo(
    val location: SavedLocation,
    val status: LocationStatus,
    val distanceMeters: Double?,
)

/** Estado de execução persistido de uma automação (sobrevive a reinício). */
data class AutomationRuntime(
    val automationId: Long,
    val lastResult: Boolean? = null,
    val lastExecutedAtMillis: Long? = null,
    /** leafId -> instante (epoch ms) desde quando a condição está verdadeira. */
    val holds: Map<Long, Long> = emptyMap(),
)

enum class ExecutionOutcome(val label: String) {
    SUCCESS("Executada"),
    CONDITIONS_NOT_MET("Condições não atendidas"),
    COMM_FAILURE("Falha de comunicação"),
    IGNORED_DUPLICATE("Ignorada (duplicidade)"),
    IGNORED_CYCLE("Ignorada (ciclo)"),
}

enum class TriState { TRUE, FALSE, UNKNOWN }

@Serializable
data class TraceLine(val text: String, val result: String)

data class ExecutionRecord(
    val id: Long = 0,
    val automationId: Long?,
    val automationName: String,
    val timestampMillis: Long,
    val outcome: ExecutionOutcome,
    val trigger: String,
    val trace: List<TraceLine>,
    val action: String?,
    val stateBefore: Boolean?,
    val stateAfter: Boolean?,
    val commResult: String?,
    val message: String?,
    val isTest: Boolean = false,
)
