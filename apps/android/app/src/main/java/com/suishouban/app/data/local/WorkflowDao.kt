package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Row projection so the team list can show member counts without loading every member. */
data class WorkspaceMemberCount(
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "member_count") val memberCount: Int,
)

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

    @Query("SELECT * FROM team_workspaces ORDER BY created_at DESC")
    fun observeWorkspaces(): Flow<List<TeamWorkspaceEntity>>

    @Query("SELECT * FROM team_members WHERE workspace_id=:workspaceId ORDER BY role, display_name")
    fun observeMembers(workspaceId: String): Flow<List<TeamMemberEntity>>

    @Query(
        "SELECT workspace_id, COUNT(*) AS member_count FROM team_members GROUP BY workspace_id",
    )
    fun observeMemberCounts(): Flow<List<WorkspaceMemberCount>>

    @Query("SELECT id FROM team_workspaces")
    suspend fun loadWorkspaceIds(): List<String>

    @Query("DELETE FROM team_workspaces WHERE id=:id")
    suspend fun deleteWorkspace(id: String)

    @Query("DELETE FROM team_members WHERE workspace_id=:workspaceId")
    suspend fun deleteMembersOfWorkspace(workspaceId: String)
}
