package com.smartplug.app.domain

import com.smartplug.app.domain.engine.TimeBoundaries
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.Consuming
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.DistanceFromLocation
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.OutsideLocation
import com.smartplug.app.domain.model.PlugOff
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.SpecificDate
import com.smartplug.app.domain.model.StateChanged
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBefore
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.TimeEquals
import com.smartplug.app.domain.model.ToggleAction
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.domain.model.UnexpectedState
import com.smartplug.app.domain.model.Weekdays
import com.smartplug.app.domain.model.Weekend
import com.smartplug.app.engine.automation
import com.smartplug.app.engine.at
import com.smartplug.app.engine.leaf
import com.smartplug.app.features.automations.ConditionKind
import com.smartplug.app.features.automations.ConditionTree
import com.smartplug.app.features.automations.Templates
import com.smartplug.app.features.automations.default
import com.smartplug.app.features.automations.kind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializationTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    private val allConditions: List<Condition> = listOf(
        TimeAfter(960), TimeBefore(1380), TimeBetween(0, 360), TimeEquals(1080, 20),
        DaysOfWeek(setOf(1, 3, 5)), Weekdays, Weekend, SpecificDate(20_000),
        InsideLocation(1), OutsideLocation(2), EnteringLocation(3), ExitingLocation(4),
        DistanceFromLocation(5, Comparison.LESS, 250.5),
        MetricCompare(Metric.ENERGY, Comparison.GREATER, 12.5), Consuming(3.0), NotConsuming(2.0),
        PlugOn, PlugOff, UnexpectedState, StateChanged(true), StateChanged(null),
    )

    @Test fun `toda condicao sobrevive ao ciclo JSON (persistencia no Room)`() {
        allConditions.forEach { c ->
            val text = json.encodeToString(Condition.serializer(), c)
            assertEquals("falhou em $text", c, json.decodeFromString(Condition.serializer(), text))
        }
    }

    @Test fun `toda acao sobrevive ao ciclo JSON`() {
        listOf<ActionSpec>(TurnOnAction, TurnOffAction, ToggleAction).forEach { a ->
            assertEquals(a, json.decodeFromString(ActionSpec.serializer(), json.encodeToString(ActionSpec.serializer(), a)))
        }
    }

    @Test fun `o catalogo cobre todos os tipos e e consistente`() {
        // cada condição mapeia para um tipo e o valor padrão do tipo volta para o mesmo tipo
        ConditionKind.entries.forEach { kind ->
            assertEquals(kind, kind.default(emptyList()).kind())
        }
        assertEquals(ConditionKind.entries.size, allConditions.map { it.kind() }.toSet().size)
    }
}

class ConditionTreeTest {
    private fun root() = ConditionTree.emptyRoot()

    @Test fun `adiciona, edita e remove condicoes e grupos`() {
        var tree = ConditionTree.addChild(root(), ConditionTree.ROOT_ID, ConditionLeaf(-2, TimeAfter(960)))
        tree = ConditionTree.addChild(tree, ConditionTree.ROOT_ID, ConditionGroup(-3, LogicOperator.OR, emptyList()))
        tree = ConditionTree.addChild(tree, -3, ConditionLeaf(-4, InsideLocation(1)))
        tree = ConditionTree.addChild(tree, -3, ConditionLeaf(-5, Weekdays))
        assertEquals(3, (tree.children.last() as ConditionGroup).children.size + 1)

        tree = ConditionTree.updateLeaf(tree, -4, OutsideLocation(1), 120)
        val updated = ConditionTree.findLeaf(tree, -4)!!
        assertEquals(OutsideLocation(1), updated.condition)
        assertEquals(120, updated.holdSeconds)

        tree = ConditionTree.setOperator(tree, -3, LogicOperator.AND)
        assertEquals(LogicOperator.AND, (tree.children.last() as ConditionGroup).operator)

        tree = ConditionTree.remove(tree, -3)
        assertEquals(1, tree.children.size)
    }

    @Test fun `validacao encontra erros comuns`() {
        assertTrue(ConditionTree.validate(root(), emptySet()).isNotEmpty())
        val tree = root().copy(children = listOf(
            ConditionLeaf(-2, InsideLocation(99)),
            ConditionLeaf(-3, TimeBetween(60, 60)),
            ConditionLeaf(-4, DaysOfWeek(emptySet())),
            ConditionLeaf(-5, EnteringLocation(1), holdSeconds = 60),
            ConditionGroup(-6, LogicOperator.OR, emptyList()),
        ))
        val errors = ConditionTree.validate(tree, setOf(1))
        assertTrue(errors.size >= 5)
        assertTrue(ConditionTree.validate(root().copy(children = listOf(ConditionLeaf(-2, TimeAfter(10)))), emptySet()).isEmpty())
    }

    @Test fun `todos os modelos prontos geram automacoes validas`() {
        Templates.all.forEach { t ->
            val a = t.build(7)
            assertTrue(t.id, ConditionTree.validate(a.root, setOf(7)).isEmpty())
            assertTrue(a.name.isNotBlank())
        }
        assertEquals(6, Templates.all.count { it.id in setOf("arrive_home", "leave_home", "night_off", "on_at_time", "off_at_time", "idle_off") })
    }
}

class TextAndBoundariesTest {
    @Test fun `descricoes em portugues`() {
        val names: (Long) -> String = { "Minha casa" }
        assertEquals("Horário maior que 16:00", ConditionText.describe(TimeAfter(960), names))
        assertEquals("Horário entre 00:00 e 06:00", ConditionText.describe(TimeBetween(0, 360), names))
        assertEquals("Dentro de Minha casa", ConditionText.describe(InsideLocation(1), names))
        assertEquals("Potência menor que 2 W", ConditionText.describe(MetricCompare(Metric.POWER, Comparison.LESS, 2.0), names))
        assertEquals(
            "Sem consumo significativo (< 2 W) por pelo menos 10 min",
            ConditionText.describe(leaf(NotConsuming(2.0), hold = 600), names),
        )
        assertEquals("Ligar Smart Plug", ConditionText.describe(TurnOnAction))
    }

    @Test fun `proximo despertar considera o proximo limite de horario`() {
        val now = at("2026-11-09", "10:00")
        val a = automation(1, leaf(TimeAfter(16 * 60)))
        val next = TimeBoundaries.next(listOf(a), now)!!
        assertEquals(at("2026-11-09", "16:00").toInstant().toEpochMilli(), next)

        val afterwards = TimeBoundaries.next(listOf(a), at("2026-11-09", "17:00"))!!
        assertEquals(at("2026-11-10", "16:00").toInstant().toEpochMilli(), afterwards)
        assertEquals(null, TimeBoundaries.next(listOf(automation(2, leaf(PlugOn))), now))
        assertNotNull(TimeBoundaries.next(listOf(automation(3, leaf(Weekdays))), now))
    }
}
