package com.smartplug.app.features.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ElectricMeter
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.ConditionText
import com.smartplug.app.ui.components.AutomationIcons
import com.smartplug.app.ui.components.MetricTile
import com.smartplug.app.ui.components.SectionTitle
import com.smartplug.app.ui.components.StatusPill
import com.smartplug.app.ui.theme.Amber
import com.smartplug.app.ui.theme.Danger
import com.smartplug.app.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenAutomations: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditAutomation: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.deviceName) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = state.configured) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (!state.configured) {
                SetupCard(onOpenSettings)
            } else {
                PowerCard(state, onToggle = { viewModel.setPower(it) })
                ConnectionRow(state, onOpenSettings)
                MetricsGrid(state)
            }

            SectionTitle("Atalhos de automações")
            if (state.automations.isEmpty()) {
                Card(
                    onClick = onOpenAutomations,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Crie sua primeira automação", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                state.automations.take(4).forEach { automation ->
                    Card(
                        onClick = { onEditAutomation(automation.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(AutomationIcons.of(automation.icon), null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(automation.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    ConditionText.describe(automation.action),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(
                                if (automation.enabled) "Ativa" else "Pausada",
                                if (automation.enabled) Success else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                TextButton(onClick = onOpenAutomations, modifier = Modifier.align(Alignment.End)) {
                    Text("Ver todas")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Power, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            Text("Conecte seu Smart Plug", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Informe as credenciais do seu projeto Tuya Cloud e o ID do aparelho para ligar, desligar e ver o consumo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onOpenSettings) { Text("Configurar agora") }
        }
    }
}

@Composable
private fun PowerCard(state: DashboardUiState, onToggle: (Boolean) -> Unit) {
    val plug = state.plug
    val on = plug?.on == true
    val sending = state.command as? CommandUi.Sending
    val container by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "card",
    )
    val buttonColor by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "button",
    )
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale",
    )

    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = buttonColor,
                modifier = Modifier
                    .size(132.dp)
                    .scale(if (sending != null) pulse.value else 1f)
                    .clickable(enabled = plug != null && sending == null) { onToggle(!on) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (sending != null) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    } else {
                        Icon(Icons.Rounded.PowerSettingsNew, "Alternar", tint = Color.White, modifier = Modifier.size(64.dp))
                    }
                }
            }
            Text(
                when {
                    plug == null -> "Aguardando leitura…"
                    on -> "Ligado"
                    else -> "Desligado"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            CommandFeedback(state.command)
        }
    }
}

@Composable
private fun CommandFeedback(command: CommandUi) {
    when (command) {
        CommandUi.Idle -> Spacer(Modifier.height(20.dp))
        is CommandUi.Sending -> Text(
            if (command.target) "Enviando comando para ligar…" else "Enviando comando para desligar…",
            style = MaterialTheme.typography.bodyMedium,
        )
        is CommandUi.Confirmed -> StatusPill(if (command.on) "Confirmado: ligado" else "Confirmado: desligado", Success)
        is CommandUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusPill("Falha no comando", Danger)
            Spacer(Modifier.height(4.dp))
            Text(command.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ConnectionRow(state: DashboardUiState, onOpenSettings: () -> Unit) {
    val (label, color) = when (val c = state.connection) {
        Connection.Checking -> "Verificando conexão…" to MaterialTheme.colorScheme.outline
        Connection.Connected -> "Conectado à nuvem Tuya" to Success
        Connection.DeviceOffline -> "Plug offline" to Amber
        is Connection.Error -> "Sem comunicação" to Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusPill(label, color)
        val updated = state.plug?.readAtMillis
        if (updated != null) {
            Text(
                "atualizado " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(updated)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    (state.connection as? Connection.Error)?.let { error ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun MetricsGrid(state: DashboardUiState) {
    val plug = state.plug
    fun fmt(value: Double?, digits: Int) = value?.let { "%.${digits}f".format(java.util.Locale.getDefault(), it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Tensão", fmt(plug?.voltageV, 1), "V", Icons.Rounded.Bolt, Modifier.weight(1f))
            MetricTile("Corrente", fmt(plug?.currentA, 3), "A", Icons.Rounded.ElectricMeter, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("Potência", fmt(plug?.powerW, 1), "W", Icons.Rounded.Speed, Modifier.weight(1f))
            MetricTile("Consumo", fmt(plug?.energyKwh, 3), "kWh", Icons.Rounded.Timer, Modifier.weight(1f))
        }
        MetricTile(
            "Latência da nuvem",
            plug?.latencyMs?.toString() ?: "—",
            "ms",
            Icons.Rounded.Refresh,
            Modifier.fillMaxWidth(),
        )
    }
}

