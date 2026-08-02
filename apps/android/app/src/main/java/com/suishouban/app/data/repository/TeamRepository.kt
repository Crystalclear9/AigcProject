package com.suishouban.app.data.repository

import com.suishouban.app.data.local.TeamMemberEntity
import com.suishouban.app.data.local.TeamWorkspaceEntity
import com.suishouban.app.data.local.WorkflowDao
import com.suishouban.app.data.model.AiConnectionMode
import com.suishouban.app.data.model.ProposedTeamTask
import com.suishouban.app.data.model.TeamDetailSummary
import com.suishouban.app.data.model.TeamGoalInfo
import com.suishouban.app.data.model.TeamGoalPlan
import com.suishouban.app.data.remote.ApiFactory
import com.suishouban.app.data.remote.GoalConfirmRequestDto
import com.suishouban.app.data.remote.SuiShouBanApi
import com.suishouban.app.data.remote.TeamCreateRequestDto
import com.suishouban.app.data.remote.TeamDto
import com.suishouban.app.data.remote.TeamGoalCreateRequestDto
import com.suishouban.app.data.remote.TeamJoinRequestDto
import com.suishouban.app.data.remote.TeamRenameRequestDto
import com.suishouban.app.data.remote.UserRegisterRequestDto
import com.suishouban.app.data.remote.toDomain
import com.suishouban.app.data.remote.toDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Server-backed team collaboration. The workflow gateway is the source of truth; Room only keeps
 * an offline snapshot so the team list stays readable without a connection. Every remote failure
 * is surfaced as a [Result] — never an uncaught exception.
 */
