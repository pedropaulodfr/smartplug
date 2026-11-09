package com.smartplug.app.domain.engine

import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.Consuming
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.DistanceFromLocation
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.OutsideLocation
import com.smartplug.app.domain.model.PlugOff
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.PlugState
import com.smartplug.app.domain.model.SpecificDate
import com.smartplug.app.domain.model.StateChanged
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBefore
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.domain.model.TriState
import com.smartplug.app.domain.model.UnexpectedState
import com.smartplug.app.domain.model.Weekdays
import com.smartplug.app.domain.model.Weekend

private fun EvaluationContext.locationName(id: Long) =
    locations[id]?.location?.name ?: "local removido"

private fun EvaluationContext.describe(c: Condition) =
    ConditionText.describe(c) { locationName(it) }

private fun EvaluationContext.minuteOfDay() = now.hour * 60 + now.minute

/** Horário, dias da semana e datas. Sempre usa o fuso horário atual do aparelho. */
class TimeEvaluator : ConditionEvaluator {
    override fun supports(condition: Condition) =
        condition is TimeAfter || condition is TimeBefore || condition is TimeBetween ||
            condition is TimeEquals || condition is DaysOfWeek || condition is Weekdays ||
            condition is Weekend || condition is SpecificDate

    override fun evaluate(condition: Condition, ctx: EvaluationContext): LeafEval {
        val minute = ctx.minuteOfDay()
        val dow = ctx.now.dayOfWeek.value
        val result = when (condition) {
            is TimeAfter -> minute >= condition.minuteOfDay
            is TimeBefore -> minute < condition.minuteOfDay
            is TimeBetween ->
                if (condition.fromMinute <= condition.toMinute) {
                    minute >= condition.fromMinute && minute < condition.toMinute
                } else {
                    minute >= condition.fromMinute || minute < condition.toMinute
                }
            is TimeEquals -> {
                val delta = (minute - condition.minuteOfDay + 1440) % 1440
                delta < condition.toleranceMinutes.coerceAtLeast(1)
            }
            is DaysOfWeek -> dow in condition.days
            Weekdays -> dow in 1..5
            Weekend -> dow in 6..7
            is SpecificDate -> ctx.now.toLocalDate().toEpochDay() == condition.epochDay
            else -> error("condição não suportada: $condition")
        }
        return LeafEval(result.toTri(), ctx.describe(condition))
    }
}

/** Dentro/fora/entrando/saindo/distância. Sem dado de localização => UNKNOWN (nunca "fora"). */
class LocationEvaluator : ConditionEvaluator {
    override fun supports(condition: Condition) =
        condition is InsideLocation || condition is OutsideLocation ||
            condition is EnteringLocation || condition is ExitingLocation ||
            condition is DistanceFromLocation

    override fun evaluate(condition: Condition, ctx: EvaluationContext): LeafEval {
        val text = ctx.describe(condition)
        return when (condition) {
            is InsideLocation -> status(condition.locationId, ctx, text) { it == LocationStatus.INSIDE }
            is OutsideLocation -> status(condition.locationId, ctx, text) { it == LocationStatus.OUTSIDE }
            is EnteringLocation -> LeafEval(
                ctx.events.any { it is EngineEvent.GeofenceEnter && it.locationId == condition.locationId }.toTri(),
                text,
            )
            is ExitingLocation -> LeafEval(
                ctx.events.any { it is EngineEvent.GeofenceExit && it.locationId == condition.locationId }.toTri(),
                text,
            )
            is DistanceFromLocation -> {
                val distance = ctx.locations[condition.locationId]?.distanceMeters
                if (distance == null) {
                    LeafEval(TriState.UNKNOWN, "$text (distância indisponível)")
                } else {
                    LeafEval(
                        condition.comparison.test(distance, condition.meters).toTri(),
                        "$text (atual: ${ConditionText.number(Math.round(distance).toDouble())} m)",
                    )
                }
            }
            else -> error("condição não suportada: $condition")
        }
    }

    private inline fun status(
        locationId: Long,
        ctx: EvaluationContext,
        text: String,
        test: (LocationStatus) -> Boolean,
    ): LeafEval {
        val info = ctx.locations[locationId]
        return if (info == null || info.status == LocationStatus.UNKNOWN) {
            LeafEval(TriState.UNKNOWN, "$text (localização indisponível)")
        } else {
            LeafEval(test(info.status).toTri(), text)
        }
    }
}

/** Tensão, corrente, potência e energia. Plug offline/sem leitura => UNKNOWN. */
class ElectricEvaluator : ConditionEvaluator {
    override fun supports(condition: Condition) =
        condition is MetricCompare || condition is Consuming || condition is NotConsuming

    override fun evaluate(condition: Condition, ctx: EvaluationContext): LeafEval {
        val text = ctx.describe(condition)
        val plug = ctx.plug
        if (plug == null || !plug.online) return LeafEval(TriState.UNKNOWN, "$text (plug sem dados)")
        return when (condition) {
            is MetricCompare -> {
                val value = plug.metric(condition.metric)
                    ?: return LeafEval(TriState.UNKNOWN, "$text (medição indisponível)")
                LeafEval(
                    condition.comparison.test(value, condition.threshold).toTri(),
                    "$text (atual: ${ConditionText.number(value)} ${condition.metric.unit})",
                )
            }
            is Consuming -> {
                val power = plug.powerW ?: return LeafEval(TriState.UNKNOWN, "$text (medição indisponível)")
                LeafEval((power >= condition.thresholdWatts).toTri(), "$text (atual: ${ConditionText.number(power)} W)")
            }
            is NotConsuming -> {
                val power = plug.powerW ?: return LeafEval(TriState.UNKNOWN, "$text (medição indisponível)")
                LeafEval((power < condition.thresholdWatts).toTri(), "$text (atual: ${ConditionText.number(power)} W)")
            }
            else -> error("condição não suportada: $condition")
        }
    }

    private fun PlugState.metric(metric: Metric): Double? = when (metric) {
        Metric.VOLTAGE -> voltageV
        Metric.CURRENT -> currentA
        Metric.POWER -> powerW
        Metric.ENERGY -> energyKwh
    }
}

/** Ligado/desligado, estado inesperado e mudança de estado. */
class PlugStateEvaluator : ConditionEvaluator {
    override fun supports(condition: Condition) =
        condition is PlugOn || condition is PlugOff || condition is UnexpectedState ||
            condition is StateChanged

    override fun evaluate(condition: Condition, ctx: EvaluationContext): LeafEval {
        val text = ctx.describe(condition)
        if (condition is StateChanged) {
            val hit = ctx.events.any {
                it is EngineEvent.PlugChanged && (condition.to == null || it.on == condition.to)
            }
            return LeafEval(hit.toTri(), text)
        }
        val plug = ctx.plug
        if (plug == null || !plug.online) return LeafEval(TriState.UNKNOWN, "$text (plug sem dados)")
        return when (condition) {
            PlugOn -> LeafEval(plug.on.toTri(), text)
            PlugOff -> LeafEval((!plug.on).toTri(), text)
            UnexpectedState -> {
                val expected = ctx.expectedOn
                    ?: return LeafEval(TriState.UNKNOWN, "$text (nenhum comando enviado ainda)")
                LeafEval((plug.on != expected).toTri(), text)
            }
            else -> error("condição não suportada: $condition")
        }
    }
}

fun defaultEvaluators(): Set<ConditionEvaluator> =
    setOf(TimeEvaluator(), LocationEvaluator(), ElectricEvaluator(), PlugStateEvaluator())
