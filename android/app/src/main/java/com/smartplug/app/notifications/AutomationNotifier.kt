package com.smartplug.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartplug.app.R
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.repo.ExecutionListener
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExecutionRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notifica o resultado das automações (opcional; exige POST_NOTIFICATIONS no Android 13+). */
@Singleton
class AutomationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
) : ExecutionListener {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automações", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisos quando uma automação liga/desliga o plug ou falha"
            },
        )
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun onRecorded(record: ExecutionRecord) {
        if (record.isTest || !prefs.notificationsEnabledNow() || !canNotify()) return
        val (title, text) = when (record.outcome) {
            ExecutionOutcome.SUCCESS -> record.automationName to "${record.action} — concluído"
            ExecutionOutcome.COMM_FAILURE -> "Falha: ${record.automationName}" to
                (record.message ?: "Não foi possível falar com o plug")
            else -> return
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_plug)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((record.automationId ?: 0L).toInt(), notification)
    }

    private companion object {
        const val CHANNEL = "automations"
    }
}