class TeamRepository(
    private val dao: WorkflowDao,
    private val settingsRepository: AppSettingsRepository,
    private val cardRepository: ActionCardRepository,
) {
    // Goals and progress are session snapshots, not Room tables — the server owns them; only
    // team task cards and membership are mirrored locally for offline reads.
    private val _currentSummary = MutableStateFlow<TeamDetailSummary?>(null)
    val currentSummary: StateFlow<TeamDetailSummary?> = _currentSummary
    private var activeTeamId: String? = null

    fun observeTeams(): Flow<List<TeamWorkspaceEntity>> = dao.observeWorkspaces()

    fun observeMembers(workspaceId: String): Flow<List<TeamMemberEntity>> =
        dao.observeMembers(workspaceId)

    /**
     * Idempotent identity upsert. Registration is best-effort on purpose: a failure here will
     * resurface as an explicit error on the next create/join call.
     */
    suspend fun ensureRegistered() {
        val settings = settingsRepository.settings.value
        if (settings.localUserId.isBlank() || settings.userNickname.isBlank()) return
        val api = remoteApiOrNull() ?: return
        runCatching {
            api.registerUser(
                UserRegisterRequestDto(id = settings.localUserId, nickname = settings.userNickname),
            )
        }
    }

    /**
     * Pulls the membership list and mirrors it into Room, dropping local workspaces the server no
     * longer returns. Quietly succeeds when no gateway is configured so offline use stays silent.
     */
    suspend fun refreshTeams(): Result<Unit> {
        val api = remoteApiOrNull() ?: return Result.success(Unit)
        return runCatching {
            val teams = api.listTeams(localUserId())
            val remoteIds = teams.map { it.id }.toSet()
            dao.loadWorkspaceIds()
                .filterNot { it in remoteIds }
                .forEach { dao.deleteWorkspace(it) }
            teams.forEach { mirrorTeam(it) }
        }
    }

    suspend fun createTeam(name: String): Result<TeamWorkspaceEntity> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            ensureRegistered()
            mirrorTeam(api.createTeam(localUserId(), TeamCreateRequestDto(name = name)))
        }
    }

    suspend fun joinTeam(code: String): Result<TeamWorkspaceEntity> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            ensureRegistered()
            mirrorTeam(api.joinTeam(localUserId(), TeamJoinRequestDto(inviteCode = code)))
        }
    }

    suspend fun renameTeam(teamId: String, name: String): Result<Unit> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            mirrorTeam(api.renameTeam(localUserId(), teamId, TeamRenameRequestDto(name = name)))
            _currentSummary.value?.let { summary ->
                if (summary.teamId == teamId) _currentSummary.value = summary.copy(teamName = name)
            }
            Unit
        }
    }

    suspend fun dissolveTeam(teamId: String): Result<Unit> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            api.deleteTeam(localUserId(), teamId)
            dao.deleteWorkspace(teamId)
            if (activeTeamId == teamId) setActiveTeam(null)
        }
    }

    /** Persists the goal + milestones server-side and returns the AI task preview (no cards yet). */
    suspend fun createGoal(teamId: String, title: String, dueDate: String?): Result<TeamGoalPlan> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            api.createTeamGoal(
                localUserId(),
                teamId,
                TeamGoalCreateRequestDto(title = title, dueDate = dueDate),
            ).toDomain()
        }
    }

    /** Confirms the (possibly edited) task list; created team cards go straight into Room. */
    suspend fun confirmGoal(
        teamId: String,
        goalId: String,
        tasks: List<ProposedTeamTask>,
    ): Result<TeamGoalInfo> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            val response = api.confirmTeamGoal(
                localUserId(),
                teamId,
                goalId,
                GoalConfirmRequestDto(tasks = tasks.map { it.toDto() }),
            )
            cardRepository.upsertServerCards(response.cards.map { it.toDomain() })
            response.goal.toDomain()
        }
    }

    /** Switching teams (or leaving the detail screen) drops the previous snapshot. */
    fun setActiveTeam(teamId: String?) {
        if (activeTeamId != teamId) {
            activeTeamId = teamId
            _currentSummary.value = null
        }
    }

    /**
     * Pulls the team summary. Changed cards are upserted into Room via the same path as full card
     * sync; membership is mirrored so member chips stay readable offline. Pass the previous
     * response's serverTime as [since] for incremental changed_cards.
     */
    suspend fun fetchSummary(teamId: String, since: String?): Result<TeamDetailSummary> {
        val api = remoteApiOrNull()
            ?: return Result.failure(IllegalStateException(GATEWAY_UNAVAILABLE_MESSAGE))
        return runCatching {
            val dto = api.teamSummary(localUserId(), teamId, since)
            mirrorTeam(dto.team)
            cardRepository.upsertServerCards(dto.changedCards.map { it.toDomain() })
            dto.toDomain().also { summary ->
                if (activeTeamId == teamId) _currentSummary.value = summary
            }
        }
    }

    private suspend fun mirrorTeam(team: TeamDto): TeamWorkspaceEntity {
        val myUserId = localUserId()
        val entity = TeamWorkspaceEntity(
            id = team.id,
            name = team.name,
            createdAt = team.createdAt,
            inviteCode = team.inviteCode,
            ownerId = team.ownerId,
            myRole = team.members.firstOrNull { it.userId == myUserId }?.role
                ?: if (team.ownerId == myUserId) "owner" else "member",
            updatedAt = team.updatedAt,
        )
        dao.upsertWorkspace(entity)
        // Replace instead of merge so members removed on the server disappear locally too.
        dao.deleteMembersOfWorkspace(team.id)
        dao.upsertMembers(
            team.members.map { member ->
                TeamMemberEntity(
                    // Member rows are scoped per workspace; the same user may join several teams.
                    id = "${team.id}:${member.userId}",
                    workspaceId = team.id,
                    displayName = member.nickname,
                    role = member.role,
                    avatarColor = member.avatarColor,
                )
            },
        )
        return entity
    }

    private fun localUserId(): String =
        settingsRepository.settings.value.localUserId

    private fun remoteApiOrNull(settings: AppSettings = settingsRepository.settings.value): SuiShouBanApi? {
        if (settings.aiConnectionMode != AiConnectionMode.WORKFLOW_GATEWAY) return null
        val baseUrl = WorkflowUrlPolicy.normalize(settings.apiBaseUrl) ?: return null
        return ApiFactory.create(baseUrl)
    }

    companion object {
        const val GATEWAY_UNAVAILABLE_MESSAGE = "网络不可用，请检查设置中的服务器地址"
    }
}
