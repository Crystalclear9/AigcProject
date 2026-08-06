package com.suishouban.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeamCommandQueueTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: WorkflowDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workflowDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sendingCommandsSurviveReloadInCreationOrderAndCanBeRebased() = runBlocking {
        val commands = listOf(
            pending("first", "1", status = "sending"),
            pending("second", "2"),
            pending("third", "3"),
        )
        commands.forEach { dao.upsertPendingTeamCommand(it) }

        assertEquals(listOf("first", "second", "third"), dao.loadPendingTeamCommands().map { it.commandId })

        dao.rebasePendingTeamCommands(
            teamId = "team-1",
            completedCommandId = "first",
            previousRevision = 4,
            newRevision = 5,
            updatedAt = "4",
        )
        val rebased = dao.loadPendingTeamCommands()
        assertEquals(4, rebased.first { it.commandId == "first" }.baseRevision)
        assertEquals(5, rebased.first { it.commandId == "second" }.baseRevision)
        assertEquals(5, rebased.first { it.commandId == "third" }.baseRevision)
    }

    @Test
    fun revisionConflictIsPersistedWithoutDeletingPendingCommand() = runBlocking {
        dao.upsertPendingTeamCommand(pending("conflicted", "1"))
        dao.updatePendingTeamCommandStatus("conflicted", "conflicted", 1, "HTTP 409", "2")
        dao.upsertTeamConflict(
            TeamConflictEntity(
                conflictId = "conflicted",
                teamId = "team-1",
                taskId = "task-1",
                localPayload = "{\"title\":\"local\"}",
                serverPayload = "{\"title\":\"server\"}",
                baseRevision = 4,
                conflictType = "revision_conflict",
                createdAt = "2",
            ),
        )

        assertTrue(dao.loadPendingTeamCommands().isEmpty())
        assertEquals("conflicted", dao.findPendingTeamCommand("conflicted")?.status)
        assertEquals(1, dao.observeOpenTeamConflicts().first().size)
    }

    private fun pending(commandId: String, createdAt: String, status: String = "pending") =
        PendingTeamCommandEntity(
            commandId = commandId,
            teamId = "team-1",
            taskId = "task-1",
            operation = "update_task",
            payload = "{}",
            baseRevision = 4,
            idempotencyKey = "idempotency-$commandId",
            status = status,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
