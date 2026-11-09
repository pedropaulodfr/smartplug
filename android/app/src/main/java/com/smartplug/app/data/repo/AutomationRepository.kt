package com.smartplug.app.data.repo

import androidx.room.withTransaction
import com.smartplug.app.data.db.AppDatabase
import com.smartplug.app.data.db.AutomationActionEntity
import com.smartplug.app.data.db.AutomationConditionEntity
import com.smartplug.app.data.db.AutomationEntity
import com.smartplug.app.data.db.AutomationRuntimeEntity
import com.smartplug.app.domain.gateway.AutomationStore
import com.smartplug.app.domain.gateway.Clock
import com.smartplug.app.domain.model.ActionSpec
import com.smartplug.app.domain.model.Automation
import com.smartplug.app.domain.model.AutomationRuntime
import com.smartplug.app.domain.model.Condition
import com.smartplug.app.domain.model.ConditionGroup
import com.smartplug.app.domain.model.ConditionLeaf
import com.smartplug.app.domain.model.ConditionNode
import com.smartplug.app.domain.model.LogicOperator
import com.smartplug.app.domain.model.TurnOnAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Singleton
class AutomationRepository @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
    private val clock: Clock,
) : AutomationStore {

    private val dao get() = db.automations()
    private val holdsSerializer = MapSerializer(Long.serializer(), Long.serializer())

    // -------------------------------------------------------------------- leitura
    fun observeAll(): Flow<List<Automation>> =
        combine(dao.observeAutomations(), dao.observeConditions(), dao.observeActions()) { autos, conds, actions ->
            val condsByAuto = conds.groupBy { it.automationId }
            val actionsByAuto = actions.groupBy { it.automationId }
            autos.map { assemble(it, condsByAuto[it.id].orEmpty(), actionsByAuto[it.id].orEmpty()) }
        }

    fun observeRuntimes(): Flow<Map<Long, AutomationRuntime>> =
        dao.observeRuntimes().map { list -> list.associate { it.automationId to it.toDomain() } }

    override suspend fun enabledAutomations(): List<Automation> =
        dao.enabledAutomations().map { load(it) }

    override suspend fun automation(id: Long): Automation? = dao.automation(id)?.let { load(it) }

    private suspend fun load(entity: AutomationEntity) =
        assemble(entity, dao.conditions(entity.id), dao.actions(entity.id))

    override suspend fun runtime(automationId: Long): AutomationRuntime =
        dao.runtime(automationId)?.toDomain() ?: AutomationRuntime(automationId)

    override suspend fun saveRuntime(runtime: AutomationRuntime) {
        dao.saveRuntime(
            AutomationRuntimeEntity(
                automationId = runtime.automationId,
                lastResult = runtime.lastResult,
                lastExecutedAt = runtime.lastExecutedAtMillis,
                holdsJson = json.encodeToString(holdsSerializer, runtime.holds),
            ),
        )
    }

    private fun AutomationRuntimeEntity.toDomain() = AutomationRuntime(
        automationId = automationId,
        lastResult = lastResult,
        lastExecutedAtMillis = lastExecutedAt,
        holds = runCatching { json.decodeFromString(holdsSerializer, holdsJson) }.getOrDefault(emptyMap()),
    )

    // ------------------------------------------------------------------ escrita
    /** Cria ou atualiza. O estado de execução é zerado para que a nova regra recomece da linha de base. */
    suspend fun save(automation: Automation): Long = db.withTransaction {
        val now = clock.nowMillis()
        val id = if (automation.id == 0L) {
            dao.insert(automation.toEntity(createdAt = now, updatedAt = now))
        } else {
            val existing = dao.automation(automation.id)
            dao.update(automation.toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now))
            dao.deleteConditions(automation.id)
            dao.deleteActions(automation.id)
            automation.id
        }
        insertNode(id, automation.root, parentId = null, position = 0)
        dao.insertAction(
            AutomationActionEntity(
                automationId = id,
                position = 0,
                specJson = json.encodeToString(ActionSpec.serializer(), automation.action),
            ),
        )
        dao.deleteRuntime(id)
        id
    }

    private suspend fun insertNode(automationId: Long, node: ConditionNode, parentId: Long?, position: Int) {
        when (node) {
            is ConditionLeaf -> dao.insertCondition(
                AutomationConditionEntity(
                    automationId = automationId,
                    parentId = parentId,
                    position = position,
                    isGroup = false,
                    operator = null,
                    conditionJson = json.encodeToString(Condition.serializer(), node.condition),
                    holdSeconds = node.holdSeconds,
                ),
            )
            is ConditionGroup -> {
                val groupId = dao.insertCondition(
                    AutomationConditionEntity(
                        automationId = automationId,
                        parentId = parentId,
                        position = position,
                        isGroup = true,
                        operator = node.operator.name,
                        conditionJson = null,
                        holdSeconds = 0,
                    ),
                )
                node.children.forEachIndexed { i, child -> insertNode(automationId, child, groupId, i) }
            }
        }
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        dao.deleteRuntime(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = db.withTransaction {
        dao.setEnabled(id, enabled, clock.nowMillis())
        dao.deleteRuntime(id) // reabilitar recomeça da linha de base
    }

    suspend fun duplicate(id: Long): Long? {
        val original = automation(id) ?: return null
        return save(
            original.copy(
                id = 0,
                name = original.name + " (cópia)",
                enabled = false,
                root = original.root.stripIds(),
            ),
        )
    }

    // ----------------------------------------------------------------- mapeamento
    private fun ConditionGroup.stripIds(): ConditionGroup = copy(
        id = 0,
        children = children.map {
            when (it) {
                is ConditionLeaf -> it.copy(id = 0)
                is ConditionGroup -> it.stripIds()
            }
        },
    )

    private fun Automation.toEntity(createdAt: Long, updatedAt: Long) = AutomationEntity(
        id = id,
        name = name,
        description = description,
        icon = icon,
        enabled = enabled,
        minIntervalSeconds = minIntervalSeconds,
        repeatEverySeconds = repeatEverySeconds,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun assemble(
        entity: AutomationEntity,
        rows: List<AutomationConditionEntity>,
        actions: List<AutomationActionEntity>,
    ): Automation {
        val byParent = rows.groupBy { it.parentId }
        fun build(row: AutomationConditionEntity): ConditionNode? =
            if (row.isGroup) {
                ConditionGroup(
                    id = row.id,
                    operator = runCatching { LogicOperator.valueOf(row.operator.orEmpty()) }.getOrDefault(LogicOperator.AND),
                    children = byParent[row.id].orEmpty().sortedBy { it.position }.mapNotNull { build(it) },
                )
            } else {
                val condition = row.conditionJson?.let {
                    runCatching { json.decodeFromString(Condition.serializer(), it) }.getOrNull()
                }
                condition?.let { ConditionLeaf(row.id, it, row.holdSeconds) }
            }

        val rootRow = byParent[null].orEmpty().minByOrNull { it.position }
        val root = (rootRow?.let { build(it) } as? ConditionGroup)
            ?: ConditionGroup(0, LogicOperator.AND, emptyList())
        val action = actions.minByOrNull { it.position }?.let {
            runCatching { json.decodeFromString(ActionSpec.serializer(), it.specJson) }.getOrNull()
        } ?: TurnOnAction

        return Automation(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            icon = entity.icon,
            enabled = entity.enabled,
            root = root,
            action = action,
            minIntervalSeconds = entity.minIntervalSeconds,
            repeatEverySeconds = entity.repeatEverySeconds,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}
