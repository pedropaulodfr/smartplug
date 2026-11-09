package com.smartplug.app.domain.engine

import com.smartplug.app.domain.gateway.AutomationStore
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.ExecutionLog
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.LocationStateSource
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.gateway.PlugMemory
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.AutomationRuntime
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionNode
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.PlugState
import com.smartplug.app.domain.model.TraceLine
import com.smartplug.app.domain.model.TriState
import com.smartplug.app.domain.model.isEvent
import com.smartplug.app.domain.model.leaves
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EngineReport(
    val evaluated: Int = 0,
    val executed: Int = 0,
    val failures: Int = 0,
    /** Quando vale a pena acordar de novo (fim de permanência, virada de horário). */
    val nextWakeAtMillis: Long? = null,
    /** Houve falha de comunicação: o chamador deve tentar de novo com backoff. */
    val retryNeeded: Boolean = false,
)

data class DryRunResult(val state: TriState, val trace: List<TraceLine>)

/**
 * Motor de automações. Independe de UI e de Android: recebe um gatilho, avalia as
 * automações habilitadas e executa ações pela [PlugGateway].
 *
 * Regras principais:
 *  - dispara apenas na transição falso -> verdadeiro (ou por recorrência explícita);
 *  - o primeiro resultado de uma automação é só uma linha de base (não dispara);
 *  - dado indisponível => UNKNOWN => nunca executa nem altera o estado anterior;
 *  - falha de comunicação não consome a transição (nova tentativa na próxima rodada);
 *  - uma rodada por vez (Mutex) e, na mesma rodada, cada automação executa no máximo uma vez.
 */
