package com.smartplug.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Estado atual das permissões de localização (lido sempre na hora: o usuário pode revogar). */
@Singleton
class PermissionChecker @Inject constructor(@ApplicationContext private val context: Context) {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarseLocation() = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasAnyLocation() = hasFineLocation() || hasCoarseLocation()

    /** Abaixo do Android 10 o acesso em segundo plano vem junto com a permissão em primeiro plano. */
    fun hasBackgroundLocation() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else hasFineLocation()

    /** Geofencing exige localização precisa; para funcionar sem o app aberto, também a de segundo plano. */
    fun canGeofenceInBackground() = hasFineLocation() && hasBackgroundLocation()
}
