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

    @Query("SELECT * FROM team_members ORDER BY workspace_id, role, display_name")
    fun observeAllMembers(): Flow<List<TeamMemberEntity>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMilestones(milestones: List<TeamMilestoneEntity>)

    @Query("DELETE FROM team_milestones WHERE goal_id=:goalId")
    suspend fun deleteMilestonesOfGoal(goalId: String)

    @Query("DELETE FROM team_milestones WHERE team_id=:teamId")
    suspend fun deleteMilestonesOfTeam(teamId: String)

    @Query("SELECT * FROM team_milestones ORDER BY sort_order")
    fun observeMilestones(): Flow<List<TeamMilestoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTeamSnapshot(snapshot: TeamSyncSnapshotEntity)

    @Query("SELECT * FROM team_sync_snapshots WHERE team_id=:teamId")
    suspend fun findTeamSnapshot(teamId: String): TeamSyncSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingTeamCommand(command: PendingTeamCommandEntity)

    @Query(
        "SELECT * FROM pending_team_commands WHERE status IN ('pending','retry','sending') " +
            "ORDER BY created_at ASC",
    )
    suspend fun loadPendingTeamCommands(): List<PendingTeamCommandEntity>

    @Query("SELECT * FROM pending_team_commands WHERE command_id=:commandId")
    suspend fun findPendingTeamCommand(commandId: String): PendingTeamCommandEntity?

    @Query("SELECT COUNT(*) FROM pending_team_commands WHERE status IN ('pending','retry','sending')")
    fun observePendingTeamCommandCount(): Flow<Int>

    @Query(
        "UPDATE pending_team_commands SET status=:status, retry_count=:retryCount, " +
            "last_error=:lastError, updated_at=:updatedAt WHERE command_id=:commandId",
    )
    suspend fun updatePendingTeamCommandStatus(
        commandId: String,
        status: String,
        retryCount: Int,
        lastError: String?,
        updatedAt: String,
    )

    @Query(
        "UPDATE pending_team_commands SET base_revision=:newRevision, updated_at=:updatedAt " +
            "WHERE team_id=:teamId AND command_id!=:completedCommandId " +
            "AND base_revision=:previousRevision AND status IN ('pending','retry','sending')",
    )
    suspend fun rebasePendingTeamCommands(
        teamId: String,
        completedCommandId: String,
        previousRevision: Long,
        newRevision: Long,
        updatedAt: String,
    )

    @Query("DELETE FROM pending_team_commands WHERE command_id=:commandId")
    suspend fun deletePendingTeamCommand(commandId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTeamConflict(conflict: TeamConflictEntity)

    @Query("SELECT * FROM team_conflicts WHERE status='open' ORDER BY created_at DESC")
    fun observeOpenTeamConflicts(): Flow<List<TeamConflictEntity>>

    @Query("SELECT COUNT(*) FROM team_conflicts WHERE status='open'")
    fun observeOpenTeamConflictCount(): Flow<Int>

    @Query("UPDATE team_conflicts SET status='resolved', resolved_at=:resolvedAt WHERE conflict_id=:conflictId")
    suspend fun resolveTeamConflict(conflictId: String, resolvedAt: String)
}
