package com.smartplug.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.location.GeofenceManager
import com.smartplug.app.workers.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restaura tudo após: reinício do aparelho, atualização do app, mudança de fuso/horário.
 * Os dados já estão no Room; aqui só se reagenda o trabalho periódico e se registram os geofences.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var geofences: GeofenceManager

    override fun onReceive(context: Context, intent: Intent) {
        val source = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> TriggerSource.BOOT
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> TriggerSource.TIME_CHANGED
            else -> return
        }
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.ensurePeriodic()
                if (source == TriggerSource.BOOT) withTimeoutOrNull(8_000) { geofences.sync() }
                // Rodada de recuperação: sem eventos, só reavalia (a linha de base evita disparos duplicados).
                scheduler.runSoon(source, delaySeconds = 20)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
