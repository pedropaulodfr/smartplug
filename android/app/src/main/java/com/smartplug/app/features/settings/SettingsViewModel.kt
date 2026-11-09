package com.smartplug.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.integrations.tuya.SetupResult
import com.smartplug.app.integrations.tuya.TuyaCredentials
import com.smartplug.app.integrations.tuya.TuyaRegion
import com.smartplug.app.integrations.tuya.TuyaSetupService
import com.smartplug.app.location.GeofenceManager
import com.smartplug.app.location.GeofenceSync
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

data class CredentialsForm(
    val clientId: String = "",
    val clientSecret: String = "",
    val deviceId: String = "",
    val region: TuyaRegion = TuyaRegion.US,
)

sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Testing : SetupStatus
    data class Ok(val deviceName: String, val online: Boolean) : SetupStatus
    data class Error(val message: String) : SetupStatus
}

data class SettingsUiState(
    val configured: Boolean = false,
    val form: CredentialsForm = CredentialsForm(),
    val setup: SetupStatus = SetupStatus.Idle,
    val notifications: Boolean = false,
    val periodicMinutes: Int = 15,
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val geofenceMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPrefs,
    private val setupService: TuyaSetupService,
    private val permissions: PermissionChecker,
    private val scheduler: WorkScheduler,
    private val geofences: GeofenceManager,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(
        local, prefs.configured, prefs.notificationsEnabled, prefs.periodicMinutes,
    ) { ui, configured, notifications, minutes ->
        ui.copy(configured = configured, notifications = notifications, periodicMinutes = minutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            prefs.credentialsSummary()?.let { saved ->
                local.update {
                    it.copy(
                        form = CredentialsForm(
                            clientId = saved.clientId,
                            clientSecret = "",
                            deviceId = saved.deviceId,
                            region = TuyaRegion.fromCode(saved.region),
                        ),
                    )
                }
            }
        }
        refreshPermissions()
    }

    fun refreshPermissions() {
        local.update {
            it.copy(
                fineLocation = permissions.hasFineLocation(),
                backgroundLocation = permissions.hasBackgroundLocation(),
            )
        }
    }

    fun updateForm(transform: (CredentialsForm) -> CredentialsForm) {
        local.update { it.copy(form = transform(it.form), setup = SetupStatus.Idle) }
    }

    fun testAndSave() {
        val form = local.value.form
        if (form.clientId.isBlank() || form.deviceId.isBlank()) {
            local.update { it.copy(setup = SetupStatus.Error("Preencha Client ID e Device ID.")) }
            return
        }
        viewModelScope.launch {
            local.update { it.copy(setup = SetupStatus.Testing) }
            // Secret em branco = manter o já salvo (edição sem digitar de novo).
            val secret = form.clientSecret.ifBlank { prefs.credentials()?.clientSecret.orEmpty() }
            if (secret.isBlank()) {
                local.update { it.copy(setup = SetupStatus.Error("Informe o Client Secret.")) }
                return@launch
            }
            val result = setupService.verifyAndSave(
                TuyaCredentials(form.clientId.trim(), secret.trim(), form.region.code, form.deviceId.trim()),
            )
            local.update {
                it.copy(
                    setup = when (result) {
                        is SetupResult.Success -> SetupStatus.Ok(result.profile.deviceName, result.online)
                        is SetupResult.Failure -> SetupStatus.Error(result.message)
                    },
                    form = it.form.copy(clientSecret = ""),
                )
            }
        }
    }

    fun removeCredentials() {
        viewModelScope.launch {
            prefs.clearCredentials()
            local.update { it.copy(form = CredentialsForm(), setup = SetupStatus.Idle) }
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotificationsEnabled(enabled) }
    }

    fun setPeriodicMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setPeriodicMinutes(minutes)
            scheduler.ensurePeriodic(replace = true)
        }
    }

    fun runEngineNow() {
        scheduler.runSoon(TriggerSource.MANUAL)
    }

    fun resyncGeofences() {
        viewModelScope.launch {
            val message = when (val r = geofences.sync()) {
                is GeofenceSync.Registered ->
                    "${r.count} local(is) registrado(s)" + if (r.backgroundReady) "." else
                        ". Falta a permissão de localização em segundo plano para funcionar com o app fechado."
                GeofenceSync.NothingToRegister -> "Nenhum local cadastrado."
                GeofenceSync.PermissionMissing -> "Permissão de localização precisa não concedida."
                is GeofenceSync.Failed -> "Falha: ${r.message}"
            }
            local.update { it.copy(geofenceMessage = message) }
            refreshPermissions()
        }
    }
}
