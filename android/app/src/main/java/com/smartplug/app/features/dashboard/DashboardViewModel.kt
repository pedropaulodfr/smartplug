package com.smartplug.app.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.gateway.FailureKind
import com.smartplug.app.domain.gateway.GatewayResult
import com.smartplug.app.domain.gateway.PlugGateway
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.PlugState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Checking : Connection
    data object Connected : Connection
    data object DeviceOffline : Connection
    data class Error(val kind: FailureKind, val message: String) : Connection
}

sealed interface CommandUi {
    data object Idle : CommandUi
    data class Sending(val target: Boolean) : CommandUi
    data class Confirmed(val on: Boolean) : CommandUi
    data class Failed(val message: String) : CommandUi
}

data class DashboardUiState(
    val configured: Boolean = true,
    val deviceName: String = "Smart Plug",
    val plug: PlugState? = null,
    val connection: Connection = Connection.Checking,
    val refreshing: Boolean = false,
    val command: CommandUi = CommandUi.Idle,
    val automations: List<Automation> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val gateway: PlugGateway,
    private val prefs: AppPrefs,
    private val automations: AutomationRepository,
    private val clock: Clock,
) : ViewModel() {

    private val live = MutableStateFlow(DashboardUiState())

    val state: StateFlow<DashboardUiState> = combine(
        live, prefs.configured, prefs.profileFlow, automations.observeAll(),
    ) { ui, configured, profile, list ->
        ui.copy(
            configured = configured,
            deviceName = profile?.deviceName ?: ui.deviceName,
            automations = list,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private var pollJob: Job? = null

    /** Chamado quando a tela fica visível: atualiza e mantém a leitura periódica. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshInternal()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        if (!prefs.configured.first()) {
            live.update { it.copy(connection = Connection.Checking, plug = null) }
            return
        }
        live.update { it.copy(refreshing = true) }
        when (val result = gateway.readState()) {
            is GatewayResult.Success -> {
                prefs.setObservedOn(result.value.on)
                live.update {
                    it.copy(
                        plug = result.value,
                        refreshing = false,
                        connection = if (result.value.online) Connection.Connected else Connection.DeviceOffline,
                    )
                }
            }
            is GatewayResult.Failure -> live.update {
                it.copy(refreshing = false, connection = Connection.Error(result.kind, result.message))
            }
        }
    }

    fun setPower(on: Boolean) {
        if (live.value.command is CommandUi.Sending) return
        viewModelScope.launch {
            live.update { it.copy(command = CommandUi.Sending(on)) }
            when (val result = gateway.setPower(on)) {
                is GatewayResult.Success -> {
                    val confirmed = result.value.on == on
                    if (confirmed) {
                        // O comando manual também é "o último comando enviado por este app".
                        prefs.setExpectedOn(on, clock.nowMillis())
                        prefs.setObservedOn(on)
                    }
                    live.update {
                        it.copy(
                            plug = result.value,
                            connection = Connection.Connected,
                            command = if (confirmed) CommandUi.Confirmed(on)
                            else CommandUi.Failed("Comando enviado, mas o plug ainda não confirmou. Atualize em instantes."),
                        )
                    }
                }
                is GatewayResult.Failure -> live.update {
                    it.copy(command = CommandUi.Failed(result.message))
                }
            }
            delay(3_500)
            live.update { if (it.command is CommandUi.Sending) it else it.copy(command = CommandUi.Idle) }
        }
    }

    private companion object {
        const val POLL_MS = 15_000L
    }
}
