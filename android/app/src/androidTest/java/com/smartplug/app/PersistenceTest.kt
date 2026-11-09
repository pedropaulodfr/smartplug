package com.smartplug.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartplug.app.data.db.AppDatabase
import com.smartplug.app.data.repo.AutomationRepository
import com.smartplug.app.data.repo.ExecutionListener
import com.smartplug.app.data.repo.ExecutionRepository
import com.smartplug.app.data.repo.LocationRepository
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.AutomationRuntime
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.InsideLocation
import com.smartplug.app.domain.model.LocationStatus
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.NotConsuming
import com.smartplug.app.domain.model.PlugOn
import com.smartplug.app.domain.model.SavedLocation
import com.smartplug.app.domain.model.TimeAfter
import com.smartplug.app.domain.model.TraceLine
import com.smartplug.app.domain.model.TurnOffAction
import com.smartplug.app.domain.model.TurnOnAction
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room em disco real do aparelho: persistência, edição, exclusão e "reinício" do app. */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true; ignoreUnknownKeys = true }
    private val clock = object : Clock { override fun nowMillis() = 1_000_000L }
    private val dbName = "persistence-test.db"
    private var db: AppDatabase? = null

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName).allowMainThreadQueries().build().also { db = it }

    private fun automations(db: AppDatabase) = AutomationRepository(db, json, clock)

    @Before fun clean() { context.deleteDatabase(dbName) }
    @After fun close() { db?.close(); context.deleteDatabase(dbName) }

    private fun sample(name: String = "Ligar em casa") = Automation(
        name = name,
        description = "teste",
        icon = "home",
        root = ConditionGroup(
            0, LogicOperator.AND,
            listOf(
                ConditionLeaf(0, TimeAfter(960)),
                ConditionGroup(0, LogicOperator.OR, listOf(
                    ConditionLeaf(0, InsideLocation(1)),
                    ConditionLeaf(0, NotConsuming(2.0), holdSeconds = 600),
                )),
            ),
        ),
        action = TurnOnAction,
        minIntervalSeconds = 300,
        repeatEverySeconds = 1800,
    )

    @Test fun criaEleAutomacaoComArvoreDeCondicoes() = runBlocking {
        val repo = automations(open())
        val id = repo.save(sample())
        val loaded = repo.automation(id)!!
        assertEquals("Ligar em casa", loaded.name)
        assertEquals(LogicOperator.AND, loaded.root.operator)
        assertEquals(2, loaded.root.children.size)
        val group = loaded.root.children[1] as ConditionGroup
        assertEquals(LogicOperator.OR, group.operator)
        assertEquals(600, (group.children[1] as ConditionLeaf).holdSeconds)
        assertEquals(TurnOnAction, loaded.action)
        assertEquals(300, loaded.minIntervalSeconds)
        assertEquals(1800, loaded.repeatEverySeconds)
        assertEquals(3, loaded.leaves().size)
    }

    @Test fun editaEExcluiAutomacao() = runBlocking {
        val repo = automations(open())
        val id = repo.save(sample())
        val edited = repo.automation(id)!!.copy(
            name = "Novo nome",
            action = TurnOffAction,
            root = ConditionGroup(0, LogicOperator.OR, listOf(ConditionLeaf(0, PlugOn))),
        )
        repo.save(edited)
        val reloaded = repo.automation(id)!!
        assertEquals("Novo nome", reloaded.name)
        assertEquals(TurnOffAction, reloaded.action)
        assertEquals(1, reloaded.leaves().size)
        assertEquals(1, repo.observeAll().first().size)

        repo.delete(id)
        assertNull(repo.automation(id))
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test fun duplicaCriandoCopiaDesabilitada() = runBlocking {
        val repo = automations(open())
        val id = repo.save(sample())
        val copyId = repo.duplicate(id)!!
        assertTrue(copyId != id)
        val copy = repo.automation(copyId)!!
        assertTrue(copy.name.endsWith("(cópia)"))
        assertFalse(copy.enabled)
        assertEquals(3, copy.leaves().size)
    }

    @Test fun habilitarEDesabilitarFiltraAsAutomacoesDoMotor() = runBlocking {
        val repo = automations(open())
        val id = repo.save(sample())
        assertEquals(1, repo.enabledAutomations().size)
        repo.setEnabled(id, false)
        assertTrue(repo.enabledAutomations().isEmpty())
        repo.setEnabled(id, true)
        assertEquals(1, repo.enabledAutomations().size)
    }

    @Test fun recuperaTudoAposFecharEReabrirOBanco_reinicioDoApp() = runBlocking {
        var database = open()
        var repo = automations(database)
        val locations = LocationRepository(database, FakePrefsHolder.prefsOrNull(), clock)
        val id = repo.save(sample())
        val locationId = locations.save(SavedLocation(0, "Minha casa", -23.5, -46.6, 150f))
        locations.setStatus(locationId, LocationStatus.INSIDE)
        repo.saveRuntime(AutomationRuntime(id, lastResult = true, lastExecutedAtMillis = 123L, holds = mapOf(7L to 999L)))
        ExecutionRepository(database, json, ExecutionListener { }).append(record(id))

        // "reinício": fecha tudo e abre o mesmo arquivo de banco
        database.close()
        database = open()
        repo = automations(database)

        assertEquals(1, repo.enabledAutomations().size)
        val runtime = repo.runtime(id)
        assertEquals(true, runtime.lastResult)          // evita disparar de novo após o boot
        assertEquals(123L, runtime.lastExecutedAtMillis)
        assertEquals(mapOf(7L to 999L), runtime.holds)  // permanência continua contando
        val places = LocationRepository(database, FakePrefsHolder.prefsOrNull(), clock)
        assertEquals(LocationStatus.INSIDE, places.statusOf(locationId))
        assertEquals("Minha casa", places.byId(locationId)!!.name)
        val history = ExecutionRepository(database, json, ExecutionListener { }).observeRecent().first()
        assertEquals(1, history.size)
        assertEquals(ExecutionOutcome.SUCCESS, history[0].outcome)
        assertEquals("Condição X", history[0].trace[0].text)
    }

    @Test fun historicoSobreviveAExclusaoDaAutomacao() = runBlocking {
        val database = open()
        val repo = automations(database)
        val history = ExecutionRepository(database, json, ExecutionListener { })
        val id = repo.save(sample())
        history.append(record(id))
        repo.delete(id)
        assertEquals(1, history.observeRecent().first().size)
        assertNotNull(history.observeLastSuccesses().first()[id])
    }

    @Test fun limiteDeExecucoesPorHoraContaApenasSucessosReais() = runBlocking {
        val database = open()
        val history = ExecutionRepository(database, json, ExecutionListener { })
        history.append(record(5, ts = 10))
        history.append(record(5, ts = 20, test = true))     // teste manual não conta
        history.append(record(5, ts = 30, outcome = ExecutionOutcome.COMM_FAILURE))
        assertEquals(1, history.successCountSince(5, 0))
    }

    private fun record(id: Long, ts: Long = 1000, outcome: ExecutionOutcome = ExecutionOutcome.SUCCESS, test: Boolean = false) =
        ExecutionRecord(
            automationId = id, automationName = "Auto", timestampMillis = ts, outcome = outcome,
            trigger = "PERIODIC", trace = listOf(TraceLine("Condição X", "TRUE")), action = "Ligar Smart Plug",
            stateBefore = false, stateAfter = true, commResult = "ok", message = null, isTest = test,
        )
}

/** LocationRepository só usa AppPrefs em `snapshots()`, que estes testes não chamam. */
private object FakePrefsHolder {
    fun prefsOrNull(): com.smartplug.app.data.prefs.AppPrefs =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.let {
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { File(it.cacheDir, "test-${System.nanoTime()}.preferences_pb") },
            )
            com.smartplug.app.data.prefs.AppPrefs(store, com.smartplug.app.data.prefs.SecretBox(), Json)
        }
}
