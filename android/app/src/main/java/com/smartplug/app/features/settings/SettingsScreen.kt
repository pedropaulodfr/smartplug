package com.smartplug.app.features.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.integrations.tuya.TuyaRegion
import com.smartplug.app.ui.components.ConfirmDialog
import com.smartplug.app.ui.components.DropdownField
import com.smartplug.app.ui.components.SectionTitle
import com.smartplug.app.ui.theme.Danger
import com.smartplug.app.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setNotifications(granted)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ------------------------------------------------------------ conta Tuya
            SectionTitle("Conta Tuya Cloud")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Use o Client ID e o Client Secret do projeto em iot.tuya.com (Cloud → seu projeto → " +
                            "Overview) e o Device ID do plug (Devices). As credenciais ficam criptografadas " +
                            "neste aparelho (Android Keystore) e nunca vão para o código do app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.form.clientId,
                        onValueChange = { v -> viewModel.updateForm { it.copy(clientId = v) } },
                        label = { Text("Client ID (Access ID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.form.clientSecret,
                        onValueChange = { v -> viewModel.updateForm { it.copy(clientSecret = v) } },
                        label = { Text("Client Secret (Access Secret)") },
                        placeholder = { if (state.configured) Text("•••• salvo — deixe em branco para manter") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.form.deviceId,
                        onValueChange = { v -> viewModel.updateForm { it.copy(deviceId = v) } },
                        label = { Text("Device ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownField(
                        label = "Data center do projeto",
                        options = TuyaRegion.entries.toList(),
                        selected = state.form.region,
                        display = { "${it.label} (${it.code})" },
                        onSelect = { r -> viewModel.updateForm { it.copy(region = r) } },
                    )
                    SetupFeedback(state.setup)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::testAndSave, enabled = state.setup !is SetupStatus.Testing) {
                            Text("Testar e salvar")
                        }
                        if (state.configured) {
                            TextButton(onClick = { confirmRemove = true }) { Text("Remover") }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- automações
            SectionTitle("Execução em segundo plano")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "As automações são verificadas pelo Android (WorkManager) em intervalos de no mínimo 15 " +
                            "minutos, ao entrar/sair dos locais (geofencing) e perto de horários e tempos de " +
                            "permanência. O sistema pode atrasar a execução com economia de bateria.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Intervalo da verificação periódica", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = state.periodicMinutes == minutes,
                                onClick = { viewModel.setPeriodicMinutes(minutes) },
                                label = { Text(if (minutes == 60) "1 h" else "$minutes min") },
                            )
                        }
                    }
                    OutlinedButton(onClick = viewModel::runEngineNow) { Text("Avaliar automações agora") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("Ajustar otimização de bateria") }
                    Text(
                        "Dica: em aparelhos Xiaomi, Samsung, Huawei, Oppo etc., libere também a " +
                            "\"inicialização automática\" e remova restrições de bateria deste app. " +
                            "Sem isso o fabricante pode bloquear as verificações em segundo plano.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --------------------------------------------------------- permissões
            SectionTitle("Permissões")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionRow(
                        "Localização precisa",
                        "Necessária para detectar quando você entra/sai dos seus locais.",
                        state.fineLocation,
                    )
                    PermissionRow(
                        "Localização em segundo plano",
                        "Necessária para o geofencing funcionar com o app fechado. No Android 11+ escolha " +
                            "\"Permitir o tempo todo\" nas permissões do app.",
                        state.backgroundLocation,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text("Abrir permissões do app") }
                    OutlinedButton(onClick = viewModel::resyncGeofences) { Text("Registrar locais novamente") }
                    state.geofenceMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionTitle("Notificações")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Avisar quando uma automação agir", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Mostra uma notificação ao ligar/desligar o plug ou em caso de falha.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.notifications,
                        onCheckedChange = { wanted ->
                            if (wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setNotifications(wanted)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remover credenciais?",
            message = "O app deixará de controlar o plug até você configurar a conta novamente. As automações são mantidas.",
            confirmLabel = "Remover",
            onConfirm = {
                confirmRemove = false
                viewModel.removeCredentials()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun SetupFeedback(status: SetupStatus) {
    when (status) {
        SetupStatus.Idle -> Unit
        SetupStatus.Testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp).height(20.dp))
            Text("Validando com a Tuya…")
        }
        is SetupStatus.Ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Success)
            Text("Conectado a \"${status.deviceName}\"" + if (status.online) "" else " (aparelho offline agora)")
        }
        is SetupStatus.Error -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Danger)
            Text(status.message, color = Danger)
        }
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            null,
            tint = if (granted) Success else MaterialTheme.colorScheme.outline,
        )
        Column {
            Text(title + if (granted) " — concedida" else " — pendente", style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
