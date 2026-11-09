package com.smartplug.app

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.smartplug.app.data.prefs.AppPrefs
import com.smartplug.app.data.prefs.SecretBox
import com.smartplug.app.domain.engine.EngineEvent
import com.smartplug.app.domain.engine.TriggerSource
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.integrations.tuya.DeviceProfile
import com.smartplug.app.integrations.tuya.TuyaCredentials
import com.smartplug.app.workers.EngineWorker
import com.smartplug.app.workers.WorkScheduler
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Segredos no Keystore, agendamento no WorkManager e dados do ambiente real do Android. */
@RunWith(AndroidJUnit4::class)
class EnvironmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = object : Clock { override fun nowMillis() = System.currentTimeMillis() }

    private fun prefs(name: String = "prefs-${System.nanoTime()}") = AppPrefs(
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "$name.preferences_pb") },
        ),
        SecretBox(),
        Json { ignoreUnknownKeys = true },
    )

    @Test fun secretBoxCriptografaEDescriptografaComKeystore() {
        val box = SecretBox()
        val encrypted = box.encrypt("segredo-123")
        assertFalse(encrypted.contains("segredo-123"))
        assertEquals("segredo-123", box.decrypt(encrypted))
        assertNotEquals(encrypted, box.encrypt("segredo-123")) // IV aleatório
        assertNull(box.decrypt("lixo-invalido"))
    }

    @Test fun credenciaisPersistemCriptografadasESobrevivemAUmNovoObjeto() = runBlocking {
        val name = "creds-${System.nanoTime()}"
        val firstJob = kotlinx.coroutines.SupervisorJob()
        val firstScope = kotlinx.coroutines.CoroutineScope(firstJob)
        val first = AppPrefs(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = firstScope,
                produceFile = { File(context.cacheDir, "$name.preferences_pb") },
            ),
            SecretBox(), Json { ignoreUnknownKeys = true },
        )
        assertNull(first.credentials())
        first.saveCredentials(TuyaCredentials("id-publico", "SEGREDO-NAO-VAZAR", "us", "dev-1"))
        first.saveProfile(DeviceProfile(deviceName = "Meu plug"))

        // o arquivo em disco não pode conter o segredo em texto claro
        val raw = File(context.cacheDir, "$name.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("SEGREDO-NAO-VAZAR"))

        firstJob.cancel() // "reinício": o primeiro DataStore deixa de existir
        kotlinx.coroutines.delay(300)
        val reopened = AppPrefs(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { File(context.cacheDir, "$name.preferences_pb") },
            ),
            SecretBox(), Json { ignoreUnknownKeys = true },
        )
        assertEquals("SEGREDO-NAO-VAZAR", reopened.credentials()!!.clientSecret)
        assertEquals("Meu plug", reopened.profile()!!.deviceName)
        assertEquals("", reopened.credentialsSummary()!!.clientSecret)
        assertTrue(reopened.configured.first())
        reopened.clearCredentials()
        assertNull(reopened.credentials())
    }

    @Test fun memoriaDoPlugEDaLocalizacaoPersistem() = runBlocking {
        val p = prefs()
        assertNull(p.expectedOn())
        p.setExpectedOn(true, 5L)
        p.setObservedOn(false)
        assertEquals(true, p.expectedOn())
        assertEquals(false, p.lastObservedOn())
        assertNull(p.knownLocation())
        p.saveKnownLocation(com.smartplug.app.data.prefs.KnownLocation(-23.5, -46.6, 12f, 99L))
        assertEquals(-23.5, p.knownLocation()!!.latitude, 1e-9)
    }

    @Test fun trabalhoPeriodicoEAgendadoNoWorkManagerComRedeObrigatoria() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = WorkScheduler(context, prefs(), clock)
        scheduler.ensurePeriodic()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("engine-periodic").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)

        // chamar de novo (ex.: após reinício) não duplica
        scheduler.ensurePeriodic()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("engine-periodic").get().size)
    }

    @Test fun despertarEEventosImediatosSaoEnfileirados() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = WorkScheduler(context, prefs(), clock)
        scheduler.scheduleWake(System.currentTimeMillis() + 10 * 60_000)
        scheduler.scheduleWake(System.currentTimeMillis() + 20 * 60_000) // REPLACE: continua só um
        scheduler.runSoon(TriggerSource.GEOFENCE, listOf(EngineEvent.GeofenceEnter(3)))
        val wm = WorkManager.getInstance(context)
        val wake = wm.getWorkInfosForUniqueWork("engine-wake").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, wake.size)
        val immediate = wm.getWorkInfosForUniqueWork("engine-immediate").get()
        assertEquals(1, immediate.size)
        scheduler.scheduleWake(null)
        assertTrue(wm.getWorkInfosForUniqueWork("engine-wake").get().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun eventosDeGeofenceViajamNoInputDoWorker() {
        val data = EngineWorker.input(TriggerSource.GEOFENCE, listOf(EngineEvent.GeofenceEnter(3), EngineEvent.GeofenceExit(8)))
        assertEquals("GEOFENCE", data.getString(EngineWorker.KEY_SOURCE))
        assertEquals(listOf("enter:3", "exit:8"), data.getStringArray(EngineWorker.KEY_EVENTS)!!.toList())
    }

    @Test fun aplicativoEstaNaVersaoDeApiEsperadaEComPacoteCorreto() {
        assertEquals("com.smartplug.app", context.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 26)
        assertNotNull(context.packageManager.getApplicationInfo(context.packageName, 0))
    }
}