@Singleton
class AutomationEngine @Inject constructor(
    private val automations: AutomationStore,
    private val executions: ExecutionLog,
    private val locations: LocationStateSource,
    private val plug: PlugGateway,
    private val memory: PlugMemory,
    private val clock: Clock,
    private val evaluators: Set<@JvmSuppressWildcards ConditionEvaluator>,
    private val executors: Set<@JvmSuppressWildcards ActionExecutor>,
) {
    private val mutex = Mutex()

    private class Run(
        val trigger: EngineTrigger,
        val nowMs: Long,
        val now: ZonedDateTime,
        val locations: Map<Long, LocationInfo>,
        var plug: PlugState?,
        var expectedOn: Boolean?,
    ) {
        val fired = mutableSetOf<Long>()
        var evaluated = 0
        var executed = 0
        var failures = 0
        var retry = false
        var wake: Long? = null
        var stateChangedTo: Boolean? = null
        val runtimes = mutableMapOf<Long, AutomationRuntime>()

        fun hint(atMillis: Long) {
            wake = wake?.let { minOf(it, atMillis) } ?: atMillis
        }
    }

    suspend fun run(trigger: EngineTrigger): EngineReport = mutex.withLock { runLocked(trigger) }

    private suspend fun runLocked(trigger: EngineTrigger): EngineReport {
        val list = automations.enabledAutomations()
        if (list.isEmpty()) return EngineReport()

        val nowMs = clock.nowMillis()
        val now = Instant.ofEpochMilli(nowMs).atZone(clock.zone())
        val infos = locations.snapshots().associateBy { it.location.id }

        var plugState: PlugState? = null
        if (list.any { it.usesPlugData() }) {
            plugState = (plug.readState() as? GatewayResult.Success)?.value
        }

        val events = trigger.events.toMutableList()
        if (plugState != null) {
            val last = memory.lastObservedOn()
            if (last != null && last != plugState.on) events += EngineEvent.PlugChanged(plugState.on, byAutomation = false)
            memory.setObservedOn(plugState.on)
        }

        val run = Run(trigger, nowMs, now, infos, plugState, memory.expectedOn())

        var passEvents: List<EngineEvent> = events
        for (pass in 0 until MAX_PASSES) {
            run.stateChangedTo = null
            for (automation in list) {
                processAutomation(automation, run, passEvents)
            }
            val changed = run.stateChangedTo ?: break
            passEvents = listOf(EngineEvent.PlugChanged(changed, byAutomation = true))
        }

        TimeBoundaries.next(list, now)?.let { run.hint(it) }
        return EngineReport(
            evaluated = run.evaluated,
            executed = run.executed,
            failures = run.failures,
            nextWakeAtMillis = run.wake,
            retryNeeded = run.retry,
        )
    }

    private fun context(run: Run, events: List<EngineEvent>) = EvaluationContext(
        now = run.now,
        plug = run.plug,
        expectedOn = run.expectedOn,
        locations = run.locations,
        events = events,
    )

    private suspend fun processAutomation(automation: Automation, run: Run, events: List<EngineEvent>) {
        run.evaluated++
        val runtime = run.runtimes.getOrPut(automation.id) { automations.runtime(automation.id) }
        val holds = runtime.holds.toMutableMap()
        val trace = mutableListOf<TraceLine>()
        val state = evaluate(automation.root, context(run, events), holds, run, trace, persistHolds = true)
        val hasEvent = automation.leaves().any { it.condition.isEvent() }
        val base = runtime.copy(holds = holds)

        when (state) {
            TriState.UNKNOWN -> {
                // Dado indisponível: não executa e não altera o último resultado conhecido.
                saveRuntime(base, run)
            }
            TriState.FALSE -> {
                if (runtime.lastResult == true && !hasEvent) {
                    record(
                        automation, run, ExecutionOutcome.CONDITIONS_NOT_MET, trace,
                        message = "As condições deixaram de ser atendidas.",
                    )
                }
                saveRuntime(base.copy(lastResult = false), run)
            }
            TriState.TRUE -> {
                val baselineOnly = runtime.lastResult == null && !hasEvent
                val transition = runtime.lastResult != true
                val repeatDue = !transition && automation.repeatEverySeconds != null &&
                    runtime.lastExecutedAtMillis != null &&
                    run.nowMs - runtime.lastExecutedAtMillis >= automation.repeatEverySeconds * 1000L
                when {
                    baselineOnly -> saveRuntime(base.copy(lastResult = true), run)
                    transition || repeatDue -> {
                        fire(automation, run, trace, base, isRepeat = repeatDue)
                        // Evento consumido: o próximo evento é uma nova transição, mesmo sem
                        // uma rodada intermediária (rajada de eventos de geofence).
                        if (hasEvent) {
                            run.runtimes[automation.id]?.let { saveRuntime(it.copy(lastResult = false), run) }
                        }
                    }
                    else -> saveRuntime(base, run)
                }
            }
        }
    }

    private suspend fun saveRuntime(rt: AutomationRuntime, run: Run) {
        run.runtimes[rt.automationId] = rt
        automations.saveRuntime(rt)
    }

    private suspend fun fire(
        automation: Automation,
        run: Run,
        trace: List<TraceLine>,
        rt: AutomationRuntime,
        isRepeat: Boolean,
    ) {
        val actionText = ConditionText.describe(automation.action)

        // 1) proteções contra repetição e ciclos
        if (automation.id in run.fired) {
            record(automation, run, ExecutionOutcome.IGNORED_CYCLE, trace, actionText,
                message = "Já executou nesta rodada: possível ciclo entre automações.")
            saveRuntime(rt.copy(lastResult = true), run)
            return
        }
        val last = rt.lastExecutedAtMillis
        if (!isRepeat && last != null && run.nowMs - last < automation.minIntervalSeconds * 1000L) {
            record(automation, run, ExecutionOutcome.IGNORED_DUPLICATE, trace, actionText,
                message = "Intervalo mínimo de ${ConditionText.duration(automation.minIntervalSeconds)} entre execuções.")
            saveRuntime(rt.copy(lastResult = true), run)
            return
        }
        if (executions.successCountSince(automation.id, run.nowMs - HOUR_MS) >= MAX_PER_HOUR) {
            record(automation, run, ExecutionOutcome.IGNORED_CYCLE, trace, actionText,
                message = "Mais de $MAX_PER_HOUR execuções na última hora: bloqueada para evitar ciclo.")
            saveRuntime(rt.copy(lastResult = true), run)
            return
        }

        // 2) estado REAL do plug antes de agir
        var before = run.plug
        if (before == null) {
            when (val read = plug.readState()) {
                is GatewayResult.Success -> before = read.value
                is GatewayResult.Failure -> {
                    run.failures++
                    run.retry = true
                    record(automation, run, ExecutionOutcome.COMM_FAILURE, trace, actionText,
                        comm = "Leitura: ${read.kind}", message = read.message)
                    saveRuntime(rt.copy(lastResult = false), run)
                    return
                }
            }
        }
        if (!before.online) {
            run.failures++
            run.retry = true
            record(automation, run, ExecutionOutcome.COMM_FAILURE, trace, actionText,
                stateBefore = before.on, comm = "Plug offline na nuvem",
                message = "O plug está offline; o comando não foi enviado.")
            saveRuntime(rt.copy(lastResult = false), run)
            return
        }

        // 3) ação
        val executor = executors.firstOrNull { it.supports(automation.action) }
        if (executor == null) {
            record(automation, run, ExecutionOutcome.COMM_FAILURE, trace, actionText,
                message = "Nenhum executor registrado para esta ação.")
            saveRuntime(rt.copy(lastResult = true), run)
            return
        }
        when (val result = executor.execute(automation.action, before, plug)) {
            is ActionResult.AlreadyInState -> {
                if (!isRepeat) {
                    record(automation, run, ExecutionOutcome.IGNORED_DUPLICATE, trace, actionText,
                        stateBefore = before.on, stateAfter = before.on,
                        message = if (result.on) "O plug já estava ligado." else "O plug já estava desligado.")
                }
                saveRuntime(rt.copy(lastResult = true), run)
            }
            is ActionResult.Done -> {
                memory.setExpectedOn(result.targetOn, run.nowMs)
                memory.setObservedOn(result.after.on)
                run.expectedOn = result.targetOn
                run.plug = result.after
                run.fired += automation.id
                run.executed++
                if (before.on != result.after.on) run.stateChangedTo = result.after.on
                record(automation, run, ExecutionOutcome.SUCCESS, trace, actionText,
                    stateBefore = before.on, stateAfter = result.after.on,
                    comm = "Tuya OK (${result.after.latencyMs} ms)")
                saveRuntime(rt.copy(lastResult = true, lastExecutedAtMillis = run.nowMs), run)
            }
            is ActionResult.Failed -> {
                run.failures++
                run.retry = true
                record(automation, run, ExecutionOutcome.COMM_FAILURE, trace, actionText,
                    stateBefore = before.on, comm = "Tuya: ${result.kind}", message = result.message)
                // não consome a transição: a próxima rodada tenta de novo
                saveRuntime(rt.copy(lastResult = false), run)
            }
        }
    }

    private suspend fun record(
        automation: Automation,
        run: Run,
        outcome: ExecutionOutcome,
        trace: List<TraceLine>,
        action: String? = null,
        stateBefore: Boolean? = null,
        stateAfter: Boolean? = null,
        comm: String? = null,
        message: String? = null,
        isTest: Boolean = false,
    ) {
        executions.append(
            ExecutionRecord(
                automationId = automation.id,
                automationName = automation.name,
                timestampMillis = run.nowMs,
                outcome = outcome,
                trigger = run.trigger.source.name,
                trace = trace,
                action = action,
                stateBefore = stateBefore,
                stateAfter = stateAfter,
                commResult = comm,
                message = message,
                isTest = isTest,
            ),
        )
    }

    // ---------------------------------------------------------------- avaliação

    private fun evaluate(
        node: ConditionNode,
        ctx: EvaluationContext,
        holds: MutableMap<Long, Long>,
        run: Run?,
        trace: MutableList<TraceLine>,
        persistHolds: Boolean,
    ): TriState = when (node) {
        is ConditionLeaf -> evaluateLeaf(node, ctx, holds, run, trace)
        is ConditionGroup -> {
            if (node.children.isEmpty()) {
                TriState.UNKNOWN
            } else {
                val results = node.children.map { evaluate(it, ctx, holds, run, trace, persistHolds) }
                when (node.operator) {
                    LogicOperator.AND -> results.reduce { a, b -> a.and(b) }
                    LogicOperator.OR -> results.reduce { a, b -> a.or(b) }
                }
            }
        }
    }

    private fun evaluateLeaf(
        leaf: ConditionLeaf,
        ctx: EvaluationContext,
        holds: MutableMap<Long, Long>,
        run: Run?,
        trace: MutableList<TraceLine>,
    ): TriState {
        val evaluator = evaluators.firstOrNull { it.supports(leaf.condition) }
        if (evaluator == null) {
            trace += TraceLine("Condição sem avaliador registrado", TriState.UNKNOWN.name)
            return TriState.UNKNOWN
        }
        val raw = evaluator.evaluate(leaf.condition, ctx)
        var state = raw.state
        var text = raw.text

        if (leaf.holdSeconds > 0 && !leaf.condition.isEvent()) {
            val nowMs = ctx.now.toInstant().toEpochMilli()
            when (raw.state) {
                TriState.TRUE -> {
                    val since = holds.getOrPut(leaf.id) { nowMs }
                    val holdMs = leaf.holdSeconds * 1000L
                    if (nowMs - since >= holdMs) {
                        text += " — mantida há ${ConditionText.duration(((nowMs - since) / 1000).toInt())}"
                    } else {
                        state = TriState.FALSE
                        text += " — aguardando ${ConditionText.duration(leaf.holdSeconds)} (decorrido ${(nowMs - since) / 1000} s)"
                        run?.hint(since + holdMs)
                    }
                }
                TriState.FALSE -> holds.remove(leaf.id)
                TriState.UNKNOWN -> state = TriState.UNKNOWN // mantém a contagem; não vale como verdadeira
            }
        }
        trace += TraceLine(text, state.name)
        return state
    }

    // ------------------------------------------------------------ uso manual/teste

    /** Avalia sem executar nem gravar nada (botão "Testar condições"). */
    suspend fun dryRun(automationId: Long): DryRunResult = mutex.withLock {
        val automation = automations.automation(automationId)
            ?: return@withLock DryRunResult(TriState.UNKNOWN, listOf(TraceLine("Automação não encontrada", "UNKNOWN")))
        val nowMs = clock.nowMillis()
        val plugState = if (automation.usesPlugData()) {
            (plug.readState() as? GatewayResult.Success)?.value
        } else null
        val ctx = EvaluationContext(
            now = Instant.ofEpochMilli(nowMs).atZone(clock.zone()),
            plug = plugState,
            expectedOn = memory.expectedOn(),
            locations = locations.snapshots().associateBy { it.location.id },
            events = emptyList(),
        )
        val trace = mutableListOf<TraceLine>()
        val holds = automations.runtime(automationId).holds.toMutableMap()
        val state = evaluate(automation.root, ctx, holds, null, trace, persistHolds = false)
        DryRunResult(state, trace)
    }

    /** Executa a ação agora, ignorando condições (botão "Executar agora"). Registra no histórico. */
    suspend fun executeNow(automationId: Long): ExecutionRecord? = mutex.withLock {
        val automation = automations.automation(automationId) ?: return@withLock null
        val nowMs = clock.nowMillis()
        val actionText = ConditionText.describe(automation.action)
        val executor = executors.firstOrNull { it.supports(automation.action) }

        fun build(
            outcome: ExecutionOutcome,
            before: Boolean? = null,
            after: Boolean? = null,
            comm: String? = null,
            message: String? = null,
        ) = ExecutionRecord(
            automationId = automation.id,
            automationName = automation.name,
            timestampMillis = nowMs,
            outcome = outcome,
            trigger = TriggerSource.MANUAL.name,
            trace = emptyList(),
            action = actionText,
            stateBefore = before,
            stateAfter = after,
            commResult = comm,
            message = message,
            isTest = true,
        )

        val record = when (val read = plug.readState()) {
            is GatewayResult.Failure -> build(ExecutionOutcome.COMM_FAILURE, comm = "Leitura: ${read.kind}", message = read.message)
            is GatewayResult.Success -> {
                val before = read.value
                if (executor == null) {
                    build(ExecutionOutcome.COMM_FAILURE, before.on, message = "Nenhum executor registrado para esta ação.")
                } else when (val result = executor.execute(automation.action, before, plug)) {
                    is ActionResult.AlreadyInState -> build(
                        ExecutionOutcome.IGNORED_DUPLICATE, before.on, before.on,
                        message = if (result.on) "O plug já estava ligado." else "O plug já estava desligado.",
                    )
                    is ActionResult.Done -> {
                        memory.setExpectedOn(result.targetOn, nowMs)
                        memory.setObservedOn(result.after.on)
                        build(ExecutionOutcome.SUCCESS, before.on, result.after.on, "Tuya OK (${result.after.latencyMs} ms)")
                    }
                    is ActionResult.Failed -> build(
                        ExecutionOutcome.COMM_FAILURE, before.on,
                        comm = "Tuya: ${result.kind}", message = result.message,
                    )
                }
            }
        }
        executions.append(record)
        record
    }

    companion object {
        const val MAX_PASSES = 4
        const val MAX_PER_HOUR = 6
        private const val HOUR_MS = 3_600_000L
    }
}
