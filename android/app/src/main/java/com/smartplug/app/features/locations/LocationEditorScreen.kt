package com.smartplug.app.features.locations

import android.Manifest
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationEditorScreen(onClose: () -> Unit, viewModel: LocationEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.refreshPermission()
        if (result.values.any { it }) viewModel.useCurrentLocation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.id == 0L) "Novo local" else "Editar local") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Nome do local (ex.: Minha casa)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MapPicker(
                latitude = state.latitude,
                longitude = state.longitude,
                radius = state.radius,
                onPick = viewModel::setPoint,
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp)),
            )
            Text(
                state.latitude?.let { "Lat %.5f · Lng %.5f".format(it, state.longitude) } ?: "Toque no mapa para escolher a posição.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    if (state.fineLocation) viewModel.useCurrentLocation()
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                enabled = !state.locating,
            ) {
                Icon(Icons.Rounded.MyLocation, null)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                Text(if (state.locating) "Localizando…" else "Usar minha localização atual")
            }
            if (!state.fineLocation) {
                Text(
                    "A localização atual só é lida uma vez, neste momento, para posicionar o marcador.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Raio de abrangência: ${state.radius.toInt()} m", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = state.radius,
                onValueChange = { viewModel.setRadius((Math.round(it / 10f) * 10).toFloat()) },
                valueRange = 100f..2000f,
            )
            Text(
                "Raios menores que ~100 m podem não ser detectados de forma confiável pelo Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text("Salvar local") }
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }
}

/** Mapa OpenStreetMap (osmdroid): toque para posicionar; o círculo mostra o raio. */
@Composable
private fun MapPicker(
    latitude: Double?,
    longitude: Double?,
    radius: Float,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnPick by rememberUpdatedState(onPick)
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(-23.5505, -46.6333))
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        currentOnPick(p.latitude, p.longitude)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }),
            )
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    val centered = remember { booleanArrayOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            // Mantém só o overlay de toques e redesenha marcador + círculo.
            map.overlays.removeAll { it is Marker || it is Polygon }
            if (latitude != null && longitude != null) {
                val point = GeoPoint(latitude, longitude)
                map.overlays.add(
                    Polygon(map).apply {
                        points = Polygon.pointsAsCircle(point, radius.toDouble())
                        fillPaint.color = AndroidColor.argb(60, 15, 118, 110)
                        outlinePaint.color = AndroidColor.rgb(15, 118, 110)
                        outlinePaint.strokeWidth = 4f
                    },
                )
                map.overlays.add(Marker(map).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                if (!centered[0]) {
                    map.controller.setCenter(point)
                    centered[0] = true
                }
            }
            map.invalidate()
        },
    )
}
