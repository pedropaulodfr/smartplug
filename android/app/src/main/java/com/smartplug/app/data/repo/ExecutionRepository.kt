package com.smartplug.app.data.repo

import com.smartplug.app.data.db.AppDatabase
import com.smartplug.app.data.db.AutomationExecutionEntity
import com.smartplug.app.domain.gateway.ExecutionLog
import com.smartplug.app.domain.model.ExecutionOutcome
import com.smartplug.app.domain.model.ExecutionRecord
import com.smartplug.app.domain.model.TraceLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Chamado após gravar um registro (ex.: para notificar o usuário). */
fun interface ExecutionListener {
    suspend fun onRecorded(record: ExecutionRecord)
}

@Singleton
class ExecutionRepository @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
    private val listener: ExecutionListener,
) : ExecutionLog {

    private val dao get() = db.executions()
    private val traceSerializer = ListSerializer(TraceLine.serializer())

    override suspend fun append(record: ExecutionRecord) {
        dao.insert(record.toEntity())
        dao.trim(KEEP)
        runCatching { listener.onRecorded(record) }
    }

    override suspend fun successCountSince(automationId: Long, sinceMillis: Long): Int =
        dao.successCountSince(automationId, sinceMillis)

    fun observeRecent(limit: Int = 300): Flow<List<ExecutionRecord>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeLastSuccesses(): Flow<Map<Long, Long>> =
        dao.observeLastSuccesses().map { list -> list.associate { it.automationId to it.at } }

    suspend fun clear() = dao.clear()

    private fun ExecutionRecord.toEntity() = AutomationExecutionEntity(
        automationId = automationId,
        automationName = automationName,
        timestamp = timestampMillis,
        outcome = outcome.name,
        trigger = trigger,
        traceJson = json.encodeToString(traceSerializer, trace),
        action = action,
        stateBefore = stateBefore,
        stateAfter = stateAfter,
        commResult = commResult,
        message = message,
        isTest = isTest,
    )

    private fun AutomationExecutionEntity.toDomain() = ExecutionRecord(
        id = id,
        automationId = automationId,
        automationName = automationName,
        timestampMillis = timestamp,
        outcome = runCatching { ExecutionOutcome.valueOf(outcome) }.getOrDefault(ExecutionOutcome.COMM_FAILURE),
        trigger = trigger,
        trace = runCatching { json.decodeFromString(traceSerializer, traceJson) }.getOrDefault(emptyList()),
        action = action,
        stateBefore = stateBefore,
        stateAfter = stateAfter,
        commResult = commResult,
        message = message,
        isTest = isTest,
    )

    private companion object {
        const val KEEP = 500
    }
}
