package com.suishouban.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIntake(session: IntakeSessionEntity)

    @Query("SELECT * FROM intake_sessions WHERE id=:id")
    suspend fun findIntake(id: String): IntakeSessionEntity?

    @Query(
        "UPDATE intake_sessions SET status=:status, workflow_run_id=:workflowRunId, " +
            "source_uri=CASE WHEN :status IN ('confirmed','ignored','cancelled','failed') " +
            "THEN NULL ELSE source_uri END, updated_at=:updatedAt WHERE id=:id",
    )
    suspend fun updateIntakeStatus(
        id: String,
        status: String,
        workflowRunId: String?,
        updatedAt: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkspace(workspace: TeamWorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<TeamMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAssignments(assignments: List<TeamAssignmentEntity>)
}
