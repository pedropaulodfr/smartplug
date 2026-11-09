package com.smartplug.app.features.locations

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.ui.components.ConfirmDialog
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.StatusPill
import com.smartplug.app.ui.theme.Amber
import com.smartplug.app.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: LocationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<LocationItem?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Locais") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "Adicionar local") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LocationPermissionCard(
                    fine = state.fineLocation,
                    background = state.backgroundLocation,
                    onChanged = viewModel::refreshPermissions,
                )
            }
            if (state.items.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Place,
                        title = "Nenhum local cadastrado",
                        message = "Cadastre \"Minha casa\", \"Trabalho\" etc. para criar automações que ligam ou desligam o plug ao chegar ou sair.",
                        actionLabel = "Adicionar local",
                        onAction = onAdd,
                    )
                }
            }
            items(state.items, key = { it.info.location.id }) { item ->
                val location = item.info.location
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(location.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Raio ${location.radiusMeters.toInt()} m · usado em ${item.usedBy} automação(ões)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when (item.info.status) {
                                LocationStatus.INSIDE -> StatusPill("Você está aqui", Success)
                                LocationStatus.OUTSIDE -> StatusPill("Fora desta área", MaterialTheme.colorScheme.outline)
                                LocationStatus.UNKNOWN -> StatusPill("Posição desconhecida", Amber)
                            }
                        }
                        IconButton(onClick = { onEdit(location.id) }) { Icon(Icons.Rounded.Edit, "Editar") }
                        IconButton(onClick = { toDelete = item }) { Icon(Icons.Rounded.Delete, "Excluir") }
                    }
                }
            }
        }
    }

    toDelete?.let { item ->
        ConfirmDialog(
            title = "Excluir \"${item.info.location.name}\"?",
            message = if (item.usedBy > 0) {
                "${item.usedBy} automação(ões) usam este local. Elas deixarão de executar (condição indisponível) até você escolher outro local."
            } else "Esta ação não pode ser desfeita.",
            confirmLabel = "Excluir",
            onConfirm = {
                viewModel.delete(item.info.location.id)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
}

/** Pede as permissões em duas etapas, explicando o motivo de cada uma. */
@Composable
fun LocationPermissionCard(fine: Boolean, background: Boolean, onChanged: () -> Unit) {
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    if (fine && background) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!fine) {
                Text("Permitir localização", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Usamos a localização somente para saber quando você entra ou sai dos locais que você cadastrar. " +
                        "Não há rastreamento contínuo nem envio da sua posição para servidores.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = {
                    foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Permitir localização") }
            } else if (!background) {
                Text("Permitir também em segundo plano", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Para as automações de chegada e saída funcionarem com o app fechado, escolha " +
                        "\"Permitir o tempo todo\" na próxima tela. Sem isso, elas só funcionam com o app aberto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Permitir em segundo plano") }
            }
        }
    }
}
