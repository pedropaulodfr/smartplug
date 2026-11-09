package com.smartplug.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Texto legível (pt-BR) de condições, ações e regras. Usado pela UI e pelo histórico. */
object ConditionText {
    private val ptBr = Locale.forLanguageTag("pt-BR")

    fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else "%.2f".format(ptBr, value).trimEnd('0').trimEnd(',')

    fun duration(seconds: Int): String = when {
        seconds % 3600 == 0 && seconds >= 3600 -> "${seconds / 3600} h"
        seconds % 60 == 0 && seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    private fun dayName(iso: Int) =
        DayOfWeek.of(iso).getDisplayName(TextStyle.SHORT, ptBr).trimEnd('.')

    fun describe(condition: Condition, locationName: (Long) -> String): String = when (condition) {
        is TimeAfter -> "Horário maior que ${hhmm(condition.minuteOfDay)}"
        is TimeBefore -> "Horário menor que ${hhmm(condition.minuteOfDay)}"
        is TimeBetween -> "Horário entre ${hhmm(condition.fromMinute)} e ${hhmm(condition.toMinute)}"
        is TimeEquals -> "Horário igual a ${hhmm(condition.minuteOfDay)}"
        is DaysOfWeek -> "Dias: " + condition.days.sorted().joinToString(", ") { dayName(it) }
        Weekdays -> "Dias úteis (seg–sex)"
        Weekend -> "Fim de semana"
        is SpecificDate -> "Data ${LocalDate.ofEpochDay(condition.epochDay).format(
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))}"
        is InsideLocation -> "Dentro de ${locationName(condition.locationId)}"
        is OutsideLocation -> "Fora de ${locationName(condition.locationId)}"
        is EnteringLocation -> "Chegando em ${locationName(condition.locationId)}"
        is ExitingLocation -> "Saindo de ${locationName(condition.locationId)}"
        is DistanceFromLocation ->
            "Distância de ${locationName(condition.locationId)} ${condition.comparison.label} " +
                "${number(condition.meters)} m"
        is MetricCompare ->
            "${condition.metric.label} ${condition.comparison.label} " +
                "${number(condition.threshold)} ${condition.metric.unit}"
        is Consuming -> "Equipamento consumindo (≥ ${number(condition.thresholdWatts)} W)"
        is NotConsuming -> "Sem consumo significativo (< ${number(condition.thresholdWatts)} W)"
        PlugOn -> "Plug ligado"
        PlugOff -> "Plug desligado"
        UnexpectedState -> "Estado diferente do esperado"
        is StateChanged -> when (condition.to) {
            true -> "Plug passou a ligado"
            false -> "Plug passou a desligado"
            null -> "Estado do plug mudou"
        }
    }

    fun describe(leaf: ConditionLeaf, locationName: (Long) -> String): String {
        val base = describe(leaf.condition, locationName)
        return if (leaf.holdSeconds > 0) "$base por pelo menos ${duration(leaf.holdSeconds)}" else base
    }

    fun describe(action: ActionSpec): String = when (action) {
        TurnOnAction -> "Ligar Smart Plug"
        TurnOffAction -> "Desligar Smart Plug"
        ToggleAction -> "Alternar estado do Smart Plug"
    }
}
