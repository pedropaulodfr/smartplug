package com.smartplug.app.domain.engine

import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.PlugState
import com.smartplug.app.domain.model.TriState
import java.time.ZonedDateTime

sealed interface EngineEvent {
    data class GeofenceEnter(val locationId: Long) : EngineEvent
    data class GeofenceExit(val locationId: Long) : EngineEvent
    data class PlugChanged(val on: Boolean, val byAutomation: Boolean) : EngineEvent
}

enum class TriggerSource { PERIODIC, WAKE, GEOFENCE, BOOT, TIME_CHANGED, MANUAL, APP }

data class EngineTrigger(
    val source: TriggerSource,
    val events: List<EngineEvent> = emptyList(),
)

/** Tudo que um avaliador precisa saber, num instante. `plug == null` = dado indisponível. */
data class EvaluationContext(
    val now: ZonedDateTime,
    val plug: PlugState?,
    val expectedOn: Boolean?,
    val locations: Map<Long, LocationInfo>,
    val events: List<EngineEvent>,
)

data class LeafEval(val state: TriState, val text: String)

/** Avalia uma família de condições. Registre novas implementações em `EngineModule`. */
interface ConditionEvaluator {
    fun supports(condition: Condition): Boolean
    fun evaluate(condition: Condition, ctx: EvaluationContext): LeafEval
}

fun TriState.and(other: TriState): TriState = when {
    this == TriState.FALSE || other == TriState.FALSE -> TriState.FALSE
    this == TriState.UNKNOWN || other == TriState.UNKNOWN -> TriState.UNKNOWN
    else -> TriState.TRUE
}

fun TriState.or(other: TriState): TriState = when {
    this == TriState.TRUE || other == TriState.TRUE -> TriState.TRUE
    this == TriState.UNKNOWN || other == TriState.UNKNOWN -> TriState.UNKNOWN
    else -> TriState.FALSE
}

fun Boolean.toTri() = if (this) TriState.TRUE else TriState.FALSE
