package com.smartplug.app.features.automations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.domain.model.TriState
import com.smartplug.app.ui.components.AutomationIcons
import com.smartplug.app.ui.components.ConfirmDialog
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.StatusPill
import com.smartplug.app.ui.theme.Amber
import com.smartplug.app.ui.theme.Danger
import com.smartplug.app.ui.theme.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    onCreate: (templateId: String?) -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showTemplates by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<AutomationItem?>(null) }
    var toRun by remember { mutableStateOf<AutomationItem?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Automações") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTemplates = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Nova automação") },
            )
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Nenhuma automação",
                    message = "Crie regras como \"ligar ao chegar em casa depois das 16h\" ou \"desligar quando parar de consumir\". Comece por um modelo pronto.",
                    actionLabel = "Criar automação",
                    onAction = { showTemplates = true },
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.automation.id }) { item ->
                    AutomationCard(
                        item = item,
                        onClick = { onEdit(item.automation.id) },
                        onToggle = { viewModel.setEnabled(item.automation.id, it) },
                        onEdit = { onEdit(item.automation.id) },
                        onDuplicate = { viewModel.duplicate(item.automation.id) },
                        onDelete = { toDelete = item },
                        onTestConditions = { viewModel.dryRun(item) },
                        onRunNow = { toRun = item },
                    )
                }
            }
        }
    }

    if (showTemplates) {
        ModalBottomSheet(onDismissRequest = { showTemplates = false }) {
            TemplatePicker(
                onPick = {
                    showTemplates = false
                    onCreate(it)
                },
            )
        }
    }

    toDelete?.let { item ->
        ConfirmDialog(
            title = "Excluir \"${item.automation.name}\"?",
            message = "A automação será removida. O histórico de execuções é mantido.",
            confirmLabel = "Excluir",
            onConfirm = {
                viewModel.delete(item.automation.id)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
    toRun?.let { item ->
        ConfirmDialog(
            title = "Executar agora?",
            message = "Isto envia \"${ConditionText.describe(item.automation.action)}\" ao plug imediatamente, ignorando as condições.",
            confirmLabel = "Executar",
            onConfirm = {
                viewModel.executeNow(item.automation.id)
                toRun = null
            },
            onDismiss = { toRun = null },
        )
    }
    state.test?.let { TestResultDialog(it, onDismiss = viewModel::dismissTest) }
}

@Composable
private fun AutomationCard(
    item: AutomationItem,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTestConditions: () -> Unit,
    onRunNow: () -> Unit,
) {
    val automation = item.automation
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AutomationIcons.of(automation.icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(automation.name, style = MaterialTheme.typography.titleMedium)
                    if (automation.description.isNotBlank()) {
                        Text(
                            automation.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Switch(checked = automation.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais opções") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Editar") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Duplicar") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Testar condições") }, leadingIcon = { Icon(Icons.Rounded.Science, null) },
                            onClick = { menu = false; onTestConditions() })
                        DropdownMenuItem(text = { Text("Executar agora") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                            onClick = { menu = false; onRunNow() })
                        DropdownMenuItem(text = { Text("Excluir") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            LabeledLine("SE", item.conditionsText)
            LabeledLine("ENTÃO", ConditionText.describe(automation.action))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    if (automation.enabled) "Habilitada" else "Desabilitada",
                    if (automation.enabled) Success else MaterialTheme.colorScheme.outline,
                )
                Text(
                    item.lastExecutionMillis?.let { "Última execução: " + formatWhen(it) } ?: "Nunca executou",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.warning?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Amber)
                }
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 1.dp).size(width = 52.dp, height = 20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatWhen(millis: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun TemplatePicker(onPick: (String?) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Comece por um modelo", style = MaterialTheme.typography.titleLarge)
        Text(
            "Você poderá personalizar tudo antes de salvar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Templates.all.forEach { template ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPick(template.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(AutomationIcons.of(template.icon), null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(template.title, style = MaterialTheme.typography.titleMedium)
                        Text(template.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onPick(null) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Automação em branco", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TestResultDialog(test: TestDialog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = {
            Text(
                when (test) {
                    is TestDialog.Evaluating -> "Aguarde…"
                    is TestDialog.Evaluated -> "Condições de \"${test.name}\""
                    is TestDialog.Executed -> "Resultado da execução"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (test) {
                    is TestDialog.Evaluating -> CircularProgressIndicator()
                    is TestDialog.Evaluated -> {
                        val (text, color) = when (test.result.state) {
                            TriState.TRUE -> "As condições estão atendidas agora." to Success
                            TriState.FALSE -> "As condições não estão atendidas agora." to MaterialTheme.colorScheme.outline
                            TriState.UNKNOWN -> "Não é possível avaliar: falta algum dado (localização, plug offline…)." to Amber
                        }
                        Text(text, color = color, style = MaterialTheme.typography.titleMedium)
                        test.result.trace.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    if (line.result == "TRUE") Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                    null,
                                    tint = when (line.result) {
                                        "TRUE" -> Success
                                        "FALSE" -> MaterialTheme.colorScheme.outline
                                        else -> Amber
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(
                            "Teste somente leitura: nada foi enviado ao plug.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is TestDialog.Executed -> {
                        val record = test.record
                        if (record == null) {
                            Text("Automação não encontrada.")
                        } else {
                            Text(record.outcome.label, style = MaterialTheme.typography.titleMedium,
                                color = if (record.outcome.name == "SUCCESS") Success else Danger)
                            record.message?.let { Text(it) }
                            record.commResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text("Registrado no histórico.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}
