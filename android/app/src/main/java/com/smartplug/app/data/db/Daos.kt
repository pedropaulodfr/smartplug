package com.smartplug.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY createdAt DESC, id DESC")
    fun observeAutomations(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automation_conditions ORDER BY automationId, position")
    fun observeConditions(): Flow<List<AutomationConditionEntity>>

    @Query("SELECT * FROM automation_actions ORDER BY automationId, position")
    fun observeActions(): Flow<List<AutomationActionEntity>>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun automation(id: Long): AutomationEntity?

    @Query("SELECT * FROM automations WHERE enabled = 1")
    suspend fun enabledAutomations(): List<AutomationEntity>

    @Query("SELECT * FROM automation_conditions WHERE automationId = :id ORDER BY position")
    suspend fun conditions(id: Long): List<AutomationConditionEntity>

    @Query("SELECT * FROM automation_actions WHERE automationId = :id ORDER BY position")
    suspend fun actions(id: Long): List<AutomationActionEntity>

    @Insert suspend fun insert(entity: AutomationEntity): Long
    @Update suspend fun update(entity: AutomationEntity)
    @Insert suspend fun insertCondition(entity: AutomationConditionEntity): Long
    @Insert suspend fun insertAction(entity: AutomationActionEntity): Long

    @Query("DELETE FROM automation_conditions WHERE automationId = :id")
    suspend fun deleteConditions(id: Long)

    @Query("DELETE FROM automation_actions WHERE automationId = :id")
    suspend fun deleteActions(id: Long)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE automations SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("SELECT * FROM automation_runtime WHERE automationId = :id")
    suspend fun runtime(id: Long): AutomationRuntimeEntity?

    @Query("SELECT * FROM automation_runtime")
    fun observeRuntimes(): Flow<List<AutomationRuntimeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRuntime(entity: AutomationRuntimeEntity)

    @Query("DELETE FROM automation_runtime WHERE automationId = :id")
    suspend fun deleteRuntime(id: Long)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<AutomationLocationEntity>>

    @Query("SELECT * FROM locations")
    suspend fun all(): List<AutomationLocationEntity>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun byId(id: Long): AutomationLocationEntity?

    @Insert suspend fun insert(entity: AutomationLocationEntity): Long
    @Update suspend fun update(entity: AutomationLocationEntity)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM location_state")
    suspend fun states(): List<LocationStateEntity>

    @Query("SELECT * FROM location_state")
    fun observeStates(): Flow<List<LocationStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(entity: LocationStateEntity)

    @Query("DELETE FROM location_state WHERE locationId = :id")
    suspend fun deleteState(id: Long)
}

@Dao
interface ExecutionDao {
    @Query("SELECT * FROM automation_executions ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutomationExecutionEntity>>

    @Query("SELECT * FROM automation_executions WHERE id = :id")
    suspend fun byId(id: Long): AutomationExecutionEntity?

    @Query(
        "SELECT MAX(timestamp) FROM automation_executions " +
            "WHERE automationId = :automationId AND outcome = 'SUCCESS'",
    )
    fun observeLastSuccess(automationId: Long): Flow<Long?>

    @Query(
        "SELECT automationId, MAX(timestamp) AS at FROM automation_executions " +
            "WHERE outcome = 'SUCCESS' AND automationId IS NOT NULL GROUP BY automationId",
    )
    fun observeLastSuccesses(): Flow<List<LastSuccess>>

    @Query(
        "SELECT COUNT(*) FROM automation_executions WHERE automationId = :automationId " +
            "AND outcome = 'SUCCESS' AND isTest = 0 AND timestamp >= :since",
    )
    suspend fun successCountSince(automationId: Long, since: Long): Int

    @Insert suspend fun insert(entity: AutomationExecutionEntity): Long

    @Query("DELETE FROM automation_executions")
    suspend fun clear()

    /** Mantém o histórico limitado. */
    @Query(
        "DELETE FROM automation_executions WHERE id NOT IN " +
            "(SELECT id FROM automation_executions ORDER BY timestamp DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

data class LastSuccess(val automationId: Long, val at: Long)

@Database(
    entities = [
        AutomationEntity::class,
        AutomationConditionEntity::class,
        AutomationActionEntity::class,
        AutomationLocationEntity::class,
        LocationStateEntity::class,
        AutomationRuntimeEntity::class,
        AutomationExecutionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automations(): AutomationDao
    abstract fun locations(): LocationDao
    abstract fun executions(): ExecutionDao
}
