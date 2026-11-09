package com.smartplug.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Uma condição atômica. Para criar um novo tipo:
 *  1. adicione uma subclasse @Serializable aqui;
 *  2. crie um [com.smartplug.app.domain.engine.ConditionEvaluator] e registre em `EngineModule`;
 *  3. descreva-a em [ConditionText] e exponha-a no editor (`ConditionCatalog`).
 * O motor, a persistência e o histórico não precisam mudar.
 */
@Serializable
sealed interface Condition

enum class Comparison(val symbol: String, val label: String) {
    GREATER(">", "maior que"),
    LESS("<", "menor que");

    fun test(value: Double, threshold: Double) = when (this) {
        GREATER -> value > threshold
        LESS -> value < threshold
    }
}

enum class Metric(val label: String, val unit: String) {
    VOLTAGE("Tensão", "V"),
    CURRENT("Corrente", "A"),
    POWER("Potência", "W"),
    ENERGY("Consumo acumulado", "kWh"),
}

// ---------------------------------------------------------------- horário
@Serializable @SerialName("time_after")
data class TimeAfter(val minuteOfDay: Int) : Condition

@Serializable @SerialName("time_before")
data class TimeBefore(val minuteOfDay: Int) : Condition

/** Intervalo [from, to); aceita virada de dia (ex.: 22:00–06:00). */
@Serializable @SerialName("time_between")
data class TimeBetween(val fromMinute: Int, val toMinute: Int) : Condition

/** Verdadeira na janela [minuteOfDay, minuteOfDay + toleranceMinutes). */
@Serializable @SerialName("time_equals")
data class TimeEquals(val minuteOfDay: Int, val toleranceMinutes: Int = 15) : Condition

/** Dias ISO: 1 = segunda … 7 = domingo. */
@Serializable @SerialName("days_of_week")
data class DaysOfWeek(val days: Set<Int>) : Condition

@Serializable @SerialName("weekdays")
data object Weekdays : Condition

@Serializable @SerialName("weekend")
data object Weekend : Condition

@Serializable @SerialName("specific_date")
data class SpecificDate(val epochDay: Long) : Condition

// ------------------------------------------------------------ localização
@Serializable @SerialName("inside_location")
data class InsideLocation(val locationId: Long) : Condition

@Serializable @SerialName("outside_location")
data class OutsideLocation(val locationId: Long) : Condition

/** Evento: verdadeira apenas na avaliação provocada pela entrada na região. */
@Serializable @SerialName("entering_location")
data class EnteringLocation(val locationId: Long) : Condition

/** Evento: verdadeira apenas na avaliação provocada pela saída da região. */
@Serializable @SerialName("exiting_location")
data class ExitingLocation(val locationId: Long) : Condition

@Serializable @SerialName("distance_from_location")
data class DistanceFromLocation(
    val locationId: Long,
    val comparison: Comparison,
    val meters: Double,
) : Condition

// ----------------------------------------------------------------- consumo
@Serializable @SerialName("metric_compare")
data class MetricCompare(
    val metric: Metric,
    val comparison: Comparison,
    val threshold: Double,
) : Condition

@Serializable @SerialName("consuming")
data class Consuming(val thresholdWatts: Double = 2.0) : Condition

@Serializable @SerialName("not_consuming")
data class NotConsuming(val thresholdWatts: Double = 2.0) : Condition

// -------------------------------------------------------------- estado do plug
@Serializable @SerialName("plug_on")
data object PlugOn : Condition

@Serializable @SerialName("plug_off")
data object PlugOff : Condition

/** Estado real diferente do último comando enviado por este app. */
@Serializable @SerialName("unexpected_state")
data object UnexpectedState : Condition

/** Evento: mudança de estado observada (para [to] = null, qualquer mudança). */
@Serializable @SerialName("state_changed")
data class StateChanged(val to: Boolean? = null) : Condition

/** Condições que dependem de um evento pontual e, por isso, não admitem tempo de permanência. */
fun Condition.isEvent(): Boolean =
    this is EnteringLocation || this is ExitingLocation || this is StateChanged

fun Condition.usesPlugData(): Boolean =
    this is MetricCompare || this is Consuming || this is NotConsuming ||
        this is PlugOn || this is PlugOff || this is UnexpectedState || this is StateChanged

fun Condition.locationId(): Long? = when (this) {
    is InsideLocation -> locationId
    is OutsideLocation -> locationId
    is EnteringLocation -> locationId
    is ExitingLocation -> locationId
    is DistanceFromLocation -> locationId
    else -> null
}
