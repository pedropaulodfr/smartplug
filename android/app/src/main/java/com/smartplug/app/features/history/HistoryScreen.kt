package com.smartplug.app.features.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.repo.ExecutionRepository
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.ui.components.ConfirmDialog
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.SectionTitle
import com.smartplug.app.ui.theme.Amber
import com.smartplug.app.ui.theme.Danger
import com.smartplug.app.ui.theme.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: ExecutionRepository) : ViewModel() {
    val records: StateFlow<List<ExecutionRecord>?> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}

private data class OutcomeStyle(val icon: ImageVector, val color: Color)

@Composable
private fun ExecutionOutcome.style(): OutcomeStyle = when (this) {
    ExecutionOutcome.SUCCESS -> OutcomeStyle(Icons.Rounded.CheckCircle, Success)
    ExecutionOutcome.CONDITIONS_NOT_MET -> OutcomeStyle(Icons.Rounded.RemoveCircleOutline, MaterialTheme.colorScheme.outline)
    ExecutionOutcome.COMM_FAILURE -> OutcomeStyle(Icons.Rounded.CloudOff, Danger)
    ExecutionOutcome.IGNORED_DUPLICATE -> OutcomeStyle(Icons.Rounded.Block, Amber)
    ExecutionOutcome.IGNORED_CYCLE -> OutcomeStyle(Icons.Rounded.Autorenew, Amber)
}

private val dateFormat get() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<ExecutionOutcome?>(null) }
    var selected by remember { mutableStateOf<ExecutionRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Histórico") },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = !records.isNullOrEmpty()) {
                        Icon(Icons.Rounded.DeleteSweep, "Limpar histórico")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Todas") })
                ExecutionOutcome.entries.forEach { outcome ->
                    FilterChip(
                        selected = filter == outcome,
                        onClick = { filter = if (filter == outcome) null else outcome },
                        label = { Text(outcome.label) },
                    )
                }
            }
            val shown = records.orEmpty().filter { filter == null || it.outcome == filter }
            if (records != null && shown.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = "Sem registros",
                    message = "Quando as automações avaliarem ou executarem algo, os detalhes aparecem aqui.",
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { record ->
                    val style = record.outcome.style()
                    Card(
                        modifier = Modifier.clickable { selected = record },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(style.icon, null, tint = style.color)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    record.automationName + if (record.isTest) " (teste)" else "",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    record.outcome.label + (record.message?.let { " — $it" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                Text(
                                    dateFormat.format(Date(record.timestampMillis)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            ExecutionDetails(record)
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Limpar histórico?",
            message = "Todos os registros de execução serão apagados.",
            confirmLabel = "Limpar",
            onConfirm = {
                viewModel.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ExecutionDetails(record: ExecutionRecord) {
    val style = record.outcome.style()
    fun stateText(on: Boolean?) = when (on) {
        true -> "Ligado"
        false -> "Desligado"
        null -> "—"
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(style.icon, null, tint = style.color)
            Text(record.automationName, style = MaterialTheme.typography.titleLarge)
        }
        DetailLine("Resultado", record.outcome.label)
        DetailLine("Quando", dateFormat.format(Date(record.timestampMillis)))
        DetailLine("Gatilho", record.trigger + if (record.isTest) " (teste manual)" else "")
        record.action?.let { DetailLine("Ação", it) }
        DetailLine("Estado anterior do plug", stateText(record.stateBefore))
        DetailLine("Estado posterior do plug", stateText(record.stateAfter))
        record.commResult?.let { DetailLine("Comunicação Tuya", it) }
        record.message?.let { DetailLine("Mensagem", it) }
        if (record.trace.isNotEmpty()) {
            SectionTitle("Condições avaliadas")
            record.trace.forEach { line ->
                val (icon, color) = when (line.result) {
                    "TRUE" -> Icons.Rounded.CheckCircle to Success
                    "FALSE" -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                    else -> Icons.Rounded.Block to Amber
                }
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp))
                    Column {
                        Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (line.result) {
                                "TRUE" -> "verdadeira"
                                "FALSE" -> "falsa"
                                else -> "indisponível"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
