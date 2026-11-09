package com.smartplug.app.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.domain.gateway.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agenda o motor via WorkManager (persistente: sobrevive a reinício do app e do aparelho).
 *  - periódico: mínimo de 15 min imposto pelo Android;
 *  - despertar: acordar perto do fim de uma permanência ou virada de horário (aproximado: o Doze
 *    e a economia de bateria podem atrasar);
 *  - imediato: eventos de geofence, boot, mudança de fuso e nova tentativa.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
    private val clock: Clock,
) {
    private val work get() = WorkManager.getInstance(context)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    suspend fun ensurePeriodic(replace: Boolean = false) {
        val minutes = prefs.periodicMinutesNow().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<EngineWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(EngineWorker.input(TriggerSource.PERIODIC, emptyList()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleWake(atMillis: Long?) {
        if (atMillis == null) {
            work.cancelUniqueWork(WAKE)
            return
        }
        val delay = (atMillis - clock.nowMillis() + 5_000).coerceAtLeast(10_000)
        val request = OneTimeWorkRequestBuilder<EngineWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .setInputData(EngineWorker.input(TriggerSource.WAKE, emptyList()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(WAKE, ExistingWorkPolicy.REPLACE, request)
    }

    /** Executa o motor assim que houver rede. Eventos são preservados para a nova tentativa. */
    fun runSoon(source: TriggerSource, events: List<EngineEvent> = emptyList(), delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<EngineWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(network)
            .setInputData(EngineWorker.input(source, events))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: não perde eventos enfileirados e não roda dois ao mesmo tempo.
        work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelAll() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(WAKE)
        work.cancelUniqueWork(IMMEDIATE)
    }

    @Suppress("unused")
    private fun emptyData(): Data = Data.EMPTY

    private companion object {
        const val PERIODIC = "engine-periodic"
        const val WAKE = "engine-wake"
        const val IMMEDIATE = "engine-immediate"
    }
}
