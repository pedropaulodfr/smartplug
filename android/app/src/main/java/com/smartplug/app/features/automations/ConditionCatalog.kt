package com.smartplug.app.features.automations

import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.Consuming
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.DistanceFromLocation
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.OutsideLocation
import com.smartplug.app.domain.model.PlugOff
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.SpecificDate
import com.smartplug.app.domain.model.StateChanged
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBefore
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.domain.model.UnexpectedState
import com.smartplug.app.domain.model.Weekdays
import com.smartplug.app.domain.model.Weekend
import java.time.LocalDate

enum class ConditionCategory(val label: String) {
    TIME("Horário"),
    LOCATION("Localização"),
    ELECTRIC("Consumo"),
    PLUG("Estado do plug"),
}

/**
 * Tipos de condição oferecidos no editor. Ao criar uma condição nova, adicione aqui o tipo e,
 * em [ConditionDialog], os campos dele.
 */
enum class ConditionKind(val category: ConditionCategory, val label: String) {
    TIME_AFTER(ConditionCategory.TIME, "Horário maior que"),
    TIME_BEFORE(ConditionCategory.TIME, "Horário menor que"),
    TIME_BETWEEN(ConditionCategory.TIME, "Horário entre"),
    TIME_EQUALS(ConditionCategory.TIME, "Horário igual a"),
    DAYS(ConditionCategory.TIME, "Dias da semana"),
    WEEKDAYS(ConditionCategory.TIME, "Dias úteis"),
    WEEKEND(ConditionCategory.TIME, "Fins de semana"),
    DATE(ConditionCategory.TIME, "Data específica"),

    INSIDE(ConditionCategory.LOCATION, "Dentro de um local"),
    OUTSIDE(ConditionCategory.LOCATION, "Fora de um local"),
    ENTERING(ConditionCategory.LOCATION, "Chegando em um local"),
    EXITING(ConditionCategory.LOCATION, "Saindo de um local"),
    DISTANCE(ConditionCategory.LOCATION, "Distância de um local"),

    METRIC(ConditionCategory.ELECTRIC, "Tensão, corrente, potência ou energia"),
    CONSUMING(ConditionCategory.ELECTRIC, "Equipamento consumindo"),
    NOT_CONSUMING(ConditionCategory.ELECTRIC, "Sem consumo significativo"),

    PLUG_ON(ConditionCategory.PLUG, "Plug ligado"),
    PLUG_OFF(ConditionCategory.PLUG, "Plug desligado"),
    UNEXPECTED(ConditionCategory.PLUG, "Estado diferente do esperado"),
    CHANGED(ConditionCategory.PLUG, "Mudança de estado");

    /** Verdadeiro só no instante do evento: não aceita "tempo de permanência". */
    val isEvent get() = this == ENTERING || this == EXITING || this == CHANGED
    val needsLocation get() = category == ConditionCategory.LOCATION
}

fun Condition.kind(): ConditionKind = when (this) {
    is TimeAfter -> ConditionKind.TIME_AFTER
    is TimeBefore -> ConditionKind.TIME_BEFORE
    is TimeBetween -> ConditionKind.TIME_BETWEEN
    is TimeEquals -> ConditionKind.TIME_EQUALS
    is DaysOfWeek -> ConditionKind.DAYS
    Weekdays -> ConditionKind.WEEKDAYS
    Weekend -> ConditionKind.WEEKEND
    is SpecificDate -> ConditionKind.DATE
    is InsideLocation -> ConditionKind.INSIDE
    is OutsideLocation -> ConditionKind.OUTSIDE
    is EnteringLocation -> ConditionKind.ENTERING
    is ExitingLocation -> ConditionKind.EXITING
    is DistanceFromLocation -> ConditionKind.DISTANCE
    is MetricCompare -> ConditionKind.METRIC
    is Consuming -> ConditionKind.CONSUMING
    is NotConsuming -> ConditionKind.NOT_CONSUMING
    PlugOn -> ConditionKind.PLUG_ON
    PlugOff -> ConditionKind.PLUG_OFF
    UnexpectedState -> ConditionKind.UNEXPECTED
    is StateChanged -> ConditionKind.CHANGED
}

/** Valor inicial razoável ao escolher um tipo no editor. */
fun ConditionKind.default(locations: List<SavedLocation>): Condition {
    val loc = locations.firstOrNull()?.id ?: 0L
    return when (this) {
        ConditionKind.TIME_AFTER -> TimeAfter(16 * 60)
        ConditionKind.TIME_BEFORE -> TimeBefore(23 * 60)
        ConditionKind.TIME_BETWEEN -> TimeBetween(0, 6 * 60)
        ConditionKind.TIME_EQUALS -> TimeEquals(18 * 60, 15)
        ConditionKind.DAYS -> DaysOfWeek(setOf(1, 2, 3, 4, 5))
        ConditionKind.WEEKDAYS -> Weekdays
        ConditionKind.WEEKEND -> Weekend
        ConditionKind.DATE -> SpecificDate(LocalDate.now().toEpochDay())
        ConditionKind.INSIDE -> InsideLocation(loc)
        ConditionKind.OUTSIDE -> OutsideLocation(loc)
        ConditionKind.ENTERING -> EnteringLocation(loc)
        ConditionKind.EXITING -> ExitingLocation(loc)
        ConditionKind.DISTANCE -> DistanceFromLocation(loc, Comparison.GREATER, 500.0)
        ConditionKind.METRIC -> MetricCompare(Metric.POWER, Comparison.GREATER, 100.0)
        ConditionKind.CONSUMING -> Consuming(2.0)
        ConditionKind.NOT_CONSUMING -> NotConsuming(2.0)
        ConditionKind.PLUG_ON -> PlugOn
        ConditionKind.PLUG_OFF -> PlugOff
        ConditionKind.UNEXPECTED -> UnexpectedState
        ConditionKind.CHANGED -> StateChanged(null)
    }
}
