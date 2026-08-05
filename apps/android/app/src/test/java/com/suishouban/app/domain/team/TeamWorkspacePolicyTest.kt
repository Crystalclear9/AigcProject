package com.suishouban.app.domain.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamWorkspacePolicyTest {

    private val teamA = listOf(
        TeamMemberOption(teamId = "team-a", userId = "u-1", nickname = "小李"),
        TeamMemberOption(teamId = "team-a", userId = "u-2", nickname = "小王"),
    )
    private val teamB = listOf(
        TeamMemberOption(teamId = "team-b", userId = "u-3", nickname = "阿强"),
    )

    // --- suggestTeam ---

    @Test
    fun `assignee hint matching exactly one team suggests that team`() {
        val suggested = TeamWorkspacePolicy.suggestTeam(
            assigneeHints = listOf("小王", null),
            sourceText = "",
            members = teamA + teamB,
        )
        assertEquals("team-a", suggested)
    }

    @Test
    fun `source text mention of a member nickname suggests the team`() {
        val suggested = TeamWorkspacePolicy.suggestTeam(
            assigneeHints = listOf(null),
            sourceText = "分工公告：阿强负责数据整理，周五前提交",
            members = teamA + teamB,
        )
        assertEquals("team-b", suggested)
    }

    @Test
    fun `matches across two teams tie and yield no suggestion`() {
        val suggested = TeamWorkspacePolicy.suggestTeam(
            assigneeHints = listOf("小李", "阿强"),
            sourceText = "",
            members = teamA + teamB,
        )
        assertNull(suggested)
    }

    @Test
    fun `no match keeps the batch personal`() {
        val suggested = TeamWorkspacePolicy.suggestTeam(
            assigneeHints = listOf("陌生人"),
            sourceText = "普通课程通知，无成员姓名",
            members = teamA + teamB,
        )
        assertNull(suggested)
    }

    @Test
    fun `empty member mirror never suggests`() {
        assertNull(
            TeamWorkspacePolicy.suggestTeam(
                assigneeHints = listOf("小王"),
                sourceText = "小王负责PPT",
                members = emptyList(),
            ),
        )
    }

    @Test
    fun `single character nicknames do not trigger source text noise matches`() {
        val members = listOf(TeamMemberOption("team-a", "u-9", "王"))
        assertNull(
            TeamWorkspacePolicy.suggestTeam(
                assigneeHints = listOf(null),
                sourceText = "王同学的作业已批改",
                members = members,
            ),
        )
    }

    // --- matchAssignee ---

    @Test
    fun `nickname hint resolves to the member user id`() {
        val match = TeamWorkspacePolicy.matchAssignee(" 小王 ", teamA)
        assertEquals("u-2", match?.userId)
    }

    @Test
    fun `already resolved user id also matches`() {
        val match = TeamWorkspacePolicy.matchAssignee("u-1", teamA)
        assertEquals("小李", match?.nickname)
    }

    @Test
    fun `unknown hint stays unresolved`() {
        assertNull(TeamWorkspacePolicy.matchAssignee("小赵", teamA))
        assertNull(TeamWorkspacePolicy.matchAssignee(null, teamA))
        assertNull(TeamWorkspacePolicy.matchAssignee("  ", teamA))
    }

    // --- includeInTodayFocus ---

    @Test
    fun `personal cards always appear in today`() {
        assertTrue(TeamWorkspacePolicy.includeInTodayFocus("personal", null, "u-me"))
        assertTrue(TeamWorkspacePolicy.includeInTodayFocus("personal", "someone", ""))
    }

    @Test
    fun `team card assigned to me appears in today`() {
        assertTrue(TeamWorkspacePolicy.includeInTodayFocus("team", "u-me", "u-me"))
    }

    @Test
    fun `team card assigned to someone else stays out of today`() {
        assertFalse(TeamWorkspacePolicy.includeInTodayFocus("team", "u-other", "u-me"))
    }

    @Test
    fun `unassigned team card stays out of today`() {
        assertFalse(TeamWorkspacePolicy.includeInTodayFocus("team", null, "u-me"))
    }

    @Test
    fun `blank local user id keeps all team cards out of today`() {
        assertFalse(TeamWorkspacePolicy.includeInTodayFocus("team", "u-me", ""))
    }
}
