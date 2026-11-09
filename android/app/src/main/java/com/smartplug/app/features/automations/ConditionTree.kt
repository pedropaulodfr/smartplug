package com.smartplug.app.features.automations

import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionNode
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.DistanceFromLocation
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.isEvent
import com.smartplug.app.domain.model.leaves
import com.smartplug.app.domain.model.locationId

/** Operações puras sobre a árvore de condições do editor (testáveis sem Android). */
object ConditionTree {

    const val ROOT_ID = -1L

    fun emptyRoot() = ConditionGroup(ROOT_ID, LogicOperator.AND, emptyList())

    fun addChild(root: ConditionGroup, parentId: Long, node: ConditionNode): ConditionGroup =
        mapGroup(root, parentId) { it.copy(children = it.children + node) }

    fun remove(root: ConditionGroup, id: Long): ConditionGroup = root.copy(
        children = root.children.filter { it.id != id }.map {
            if (it is ConditionGroup) remove(it, id) else it
        },
    )

    fun updateLeaf(root: ConditionGroup, id: Long, condition: Condition, holdSeconds: Int): ConditionGroup =
        root.copy(
            children = root.children.map {
                when {
                    it is ConditionLeaf && it.id == id -> it.copy(condition = condition, holdSeconds = holdSeconds)
                    it is ConditionGroup -> updateLeaf(it, id, condition, holdSeconds)
                    else -> it
                }
            },
        )

    fun setOperator(root: ConditionGroup, groupId: Long, operator: LogicOperator): ConditionGroup =
        mapGroup(root, groupId) { it.copy(operator = operator) }

    private fun mapGroup(group: ConditionGroup, id: Long, f: (ConditionGroup) -> ConditionGroup): ConditionGroup =
        if (group.id == id) f(group)
        else group.copy(children = group.children.map { if (it is ConditionGroup) mapGroup(it, id, f) else it })

    fun findLeaf(group: ConditionGroup, id: Long): ConditionLeaf? =
        group.leaves().firstOrNull { it.id == id }

    /** Mensagens de erro (vazio = válido). */
    fun validate(root: ConditionGroup, knownLocations: Set<Long>): List<String> {
        val errors = mutableListOf<String>()
        if (root.leaves().isEmpty()) errors += "Adicione ao menos uma condição."
        fun walk(group: ConditionGroup, isRoot: Boolean) {
            if (group.children.isEmpty() && !isRoot) errors += "Há um grupo vazio: adicione condições ou remova-o."
            group.children.forEach { if (it is ConditionGroup) walk(it, false) }
        }
        walk(root, true)
        root.leaves().forEach { leaf ->
            val c = leaf.condition
            c.locationId()?.let { if (it !in knownLocations) errors += "Selecione um local válido para a condição de localização." }
            when (c) {
                is TimeBetween -> if (c.fromMinute == c.toMinute) errors += "O intervalo de horário não pode começar e terminar na mesma hora."
                is DaysOfWeek -> if (c.days.isEmpty()) errors += "Escolha ao menos um dia da semana."
                is DistanceFromLocation -> if (c.meters < 0) errors += "A distância não pode ser negativa."
                is MetricCompare -> if (c.threshold.isNaN()) errors += "Informe um valor numérico para o consumo."
                else -> Unit
            }
            if (leaf.holdSeconds > 0 && c.isEvent()) errors += "Eventos (chegar/sair/mudança de estado) não aceitam tempo de permanência."
        }
        return errors.distinct()
    }
}
