package com.suishouban.app.data.model

/**
 * Team goal collaboration domain models. The server is the source of truth for goals, milestones,
 * and progress; these are session snapshots and are never written to Room. Team task cards
 * themselves flow through the ordinary [ActionCard] path.
 */

data class TeamMilestone(
    val id: String,
    val title: String,
    val dueDate: String? = null,
    val sortOrder: Int = 0,
)

data class TeamGoalInfo(
    val id: String,
    val title: String,
    val description: String = "",
    val dueDate: String? = null,
    val status: String = "active",
    val decomposeSource: String = "template",
    val createdAt: String = "",
    val updatedAt: String = "",
    val milestones: List<TeamMilestone> = emptyList(),
)

/** One AI-proposed team task, editable in the preview step before the goal is confirmed. */
data class ProposedTeamTask(
    val title: String,
    val summary: String = "",
    val assigneeId: String? = null,
    val milestoneId: String? = null,
    val startTime: String? = null,
    val deadline: String? = null,
    val deliverables: List<String> = emptyList(),
)

data class TeamGoalPlan(
    val goal: TeamGoalInfo,
    val tasks: List<ProposedTeamTask> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Prefill extracted from a screenshot for the goal-publish flow: first card title + due date. */
data class GoalSeed(
    val title: String,
    val dueDate: String? = null,
)

data class TeamMilestoneProgress(
    val milestone: TeamMilestone,
    val done: Int = 0,
    val total: Int = 0,
)

data class TeamGoalProgress(
    val goal: TeamGoalInfo,
    val done: Int = 0,
    val total: Int = 0,
    val milestones: List<TeamMilestoneProgress> = emptyList(),
)

data class TeamMemberInfo(
    val userId: String,
    val nickname: String,
    val avatarColor: String = "blue",
    val role: String = "member",
)

data class TeamMemberStat(
    val userId: String,
    val nickname: String,
    val avatarColor: String = "blue",
    val role: String = "member",
    val done: Int = 0,
    val total: Int = 0,
)

data class TeamDetailSummary(
    val teamId: String,
    val teamName: String,
    val inviteCode: String = "",
    val ownerId: String = "",
    val members: List<TeamMemberInfo> = emptyList(),
    val goals: List<TeamGoalProgress> = emptyList(),
    val memberStats: List<TeamMemberStat> = emptyList(),
    val serverTime: String = "",
)
