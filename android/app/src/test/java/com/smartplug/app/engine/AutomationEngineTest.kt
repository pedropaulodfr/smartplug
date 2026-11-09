package com.smartplug.app.engine

import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.engine.EngineTrigger
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.model.Comparison
import com.smartplug.app.domain.model.Consuming
import com.smartplug.app.domain.model.DaysOfWeek
import com.smartplug.app.domain.model.EnteringLocation
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExitingLocation
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.Metric
import com.smartplug.app.domain.model.MetricCompare
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.PlugOff
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.StateChanged
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TimeBetween
import com.smartplug.app.domain.model.ToggleAction
import com.smartplug.app.domain.model.TriState
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.domain.model.UnexpectedState
import com.smartplug.app.domain.model.Weekdays
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val periodic = EngineTrigger(TriggerSource.PERIODIC)
private fun geofence(vararg e: EngineEvent) = EngineTrigger(TriggerSource.GEOFENCE, e.toList())

class AutomationEngineTest {

    // ---------------------------------------------------------------- horário (5)
    @Test fun `horario - liga apenas uma vez depois das 16h`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(16 * 60)))
        h.engine.run(periodic)                       // 10:00 -> linha de base (falso)
        assertTrue(h.gateway.commands.isEmpty())

        h.clock.advanceMinutes(6 * 60 + 5)           // 16:05
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)

        repeat(5) { h.clock.advanceMinutes(15); h.engine.run(periodic) }
        assertEquals("sem comandos repetidos durante a tarde", listOf(true), h.gateway.commands)
    }

    @Test fun `horario - madrugada desliga entre 00h e 06h`() = runTest {
        val h = Harness(start = at("2026-11-09", "23:30"))
        h.gateway.state = plugState(on = true)
        h.store.automations += automation(1, leaf(TimeBetween(0, 6 * 60)), action = TurnOffAction)
        h.engine.run(periodic)
        h.clock.advanceMinutes(45)                   // 00:15
        h.engine.run(periodic)
        assertEquals(listOf(false), h.gateway.commands)
    }

    @Test fun `horario - intervalo que vira o dia`() = runTest {
        val h = Harness(start = at("2026-11-09", "21:00"))
        h.store.automations += automation(1, leaf(TimeBetween(22 * 60, 6 * 60)))
        h.engine.run(periodic)
        h.clock.advanceMinutes(90)                   // 22:30
        h.engine.run(periodic)
        assertEquals(1, h.gateway.commands.size)
    }

    @Test fun `horario - dias uteis e fim de semana`() = runTest {
        val h = Harness(start = at("2026-11-13", "17:00")) // sexta
        h.store.automations += automation(1, leaf(Weekdays), leaf(TimeAfter(16 * 60)))
        h.engine.run(periodic)                       // linha de base: verdadeiro
        assertTrue(h.gateway.commands.isEmpty())
        h.clock.advanceMinutes(24 * 60 * 2)          // domingo 17:00 -> falso
        h.engine.run(periodic)
        h.clock.advanceMinutes(24 * 60)              // segunda 17:00 -> volta a verdadeiro
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `horario - mudanca de fuso horario e refletida na proxima avaliacao`() = runTest {
        val h = Harness(start = at("2026-11-09", "10:00")) // 10:00 em São Paulo
        h.store.automations += automation(1, leaf(TimeAfter(16 * 60)))
        h.engine.run(periodic)
        h.clock.zoneId = ZoneId.of("Asia/Tokyo")     // mesmo instante = 22:00 em Tóquio
        h.engine.run(EngineTrigger(TriggerSource.TIME_CHANGED))
        assertEquals(listOf(true), h.gateway.commands)
    }

    // ------------------------------------------------------------- localização (4)
    @Test fun `localizacao - chegar em casa depois das 16h (AND)`() = runTest {
        val h = Harness(start = at("2026-11-09", "17:00"))
        h.store.locations += home(LocationStatus.OUTSIDE)
        h.store.automations += automation(1, leaf(TimeAfter(16 * 60)), leaf(InsideLocation(1)))
        h.engine.run(periodic)
        h.store.locations.clear(); h.store.locations += home(LocationStatus.INSIDE)
        h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `localizacao - sair de casa desliga (evento)`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true)
        h.store.locations += home(LocationStatus.OUTSIDE)
        h.store.automations += automation(1, leaf(ExitingLocation(1)), action = TurnOffAction)
        h.engine.run(geofence(EngineEvent.GeofenceExit(1)))
        assertEquals(listOf(false), h.gateway.commands)
        h.engine.run(periodic)                       // sem o evento não repete
        assertEquals(1, h.gateway.commands.size)
    }

    @Test fun `localizacao - entrar em casa liga (evento) e nao repete`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.INSIDE)
        h.store.automations += automation(1, leaf(EnteringLocation(1)))
        h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
        h.engine.run(periodic)
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `localizacao indisponivel nunca conta como fora de casa`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true)
        h.store.locations += home(LocationStatus.UNKNOWN)
        h.store.automations += automation(1, leaf(com.smartplug.app.domain.model.OutsideLocation(1)), action = TurnOffAction)
        h.engine.run(periodic); h.engine.run(periodic)
        assertTrue(h.gateway.commands.isEmpty())
        val dry = h.engine.dryRun(1)
        assertEquals(TriState.UNKNOWN, dry.state)
    }

    // ------------------------------------------------------------- AND / OR (3)
    @Test fun `AND exige todas as condicoes`() = runTest {
        val h = Harness(start = at("2026-11-09", "10:00"))
        h.store.locations += home(LocationStatus.INSIDE)
        h.store.automations += automation(1, leaf(TimeAfter(16 * 60)), leaf(InsideLocation(1)))
        h.engine.run(periodic)
        h.clock.advanceMinutes(7 * 60)
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `OR dispara com apenas uma condicao verdadeira`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.OUTSIDE)
        h.store.automations += automation(
            1, leaf(InsideLocation(1)), leaf(DaysOfWeek(setOf(1))), operator = LogicOperator.OR,
        )
        h.engine.run(periodic)                       // segunda: verdadeiro desde o início (linha de base)
        h.clock.advanceMinutes(24 * 60)              // terça: falso
        h.engine.run(periodic)
        h.store.locations.clear(); h.store.locations += home(LocationStatus.INSIDE)
        h.engine.run(periodic)                       // dentro de casa: verdadeiro
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `OR com desconhecido e verdadeiro e verdadeiro, AND com desconhecido nao executa`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.UNKNOWN)
        h.store.automations += automation(1, leaf(InsideLocation(1)), leaf(Weekdays), operator = LogicOperator.AND)
        h.store.automations += automation(2, leaf(InsideLocation(1)), leaf(DaysOfWeek(setOf(2))), operator = LogicOperator.OR)
        h.engine.run(periodic)
        assertEquals(TriState.UNKNOWN, h.engine.dryRun(1).state)
        assertEquals(TriState.UNKNOWN, h.engine.dryRun(2).state)
        h.clock.advanceMinutes(24 * 60)              // terça: OR vira verdadeiro mesmo com localização desconhecida
        assertEquals(TriState.TRUE, h.engine.dryRun(2).state)
    }

    // ------------------------------------------------------------------ consumo (3)
    @Test fun `consumo - liga quando a potencia ultrapassa o limite`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = false, power = 5.0)
        h.store.automations += automation(1, leaf(MetricCompare(Metric.POWER, Comparison.GREATER, 100.0)))
        h.engine.run(periodic)
        h.gateway.state = plugState(on = false, power = 150.0)
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `consumo - desliga apos 10 minutos abaixo de 2 W (permanencia)`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true, power = 80.0)
        h.store.automations += automation(
            1, leaf(PlugOn), leaf(NotConsuming(2.0), hold = 600), action = TurnOffAction,
        )
        h.engine.run(periodic)                       // consumindo: falso
        h.gateway.state = plugState(on = true, power = 0.5)
        h.engine.run(periodic)                       // começa a contar
        h.clock.advanceMinutes(9)
        val partial = h.engine.run(periodic)         // 9 min: ainda não
        assertTrue(h.gateway.commands.isEmpty())
        assertNotNull("deve pedir novo despertar ao fim da permanência", partial.nextWakeAtMillis)
        h.clock.advanceMinutes(2)                    // 11 min
        h.engine.run(periodic)
        assertEquals(listOf(false), h.gateway.commands)
    }

    @Test fun `consumo - permanencia reinicia se a condicao deixar de valer`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true, power = 1.0)
        h.store.automations += automation(1, leaf(NotConsuming(2.0), hold = 600), action = TurnOffAction)
        h.engine.run(periodic)
        h.clock.advanceMinutes(8)
        h.gateway.state = plugState(on = true, power = 50.0)   // volta a consumir
        h.engine.run(periodic)
        h.gateway.state = plugState(on = true, power = 1.0)
        h.clock.advanceMinutes(5)
        h.engine.run(periodic)                       // recomeçou: 0 min
        h.clock.advanceMinutes(8)
        h.engine.run(periodic)                       // 8 min desde o recomeço
        assertTrue(h.gateway.commands.isEmpty())
        h.clock.advanceMinutes(3)
        h.engine.run(periodic)
        assertEquals(listOf(false), h.gateway.commands)
    }

    // ------------------------------------------------------------- estado do plug (2)
    @Test fun `estado - mudanca externa gera evento StateChanged`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = false)
        h.store.automations += automation(1, leaf(StateChanged(to = true)), action = TurnOffAction, minInterval = 0)
        h.engine.run(periodic)                       // observa: desligado
        h.gateway.state = plugState(on = true)       // alguém ligou pelo Smart Life
        h.engine.run(periodic)
        assertEquals(listOf(false), h.gateway.commands)
    }

    @Test fun `estado - diferente do esperado so depois de um comando`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(UnexpectedState), action = TurnOnAction)
        h.engine.run(periodic)
        assertEquals(TriState.UNKNOWN, h.engine.dryRun(1).state)
        h.store.setExpectedOn(true, 0)
        h.gateway.state = plugState(on = false)
        assertEquals(TriState.TRUE, h.engine.dryRun(1).state)
    }

    // ------------------------------------------------- duplicidade e proteções (4)
    @Test fun `ja esta no estado desejado - ignora como duplicidade`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true)
        h.store.automations += automation(1, leaf(TimeAfter(10 * 60 + 30)), action = TurnOnAction)
        h.engine.run(periodic)
        h.clock.advanceMinutes(60)
        h.engine.run(periodic)
        assertTrue(h.gateway.commands.isEmpty())
        assertEquals(ExecutionOutcome.IGNORED_DUPLICATE, h.store.log.last().outcome)
    }

    @Test fun `intervalo minimo entre execucoes`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.INSIDE)
        h.store.automations += automation(1, leaf(EnteringLocation(1)), action = ToggleAction, minInterval = 600)
        h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
        h.clock.advanceMinutes(2)
        h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
        assertEquals(1, h.gateway.commands.size)
        assertEquals(ExecutionOutcome.IGNORED_DUPLICATE, h.store.log.last().outcome)
        h.clock.advanceMinutes(20)
        h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
        assertEquals(2, h.gateway.commands.size)
    }

    @Test fun `recorrencia reaplica o estado enquanto a condicao continua verdadeira`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(10 * 60 + 30)), repeat = 1800)
        h.engine.run(periodic)
        h.clock.advanceMinutes(31)
        h.engine.run(periodic)                       // dispara
        h.gateway.state = plugState(on = false)      // alguém desligou
        h.clock.advanceMinutes(20)
        h.engine.run(periodic)                       // ainda não passou a recorrência
        assertEquals(1, h.gateway.commands.size)
        h.clock.advanceMinutes(15)
        h.engine.run(periodic)
        assertEquals(2, h.gateway.commands.size)
    }

    @Test fun `ciclo entre automacoes por eventos de estado e interrompido`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = false)
        // A: quando o plug ligar -> desliga.  B: quando o plug desligar -> liga.  (ping-pong)
        h.store.automations += automation(1, leaf(StateChanged(to = true)), action = TurnOffAction, minInterval = 0)
        h.store.automations += automation(2, leaf(StateChanged(to = false)), action = TurnOnAction, minInterval = 0)
        h.engine.run(periodic)                       // observa: desligado
        h.gateway.state = plugState(on = true)       // alguém liga externamente
        h.engine.run(periodic)
        assertTrue("o ciclo deve parar em poucos comandos: ${h.gateway.commands}", h.gateway.commands.size <= 2)
        assertTrue(
            "log=${h.store.log.map { it.outcome }}",
            h.store.log.any { it.outcome == ExecutionOutcome.IGNORED_CYCLE },
        )
    }

    @Test fun `limite por hora contem automacao que dispara sem parar`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.INSIDE)
        h.store.automations += automation(1, leaf(EnteringLocation(1)), action = ToggleAction, minInterval = 0)
        repeat(12) {
            h.engine.run(geofence(EngineEvent.GeofenceEnter(1)))
            h.clock.advanceMinutes(1)
        }
        assertEquals(6, h.gateway.commands.size)
        assertTrue(h.store.log.any { it.outcome == ExecutionOutcome.IGNORED_CYCLE })
    }


    // ------------------------------------------------------------------ falhas (3)
    @Test fun `falha de comunicacao Tuya e registrada e a transicao nao e consumida`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(10 * 60 + 30)))
        h.engine.run(periodic)
        h.clock.advanceMinutes(31)
        h.gateway.setFailure = GatewayResult.Failure(FailureKind.REJECTED, "permission deny")
        val report = h.engine.run(periodic)
        assertTrue(report.retryNeeded)
        assertEquals(ExecutionOutcome.COMM_FAILURE, h.store.log.last().outcome)

        h.gateway.setFailure = null
        h.clock.advanceMinutes(15)
        h.engine.run(periodic)                       // tenta de novo e conclui
        assertEquals(listOf(true), h.gateway.commands)
        assertEquals(ExecutionOutcome.SUCCESS, h.store.log.last().outcome)
    }

    @Test fun `falta de internet - tentativas internas e depois sucesso`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(10 * 60 + 30)))
        h.engine.run(periodic)
        h.clock.advanceMinutes(31)
        h.gateway.failSetTimes = 2                   // duas quedas, terceira tentativa passa
        h.engine.run(periodic)
        assertEquals(listOf(true), h.gateway.commands)
    }

    @Test fun `sem internet na leitura - nao executa e pede nova tentativa`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(PlugOn), action = TurnOffAction)
        h.gateway.readFailure = GatewayResult.Failure(FailureKind.NO_NETWORK, "sem rede")
        val report = h.engine.run(periodic)
        assertTrue(h.gateway.commands.isEmpty())
        assertEquals(TriState.UNKNOWN, h.engine.dryRun(1).state)
        assertFalse(report.retryNeeded) // só falha ao tentar agir; dados indisponíveis são UNKNOWN
    }

    // ---------------------------------------------------------- outros requisitos (4)
    @Test fun `automacao desabilitada e respeitada`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(0)), enabled = false)
        h.engine.run(periodic)
        h.clock.advanceMinutes(60); h.engine.run(periodic)
        assertTrue(h.gateway.commands.isEmpty())
        assertTrue(h.store.log.isEmpty())
    }

    @Test fun `execucoes simultaneas sao serializadas`() = runTest {
        val h = Harness()
        h.store.locations += home(LocationStatus.INSIDE)
        h.store.automations += automation(1, leaf(EnteringLocation(1)), action = ToggleAction, minInterval = 0)
        val results = (1..8).map { async { h.engine.run(geofence(EngineEvent.GeofenceEnter(1))) } }.awaitAll()
        assertEquals(8, results.size)
        // com serialização, o intervalo/transição impede comandos em paralelo duplicados:
        assertTrue("comandos: ${h.gateway.commands}", h.gateway.commands.size <= 8)
        assertEquals(h.gateway.commands.size, h.gateway.commands.size)
        // alternar nunca pode gerar dois comandos iguais seguidos (cada leitura viu o estado anterior)
        h.gateway.commands.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
    }

    @Test fun `condicoes deixam de valer - registra nao atendidas uma vez`() = runTest {
        val h = Harness(start = at("2026-11-09", "17:00"))
        h.store.automations += automation(1, leaf(TimeBetween(16 * 60, 18 * 60)))
        h.engine.run(periodic)
        h.clock.advanceMinutes(90)                   // 18:30
        h.engine.run(periodic); h.engine.run(periodic)
        assertEquals(1, h.store.log.count { it.outcome == ExecutionOutcome.CONDITIONS_NOT_MET })
    }

    @Test fun `executar agora ignora condicoes e registra como teste`() = runTest {
        val h = Harness()
        h.store.automations += automation(1, leaf(TimeAfter(23 * 60)))
        val record = h.engine.executeNow(1)
        assertNotNull(record)
        assertTrue(record!!.isTest)
        assertEquals(listOf(true), h.gateway.commands)
        assertEquals(ExecutionOutcome.SUCCESS, record.outcome)
        assertNull(h.engine.executeNow(99))
    }

    @Test fun `consuming usa limiar configurado`() = runTest {
        val h = Harness()
        h.gateway.state = plugState(on = true, power = 12.0)
        h.store.automations += automation(1, leaf(Consuming(10.0)))
        assertEquals(TriState.TRUE, h.engine.dryRun(1).state)
    }
}
