package com.suishouban.app.domain.team

/** One selectable team member, flattened from the Room mirror (`team_members` rows). */
data class TeamMemberOption(
    val teamId: String,
    val userId: String,
    val nickname: String,
)

/**
 * Pure decisions shared by the draft-confirm surfaces and the 今日 view. Kept free of Android
 * types so the matching and filtering rules stay JVM-testable.
 */
object TeamWorkspacePolicy {

    /**
     * Suggests the batch workspace for a set of freshly analyzed drafts.
     *
     * A team qualifies when any draft assignee hint equals a member nickname, or the source
     * text mentions a member nickname (2+ chars, to avoid single-char noise). The suggestion is
     * only offered when exactly ONE local team qualifies; ties and no-matches return null so the
     * batch stays 个人 by default.
     */
    fun suggestTeam(
        assigneeHints: List<String?>,
        sourceText: String,
        members: List<TeamMemberOption>,
    ): String? {
        if (members.isEmpty()) return null
        val hints = assigneeHints.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        val matchedTeams = members
            .filter { member ->
                val nickname = member.nickname.trim()
                if (nickname.isEmpty()) return@filter false
                hints.any { it == nickname || it == member.userId } ||
                    (nickname.length >= 2 && sourceText.contains(nickname))
            }
            .map { it.teamId }
            .toSet()
        return matchedTeams.singleOrNull()
    }

    /**
     * Resolves a plain-name assignee hint against one team's members. Matches nickname (trimmed)
     * or an already-resolved user id; unknown hints return null and are kept verbatim upstream —
     * the server performs the authoritative resolution on POST/PATCH.
     */
    fun matchAssignee(hint: String?, members: List<TeamMemberOption>): TeamMemberOption? {
        val needle = hint?.trim()?.takeIf(String::isNotBlank) ?: return null
        return members.firstOrNull { it.nickname.trim() == needle || it.userId == needle }
    }

    /**
     * 今日/行动状态 filter: personal cards always count; team cards only when assigned to ME.
     * Teammates' tasks and unassigned team tasks stay in team detail and the 团队 filter.
     */
    fun includeInTodayFocus(workspaceType: String, assigneeId: String?, myUserId: String): Boolean {
        if (workspaceType != "team") return true
        return myUserId.isNotBlank() && assigneeId == myUserId
    }
}
