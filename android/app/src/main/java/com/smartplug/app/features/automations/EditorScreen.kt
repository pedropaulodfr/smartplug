package com.smartplug.app.features.automations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.ToggleAction
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import com.smartplug.app.domain.model.usesPlugData
import com.smartplug.app.ui.components.AutomationIcons
import com.smartplug.app.ui.components.DropdownField
import com.smartplug.app.ui.components.SectionTitle
import com.smartplug.app.ui.theme.Amber

private val stepTitles = listOf("Informações", "Condições", "Ação", "Revisão")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onClose: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.existingId == 0L) "Nova automação" else "Editar automação") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Fechar") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step > 0) OutlinedButton(onClick = viewModel::back) { Text("Voltar") } else Spacer(Modifier.size(1.dp))
                    if (state.step < EditorViewModel.LAST_STEP) {
                        Button(onClick = viewModel::next) { Text("Avançar") }
                    } else {
                        Button(onClick = viewModel::save) {
                            Icon(Icons.Rounded.Check, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Salvar automação")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Stepper(state.step, onSelect = viewModel::goTo)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
                    0 -> StepInfo(state, viewModel)
                    1 -> StepConditions(state, viewModel)
                    2 -> StepAction(state, viewModel)
                    else -> StepReview(state, viewModel)
                }
                if (state.errors.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.errors.forEach {
                                Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    state.dialog?.let { target ->
        ConditionDialog(
            initial = target.initial,
            initialHoldSeconds = target.initialHoldSeconds,
            locations = state.locations,
            onConfirm = viewModel::confirmCondition,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun Stepper(current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stepTitles.forEachIndexed { index, title ->
            val done = index < current
            val active = index == current
            Column(
                Modifier.weight(1f).clickable(enabled = index <= current) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (active || done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        else Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------- etapa 1
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepInfo(state: EditorUiState, vm: EditorViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text("Nome") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.description,
        onValueChange = vm::setDescription,
        label = { Text("Descrição (opcional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    SectionTitle("Ícone")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AutomationIcons.all.forEach { (key, icon) ->
            val selected = state.icon == key
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.size(52.dp).clip(CircleShape).clickable { vm.setIcon(key) },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, key) }
            }
        }
    }
}

// ------------------------------------------------------------------- etapa 2
@Composable
private fun StepConditions(state: EditorUiState, vm: EditorViewModel) {
    Text(
        "Defina QUANDO a automação deve agir. Ela dispara quando o resultado passa de falso para verdadeiro, " +
            "uma vez — não a cada verificação.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GroupEditor(state.root, state, vm, depth = 0, isRoot = true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditor(group: ConditionGroup, state: EditorUiState, vm: EditorViewModel, depth: Int, isRoot: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (depth % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (isRoot) null else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isRoot) "Combinar com:" else "Grupo:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
                LogicOperator.entries.forEach { op ->
                    FilterChip(
                        selected = group.operator == op,
                        onClick = { vm.setOperator(group.id, op) },
                        label = { Text(if (op == LogicOperator.AND) "E (todas)" else "OU (qualquer)") },
                    )
                }
                if (!isRoot) {
                    IconButton(onClick = { vm.remove(group.id) }) { Icon(Icons.Rounded.Delete, "Remover grupo") }
                }
            }

            if (group.children.isEmpty()) {
                Text("Nenhuma condição ainda.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            group.children.forEachIndexed { index, node ->
                if (index > 0) {
                    Text(group.operator.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                when (node) {
                    is ConditionLeaf -> Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ConditionText.describe(node, state::locationName),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.openEditCondition(node, group.id) }) { Icon(Icons.Rounded.Edit, "Editar") }
                            IconButton(onClick = { vm.remove(node.id) }) { Icon(Icons.Rounded.Delete, "Remover") }
                        }
                    }
                    is ConditionGroup -> GroupEditor(node, state, vm, depth + 1, isRoot = false)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.openNewCondition(group.id) }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Condição")
                }
                TextButton(onClick = { vm.addGroup(group.id) }) { Text("+ Grupo (E/OU)") }
            }
        }
    }
}

// ------------------------------------------------------------------- etapa 3
@Composable
private fun StepAction(state: EditorUiState, vm: EditorViewModel) {
    SectionTitle("O que fazer quando as condições forem atendidas")
    listOf<ActionSpec>(TurnOnAction, TurnOffAction, ToggleAction).forEach { action ->
        Card(
            onClick = { vm.setAction(action) },
            colors = CardDefaults.cardColors(
                containerColor = if (state.action == action) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.action == action, onClick = { vm.setAction(action) })
                Text(ConditionText.describe(action), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    SectionTitle("Proteção contra repetição")
    DropdownField(
        label = "Intervalo mínimo entre execuções",
        options = listOf(0, 60, 300, 900, 1800, 3600),
        selected = state.minIntervalSeconds,
        display = { if (it == 0) "Sem intervalo" else ConditionText.duration(it) },
        onSelect = vm::setMinInterval,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Repetir enquanto a condição continuar", style = MaterialTheme.typography.titleMedium)
            Text(
                "Reaplica o estado periodicamente (útil se alguém mudar manualmente). Desligado: executa só uma vez por transição.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.repeatEverySeconds != null, onCheckedChange = { vm.setRepeat(if (it) 1800 else null) })
    }
    state.repeatEverySeconds?.let { current ->
        DropdownField(
            label = "Repetir a cada",
            options = listOf(900, 1800, 3600, 7200, 21600),
            selected = current,
            display = { ConditionText.duration(it) },
            onSelect = { vm.setRepeat(it) },
        )
    }
}

// ------------------------------------------------------------------- etapa 4
@Composable
private fun StepReview(state: EditorUiState, vm: EditorViewModel) {
    Text(state.name.ifBlank { "Sem nome" }, style = MaterialTheme.typography.titleLarge)
    if (state.description.isNotBlank()) {
        Text(state.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    RuleView(state.root, state.action, state::locationName)

    val notes = buildList {
        add("Dispara uma vez a cada transição de falso para verdadeiro; intervalo mínimo de " +
            (if (state.minIntervalSeconds == 0) "0 s" else ConditionText.duration(state.minIntervalSeconds)) + ".")
        state.repeatEverySeconds?.let { add("Repete a cada ${ConditionText.duration(it)} enquanto a condição continuar verdadeira.") }
        add("Ao salvar, o estado atual vira a linha de base: se as condições já forem verdadeiras agora, ela só agirá na próxima transição.")
    }
    notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    if (state.root.leavesCompat().any { it.condition.usesPlugData() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
            Text(
                "Usa dados do plug: dependem de internet e de o plug estar online. Sem dados, a regra não executa.",
                style = MaterialTheme.typography.bodyMedium,
                color = Amber,
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Habilitada ao salvar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = state.enabled, onCheckedChange = vm::setEnabled)
    }
}

private fun ConditionGroup.leavesCompat(): List<ConditionLeaf> =
    children.flatMap { if (it is ConditionLeaf) listOf(it) else (it as ConditionGroup).leavesCompat() }
