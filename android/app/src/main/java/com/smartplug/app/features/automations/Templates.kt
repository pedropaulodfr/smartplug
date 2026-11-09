package com.smartplug.app.features.automations

import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.domain.model.Weekdays

data class AutomationTemplate(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val needsLocation: Boolean,
    private val factory: (locationId: Long) -> Automation,
) {
    fun build(locationId: Long): Automation = factory(locationId)
}

private fun draft(
    name: String,
    description: String,
    icon: String,
    action: ActionSpec,
    operator: LogicOperator = LogicOperator.AND,
    vararg conditions: Pair<Condition, Int>,
): Automation = Automation(
    name = name,
    description = description,
    icon = icon,
    root = ConditionGroup(
        id = ConditionTree.ROOT_ID,
        operator = operator,
        children = conditions.mapIndexed { i, (c, hold) -> ConditionLeaf(-(i + 2).toLong(), c, hold) },
    ),
    action = action,
)

/** Modelos prontos: o usuário pode ajustar tudo antes de salvar. */
object Templates {
    val all: List<AutomationTemplate> = listOf(
        AutomationTemplate("arrive_home", "Ligar ao chegar em casa", "Liga o plug quando você entra na área do local.", "home", true) { loc ->
            draft("Ligar ao chegar em casa", "Liga o plug quando eu chego.", "home", TurnOnAction,
                conditions = arrayOf(EnteringLocation(loc) to 0))
        },
        AutomationTemplate("arrive_home_evening", "Ligar ao chegar em casa após as 16h", "Só liga se for depois das 16:00 e você estiver em casa.", "home", true) { loc ->
            draft("Ligar em casa após as 16h", "Liga quando estou em casa depois das 16:00.", "home", TurnOnAction,
                conditions = arrayOf(TimeAfter(16 * 60) to 0, InsideLocation(loc) to 0))
        },
        AutomationTemplate("leave_home", "Desligar ao sair de casa", "Desliga o plug quando você sai da área do local.", "walk", true) { loc ->
            draft("Desligar ao sair de casa", "Desliga quando eu saio.", "walk", TurnOffAction,
                conditions = arrayOf(ExitingLocation(loc) to 0))
        },
        AutomationTemplate("night_off", "Desligar durante a madrugada", "Desliga entre 00:00 e 06:00.", "moon", false) {
            draft("Desligar na madrugada", "Desliga entre 00:00 e 06:00.", "moon", TurnOffAction,
                conditions = arrayOf(TimeBetween(0, 6 * 60) to 0))
        },
        AutomationTemplate("on_at_time", "Ligar em determinado horário", "Liga todos os dias no horário escolhido.", "sun", false) {
            draft("Ligar às 18:00", "Liga no horário definido.", "sun", TurnOnAction,
                conditions = arrayOf(TimeEquals(18 * 60, 15) to 0))
        },
        AutomationTemplate("off_at_time", "Desligar em determinado horário", "Desliga todos os dias no horário escolhido.", "schedule", false) {
            draft("Desligar às 23:00", "Desliga no horário definido.", "schedule", TurnOffAction,
                conditions = arrayOf(TimeEquals(23 * 60, 15) to 0))
        },
        AutomationTemplate("idle_off", "Desligar quando parar de consumir", "Desliga se a potência ficar abaixo de 2 W por 10 minutos.", "eco", false) {
            draft("Desligar sem consumo", "Desliga após 10 min abaixo de 2 W.", "eco", TurnOffAction,
                conditions = arrayOf(PlugOn to 0, NotConsuming(2.0) to 600))
        },
        AutomationTemplate("weekday_evening", "Em casa nos dias úteis à noite", "Liga de seg–sex, entre 16:00 e 23:00, quando você está em casa.", "light", true) { loc ->
            draft("Noite em casa (dias úteis)", "Liga nos dias úteis à noite quando estou em casa.", "light", TurnOnAction,
                conditions = arrayOf(TimeBetween(16 * 60, 23 * 60) to 0, InsideLocation(loc) to 0, Weekdays to 0))
        },
        AutomationTemplate("power_limit", "Ligar quando a potência passar de um limite", "Liga o plug se a potência ultrapassar o valor definido.", "power", false) {
            draft("Potência acima do limite", "Liga quando a potência passa do limite.", "power", TurnOnAction,
                conditions = arrayOf(MetricCompare(Metric.POWER, Comparison.GREATER, 100.0) to 0))
        },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
