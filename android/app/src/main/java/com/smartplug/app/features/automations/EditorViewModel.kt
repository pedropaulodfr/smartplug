package com.smartplug.app.features.automations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.workers.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Qual diálogo de condição está aberto: nova (em [parentId]) ou edição de [leafId]. */
data class ConditionDialogTarget(
    val parentId: Long,
    val leafId: Long?,
    val initial: Condition?,
    val initialHoldSeconds: Int,
)

data class EditorUiState(
    val loaded: Boolean = false,
    val existingId: Long = 0,
    val step: Int = 0,
    val name: String = "",
    val description: String = "",
    val icon: String = "bolt",
    val enabled: Boolean = true,
    val root: ConditionGroup = ConditionTree.emptyRoot(),
    val action: ActionSpec = TurnOnAction,
    val minIntervalSeconds: Int = 60,
    val repeatEverySeconds: Int? = null,
    val locations: List<SavedLocation> = emptyList(),
    val errors: List<String> = emptyList(),
    val dialog: ConditionDialogTarget? = null,
    val saved: Boolean = false,
) {
    fun locationName(id: Long): String = locations.firstOrNull { it.id == id }?.name ?: "local removido"
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AutomationRepository,
    private val locationRepository: LocationRepository,
    private val scheduler: WorkScheduler,
) : ViewModel() {

    private val editingId: Long = savedState.get<Long>("id") ?: 0L
    private val templateId: String? = savedState.get<String>("template")

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state

    private var nextTempId = -100L
    private fun tempId() = nextTempId--

    init {
        viewModelScope.launch {
            val places = locationRepository.all()
            val existing = if (editingId != 0L) repository.automation(editingId) else null
            val template = templateId?.let { Templates.byId(it) }
            val base: Automation? = existing ?: template?.build(
                (places.firstOrNull { it.name.contains("casa", ignoreCase = true) } ?: places.firstOrNull())?.id ?: 0L,
            )
            _state.update {
                if (base != null) it.copy(
                    loaded = true,
                    existingId = existing?.id ?: 0,
                    name = base.name,
                    description = base.description,
                    icon = base.icon,
                    enabled = base.enabled,
                    root = base.root,
                    action = base.action,
                    minIntervalSeconds = base.minIntervalSeconds,
                    repeatEverySeconds = base.repeatEverySeconds,
                    locations = places,
                ) else it.copy(loaded = true, locations = places)
            }
        }
    }

    // ------------------------------------------------------------------ campos
    fun setName(v: String) = _state.update { it.copy(name = v, errors = emptyList()) }
    fun setDescription(v: String) = _state.update { it.copy(description = v) }
    fun setIcon(v: String) = _state.update { it.copy(icon = v) }
    fun setEnabled(v: Boolean) = _state.update { it.copy(enabled = v) }
    fun setAction(a: ActionSpec) = _state.update { it.copy(action = a) }
    fun setMinInterval(seconds: Int) = _state.update { it.copy(minIntervalSeconds = seconds) }
    fun setRepeat(seconds: Int?) = _state.update { it.copy(repeatEverySeconds = seconds) }

    // ------------------------------------------------------------------ condições
    fun openNewCondition(parentId: Long) =
        _state.update { it.copy(dialog = ConditionDialogTarget(parentId, null, null, 0)) }

    fun openEditCondition(leaf: ConditionLeaf, parentId: Long) =
        _state.update { it.copy(dialog = ConditionDialogTarget(parentId, leaf.id, leaf.condition, leaf.holdSeconds)) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun confirmCondition(condition: Condition, holdSeconds: Int) {
        val target = _state.value.dialog ?: return
        _state.update {
            val root = if (target.leafId == null) {
                ConditionTree.addChild(it.root, target.parentId, ConditionLeaf(tempId(), condition, holdSeconds))
            } else {
                ConditionTree.updateLeaf(it.root, target.leafId, condition, holdSeconds)
            }
            it.copy(root = root, dialog = null, errors = emptyList())
        }
    }

    fun addGroup(parentId: Long) = _state.update {
        it.copy(root = ConditionTree.addChild(it.root, parentId, ConditionGroup(tempId(), LogicOperator.OR, emptyList())))
    }

    fun remove(id: Long) = _state.update { it.copy(root = ConditionTree.remove(it.root, id), errors = emptyList()) }

    fun setOperator(groupId: Long, op: LogicOperator) =
        _state.update { it.copy(root = ConditionTree.setOperator(it.root, groupId, op)) }

    // ------------------------------------------------------------------ navegação
    fun next() {
        val s = _state.value
        val errors = validateStep(s, s.step)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
        } else {
            _state.update { it.copy(step = (it.step + 1).coerceAtMost(LAST_STEP), errors = emptyList()) }
        }
    }

    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0), errors = emptyList()) }

    fun goTo(step: Int) {
        val s = _state.value
        // Só avança para etapas já validadas.
        if (step <= s.step) _state.update { it.copy(step = step, errors = emptyList()) }
    }

    private fun validateStep(s: EditorUiState, step: Int): List<String> = when (step) {
        0 -> if (s.name.isBlank()) listOf("Dê um nome à automação.") else emptyList()
        1 -> ConditionTree.validate(s.root, s.locations.map { it.id }.toSet())
        else -> emptyList()
    }

    fun save() {
        val s = _state.value
        val errors = (0..2).flatMap { validateStep(s, it) }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            repository.save(
                Automation(
                    id = s.existingId,
                    name = s.name.trim(),
                    description = s.description.trim(),
                    icon = s.icon,
                    enabled = s.enabled,
                    root = s.root.normalized(),
                    action = s.action,
                    minIntervalSeconds = s.minIntervalSeconds,
                    repeatEverySeconds = s.repeatEverySeconds,
                ),
            )
            scheduler.runSoon(TriggerSource.APP) // linha de base imediata da nova regra
            _state.update { it.copy(saved = true) }
        }
    }

    /** Ids temporários (negativos) viram 0 para o banco atribuir novos. */
    private fun ConditionGroup.normalized(): ConditionGroup = copy(
        id = 0,
        children = children.map {
            when (it) {
                is ConditionLeaf -> it.copy(id = 0)
                is ConditionGroup -> it.normalized()
            }
        },
    )

    companion object {
        const val LAST_STEP = 3
    }
}
