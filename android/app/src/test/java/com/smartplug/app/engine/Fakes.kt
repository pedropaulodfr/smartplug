package com.smartplug.app.engine

import com.smartplug.app.domain.engine.ActionExecutor
import com.smartplug.app.domain.engine.AutomationEngine
import com.smartplug.app.domain.engine.ConditionEvaluator
import com.smartplug.app.domain.engine.PlugPowerExecutor
import com.smartplug.app.domain.engine.defaultEvaluators
import com.smartplug.app.domain.gateway.AutomationStore
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.ExecutionLog
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.LocationStateSource
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.gateway.PlugMemory
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.AutomationRuntime
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.PlugState
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.TurnOnAction
import java.time.ZoneId
import java.time.ZonedDateTime

class FakeClock(var zoneId: ZoneId = ZoneId.of("America/Sao_Paulo"), start: ZonedDateTime) : Clock {
    var millis: Long = start.toInstant().toEpochMilli()
    override fun nowMillis() = millis
    override fun zone(): ZoneId = zoneId
    fun advanceMinutes(minutes: Long) { millis += minutes * 60_000 }
    fun advanceSeconds(seconds: Long) { millis += seconds * 1_000 }
}

fun at(day: String, time: String, zone: String = "America/Sao_Paulo"): ZonedDateTime =
    java.time.LocalDateTime.parse("${day}T$time:00").atZone(ZoneId.of(zone))

class FakeGateway : PlugGateway {
    var state = plugState(on = false)
    var readFailure: GatewayResult.Failure? = null
    var setFailure: GatewayResult.Failure? = null
    var failSetTimes = 0
    var confirm = true
    val commands = mutableListOf<Boolean>()
    var onSet: (suspend () -> Unit)? = null

    override suspend fun readState(): GatewayResult<PlugState> =
        readFailure ?: GatewayResult.Success(state)

    override suspend fun setPower(on: Boolean): GatewayResult<PlugState> {
        onSet?.invoke()
        if (failSetTimes > 0) {
            failSetTimes--
            return GatewayResult.Failure(FailureKind.NO_NETWORK, "sem internet")
        }
        setFailure?.let { return it }
        commands += on
        if (confirm) state = state.copy(on = on)
        return GatewayResult.Success(state)
    }
}

fun plugState(
    on: Boolean = false,
    power: Double? = 0.0,
    voltage: Double? = 220.0,
    current: Double? = 0.0,
    energy: Double? = 1.0,
    online: Boolean = true,
) = PlugState(on, power, voltage, current, energy, online, latencyMs = 100, readAtMillis = 0)

class FakeStore : AutomationStore, ExecutionLog, LocationStateSource, PlugMemory {
    val automations = mutableListOf<Automation>()
    val runtimes = mutableMapOf<Long, AutomationRuntime>()
    val log = mutableListOf<ExecutionRecord>()
    val locations = mutableListOf<LocationInfo>()
    private var observed: Boolean? = null
    private var expected: Boolean? = null

    override suspend fun enabledAutomations() = automations.filter { it.enabled }
    override suspend fun automation(id: Long) = automations.firstOrNull { it.id == id }
    override suspend fun runtime(automationId: Long) = runtimes[automationId] ?: AutomationRuntime(automationId)
    override suspend fun saveRuntime(runtime: AutomationRuntime) { runtimes[runtime.automationId] = runtime }

    override suspend fun append(record: ExecutionRecord) { log += record }
    override suspend fun successCountSince(automationId: Long, sinceMillis: Long) =
        log.count { it.automationId == automationId && it.outcome.name == "SUCCESS" && it.timestampMillis >= sinceMillis }

    override suspend fun snapshots() = locations.toList()

    override suspend fun lastObservedOn() = observed
    override suspend fun setObservedOn(on: Boolean) { observed = on }
    override suspend fun expectedOn() = expected
    override suspend fun setExpectedOn(on: Boolean, atMillis: Long) { expected = on }
}

fun home(status: LocationStatus, distance: Double? = null, id: Long = 1) =
    LocationInfo(SavedLocation(id, "Minha casa", -23.0, -46.0, 150f), status, distance)

fun leaf(condition: Condition, hold: Int = 0, id: Long = 0) = ConditionLeaf(id = id, condition = condition, holdSeconds = hold)

fun automation(
    id: Long = 1,
    vararg leaves: ConditionLeaf,
    operator: LogicOperator = LogicOperator.AND,
    action: ActionSpec = TurnOnAction,
    minInterval: Int = 60,
    repeat: Int? = null,
    enabled: Boolean = true,
): Automation = Automation(
    id = id,
    name = "Auto $id",
    root = ConditionGroup(
        id = 0,
        operator = operator,
        children = leaves.mapIndexed { i, l -> l.copy(id = id * 100 + i + 1) },
    ),
    action = action,
    minIntervalSeconds = minInterval,
    repeatEverySeconds = repeat,
    enabled = enabled,
)

class Harness(
    start: ZonedDateTime = at("2026-11-09", "10:00"), // segunda-feira
    evaluators: Set<ConditionEvaluator> = defaultEvaluators(),
    executors: Set<ActionExecutor> = setOf(PlugPowerExecutor(maxAttempts = 3, retryDelayMs = 0)),
) {
    val clock = FakeClock(start = start)
    val gateway = FakeGateway()
    val store = FakeStore()
    val engine = AutomationEngine(store, store, store, gateway, store, clock, evaluators, executors)
}
