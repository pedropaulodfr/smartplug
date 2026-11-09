package com.smartplug.app.features.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.data.repo.ExecutionRepository
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.engine.AutomationEngine
import com.smartplug.app.domain.engine.DryRunResult
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionNode
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.locationId
import com.smartplug.app.location.PermissionChecker
import com.smartplug.app.workers.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resumo em uma linha: "Horário maior que 16:00 E (Dentro de Casa OU ...)". */
fun ConditionNode.oneLine(locationName: (Long) -> String): String = when (this) {
    is ConditionLeaf -> ConditionText.describe(this, locationName)
    is ConditionGroup ->
        if (children.size == 1) children[0].oneLine(locationName)
        else children.joinToString(" ${operator.label} ", "(", ")") { it.oneLine(locationName) }
}

fun ConditionGroup.rootLine(locationName: (Long) -> String): String =
    if (children.isEmpty()) "Sem condições"
    else children.joinToString(" ${operator.label} ") { it.oneLine(locationName) }

data class AutomationItem(
    val automation: Automation,
    val conditionsText: String,
    val lastExecutionMillis: Long?,
    val warning: String?,
)

sealed interface TestDialog {
    data class Evaluating(val name: String) : TestDialog
    data class Evaluated(val name: String, val result: DryRunResult) : TestDialog
    data class Executed(val record: ExecutionRecord?) : TestDialog
}

data class AutomationsUiState(
    val items: List<AutomationItem> = emptyList(),
    val loaded: Boolean = false,
    val test: TestDialog? = null,
)

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val repository: AutomationRepository,
    private val executions: ExecutionRepository,
    private val locations: LocationRepository,
    private val engine: AutomationEngine,
    private val permissions: PermissionChecker,
    private val scheduler: WorkScheduler,
) : ViewModel() {

    private val dialog = MutableStateFlow<TestDialog?>(null)

    val state: StateFlow<AutomationsUiState> = combine(
        repository.observeAll(), locations.observeAll(), executions.observeLastSuccesses(), dialog,
    ) { automations, places, lastRuns, test ->
        val names = places.associate { it.id to it.name }
        val name: (Long) -> String = { names[it] ?: "local removido" }
        AutomationsUiState(
            items = automations.map { a ->
                AutomationItem(
                    automation = a,
                    conditionsText = a.root.rootLine(name),
                    lastExecutionMillis = lastRuns[a.id],
                    warning = warningFor(a, places),
                )
            },
            loaded = true,
            test = test,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationsUiState())

    private fun warningFor(a: Automation, places: List<SavedLocation>): String? {
        val ids = a.leaves().mapNotNull { it.condition.locationId() }
        if (ids.isEmpty()) return null
        if (ids.any { id -> places.none { it.id == id } }) return "Usa um local que foi removido."
        if (!permissions.hasFineLocation()) return "Requer permissão de localização."
        if (!permissions.hasBackgroundLocation()) return "Requer localização em segundo plano para funcionar com o app fechado."
        return null
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            if (enabled) scheduler.runSoon(com.smartplug.app.domain.engine.TriggerSource.APP)
        }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun dryRun(item: AutomationItem) {
        viewModelScope.launch {
            dialog.value = TestDialog.Evaluating(item.automation.name)
            dialog.value = TestDialog.Evaluated(item.automation.name, engine.dryRun(item.automation.id))
        }
    }

    fun executeNow(id: Long) {
        viewModelScope.launch {
            dialog.value = TestDialog.Evaluating("Executando…")
            dialog.value = TestDialog.Executed(engine.executeNow(id))
        }
    }

    fun dismissTest() {
        dialog.update { null }
    }
}

