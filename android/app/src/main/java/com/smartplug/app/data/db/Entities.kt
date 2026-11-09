package com.smartplug.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val icon: String,
    val enabled: Boolean,
    val minIntervalSeconds: Int,
    val repeatEverySeconds: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Nó da árvore de condições (grupo AND/OR ou condição folha). */
@Entity(
    tableName = "automation_conditions",
    foreignKeys = [ForeignKey(entity = AutomationEntity::class, parentColumns = ["id"], childColumns = ["automationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("automationId")],
)
data class AutomationConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: Long,
    val parentId: Long?,
    val position: Int,
    val isGroup: Boolean,
    /** "AND" | "OR" quando é grupo. */
    val operator: String?,
    /** JSON polimórfico da [com.smartplug.app.domain.model.Condition] quando é folha. */
    val conditionJson: String?,
    val holdSeconds: Int,
)

@Entity(
    tableName = "automation_actions",
    foreignKeys = [ForeignKey(entity = AutomationEntity::class, parentColumns = ["id"], childColumns = ["automationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("automationId")],
)
data class AutomationActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: Long,
    val position: Int,
    val specJson: String,
)

@Entity(tableName = "locations")
data class AutomationLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

@Entity(tableName = "location_state")
data class LocationStateEntity(
    @PrimaryKey val locationId: Long,
    /** INSIDE | OUTSIDE | UNKNOWN */
    val status: String,
    val updatedAt: Long,
)

/** Estado de execução persistido por automação (sobrevive a reinício e atualização do app). */
@Entity(tableName = "automation_runtime")
data class AutomationRuntimeEntity(
    @PrimaryKey val automationId: Long,
    val lastResult: Boolean?,
    val lastExecutedAt: Long?,
    val holdsJson: String,
)

/** Histórico. Sem chave estrangeira: o registro sobrevive à exclusão da automação. */
@Entity(tableName = "automation_executions", indices = [Index("automationId"), Index("timestamp")])
data class AutomationExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: Long?,
    val automationName: String,
    val timestamp: Long,
    val outcome: String,
    val trigger: String,
    val traceJson: String,
    val action: String?,
    val stateBefore: Boolean?,
    val stateAfter: Boolean?,
    val commResult: String?,
    val message: String?,
    val isTest: Boolean,
)
