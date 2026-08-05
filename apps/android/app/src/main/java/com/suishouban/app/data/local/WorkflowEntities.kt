package com.suishouban.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "team_workspaces")
data class TeamWorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "invite_code") val inviteCode: String = "",
    @ColumnInfo(name = "owner_id") val ownerId: String = "",
    @ColumnInfo(name = "my_role") val myRole: String = "member",
    @ColumnInfo(name = "updated_at") val updatedAt: String = "",
)

@Entity(
    tableName = "team_members",
    foreignKeys = [
        ForeignKey(
            entity = TeamWorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspace_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workspace_id")],
)
data class TeamMemberEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val role: String,
    @ColumnInfo(name = "avatar_color") val avatarColor: String = "blue",
)

@Entity(
    tableName = "team_assignments",
    primaryKeys = ["card_id", "member_id"],
    foreignKeys = [
        ForeignKey(
            entity = ActionCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TeamMemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["member_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("card_id"), Index("member_id")],
)
data class TeamAssignmentEntity(
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
    @ColumnInfo(name = "assignment_role") val assignmentRole: String,
    @ColumnInfo(name = "is_owner") val isOwner: Boolean,
)

/**
 * Offline mirror of team goal milestones, refreshed delete-then-insert per goal from the team
 * summary poll. Only the calendar reads it — the server stays the source of truth.
 */
@Entity(tableName = "team_milestones")
data class TeamMilestoneEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "team_id") val teamId: String,
    @ColumnInfo(name = "goal_id") val goalId: String,
    val title: String,
    @ColumnInfo(name = "due_date") val dueDate: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

@Entity(tableName = "intake_sessions")
data class IntakeSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_uri") val sourceUri: String?,
    @ColumnInfo(name = "workspace_type") val workspaceType: String,
    val status: String,
    @ColumnInfo(name = "workflow_run_id") val workflowRunId: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

data class DeviceActionProposal(
    val id: String,
    val cardId: String,
    val type: String,
    val title: String,
    val startTime: String?,
    val endTime: String?,
    val location: String?,
    val description: String,
    val selected: Boolean = false,
)
