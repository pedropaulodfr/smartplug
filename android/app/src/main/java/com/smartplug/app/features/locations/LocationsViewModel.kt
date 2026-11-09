package com.smartplug.app.features.locations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.model.LocationInfo
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.locationId
import com.smartplug.app.location.GeofenceManager
import com.smartplug.app.location.LocationTracker
import com.smartplug.app.location.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationItem(val info: LocationInfo, val usedBy: Int)

data class LocationsUiState(
    val items: List<LocationItem> = emptyList(),
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
)

@HiltViewModel
class LocationsViewModel @Inject constructor(
    private val repository: LocationRepository,
    private val automations: AutomationRepository,
    private val geofences: GeofenceManager,
    private val permissions: PermissionChecker,
) : ViewModel() {

    private val perms = MutableStateFlow(permissions.hasFineLocation() to permissions.hasBackgroundLocation())

    val state: StateFlow<LocationsUiState> = combine(
        repository.observeInfo(), automations.observeAll(), perms,
    ) { infos, autos, (fine, background) ->
        LocationsUiState(
            items = infos.map { info ->
                LocationItem(info, autos.count { a -> a.leaves().any { it.condition.locationId() == info.location.id } })
            },
            fineLocation = fine,
            backgroundLocation = background,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationsUiState())

    fun refreshPermissions() {
        perms.update { permissions.hasFineLocation() to permissions.hasBackgroundLocation() }
        viewModelScope.launch { geofences.sync() }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            geofences.sync()
        }
    }
}

data class EditorLocationState(
    val loaded: Boolean = false,
    val id: Long = 0,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Float = 150f,
    val locating: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    val fineLocation: Boolean = false,
)

@HiltViewModel
class LocationEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: LocationRepository,
    private val tracker: LocationTracker,
    private val geofences: GeofenceManager,
    private val permissions: PermissionChecker,
) : ViewModel() {

    private val editingId: Long = savedState.get<Long>("id") ?: 0L
    private val _state = MutableStateFlow(EditorLocationState(fineLocation = permissions.hasAnyLocation()))
    val state: StateFlow<EditorLocationState> = _state

    init {
        viewModelScope.launch {
            val existing = if (editingId != 0L) repository.byId(editingId) else null
            _state.update {
                if (existing != null) it.copy(
                    loaded = true, id = existing.id, name = existing.name,
                    latitude = existing.latitude, longitude = existing.longitude, radius = existing.radiusMeters,
                ) else it.copy(loaded = true)
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, message = null) }
    fun setRadius(radius: Float) = _state.update { it.copy(radius = radius) }
    fun setPoint(lat: Double, lng: Double) = _state.update { it.copy(latitude = lat, longitude = lng, message = null) }

    fun refreshPermission() = _state.update { it.copy(fineLocation = permissions.hasAnyLocation()) }

    fun useCurrentLocation() {
        if (!permissions.hasAnyLocation()) {
            _state.update { it.copy(message = "Permita o acesso à localização para usar a posição atual.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(locating = true, message = null) }
            val fix = tracker.currentFix()
            _state.update {
                if (fix == null) it.copy(locating = false, message = "Não foi possível obter a localização agora. Verifique o GPS e tente de novo.")
                else it.copy(locating = false, latitude = fix.latitude, longitude = fix.longitude)
            }
        }
    }

    fun save() {
        val s = _state.value
        val lat = s.latitude
        val lng = s.longitude
        when {
            s.name.isBlank() -> _state.update { it.copy(message = "Dê um nome ao local.") }
            lat == null || lng == null -> _state.update { it.copy(message = "Toque no mapa ou use a localização atual.") }
            else -> viewModelScope.launch {
                repository.save(SavedLocation(s.id, s.name.trim(), lat, lng, s.radius))
                geofences.sync()
                _state.update { it.copy(saved = true) }
            }
        }
    }
}
