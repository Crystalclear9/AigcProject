package com.suishouban.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActionCardEntity::class,
        NotificationCandidateEntity::class,
        ActionPlanEntity::class,
        PlanItemEntity::class,
        CardAttachmentEntity::class,
        CardRefinementPreferenceEntity::class,
        UserProfileEntity::class,
        ProfileSignalStatEntity::class,
        TeamWorkspaceEntity::class,
        TeamMemberEntity::class,
        TeamAssignmentEntity::class,
        IntakeSessionEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class, ReminderNodeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): ActionCardDao
    abstract fun notificationCandidateDao(): NotificationCandidateDao
    abstract fun cardRefinementDao(): CardRefinementDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "suishouban.db"
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN action_id TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN dependencies TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE cards ADD COLUMN evidence_summary TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Adds a separate local-only queue; existing cards remain untouched. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notification_candidates (
                        id TEXT NOT NULL,
                        notification_key TEXT NOT NULL,
                        package_name TEXT NOT NULL,
                        app_label TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        posted_at_millis INTEGER NOT NULL,
                        content_hash TEXT NOT NULL,
                        expires_at_millis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_notification_candidates_content_hash " +
                        "ON notification_candidates(content_hash)",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS action_plans (
                        id TEXT NOT NULL,
                        parent_card_id TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        objective TEXT NOT NULL,
                        assumptions TEXT NOT NULL,
                        evidence_summary TEXT NOT NULL,
                        warnings TEXT NOT NULL,
                        generated_by TEXT NOT NULL,
                        profile_version INTEGER,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(parent_card_id) REFERENCES cards(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_action_plans_parent_card_id " +
                        "ON action_plans(parent_card_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plan_items (
                        id TEXT NOT NULL,
                        plan_id TEXT NOT NULL,
                        parent_id TEXT,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        start_time TEXT,
                        deadline TEXT,
                        estimated_minutes INTEGER,
                        importance TEXT NOT NULL,
                        dependencies TEXT NOT NULL,
                        reminder_enabled INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        evidence_refs TEXT NOT NULL,
                        need_confirm TEXT NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(plan_id) REFERENCES action_plans(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_plan_items_plan_id ON plan_items(plan_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_plan_items_parent_id ON plan_items(parent_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS card_attachments (
                        id TEXT NOT NULL,
                        card_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        extraction_status TEXT NOT NULL,
                        warning TEXT,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(card_id) REFERENCES cards(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_card_attachments_card_id " +
                        "ON card_attachments(card_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS card_refinement_preferences (
                        card_id TEXT NOT NULL,
                        refinement_enabled INTEGER,
                        use_profile INTEGER,
                        include_work_blocks INTEGER,
                        milestone_reminders INTEGER,
                        granularity TEXT,
                        PRIMARY KEY(card_id),
                        FOREIGN KEY(card_id) REFERENCES cards(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_profiles (
                        id TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        scenario TEXT NOT NULL,
                        active_period TEXT NOT NULL,
                        planning_granularity TEXT NOT NULL,
                        reminder_style TEXT NOT NULL,
                        work_rhythm TEXT NOT NULL,
                        timezone TEXT NOT NULL,
                        questionnaire_completed INTEGER NOT NULL,
                        learning_consent INTEGER NOT NULL,
                        explicit_fields TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profile_signal_stats (
                        metric TEXT NOT NULL,
                        option_value TEXT NOT NULL,
                        count INTEGER NOT NULL,
                        score REAL NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(metric, option_value)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN " +
                        "buffer_preference TEXT NOT NULL DEFAULT 'standard'",
                )
                db.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN " +
                        "weekend_policy TEXT NOT NULL DEFAULT 'flexible'",
                )
                db.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN " +
                        "assistant_tone TEXT NOT NULL DEFAULT 'warm'",
                )
                db.execSQL(
                    "ALTER TABLE user_profiles ADD COLUMN " +
                        "questionnaire_version INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE action_plans ADD COLUMN " +
                        "quality_score REAL NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE action_plans ADD COLUMN " +
                        "constraint_errors TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE action_plans ADD COLUMN " +
                        "profile_effects TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE action_plans ADD COLUMN " +
                        "verification_summary TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN priority_mode TEXT NOT NULL DEFAULT 'adaptive'",
                )
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN priority_score REAL NOT NULL DEFAULT 50",
                )
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN priority_reason TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL("ALTER TABLE cards ADD COLUMN priority_updated_at TEXT")
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN priority_locked INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN workspace_type TEXT NOT NULL DEFAULT 'personal'",
                )
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN workspace_id TEXT NOT NULL DEFAULT 'personal'",
                )
                db.execSQL("ALTER TABLE cards ADD COLUMN assignee_id TEXT")
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN participant_ids TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN deliverables TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL("ALTER TABLE cards ADD COLUMN source_session_id TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS team_workspaces (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS team_members (
                        id TEXT NOT NULL,
                        workspace_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        role TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(workspace_id) REFERENCES team_workspaces(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_team_members_workspace_id " +
                        "ON team_members(workspace_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS team_assignments (
                        card_id TEXT NOT NULL,
                        member_id TEXT NOT NULL,
                        assignment_role TEXT NOT NULL,
                        is_owner INTEGER NOT NULL,
                        PRIMARY KEY(card_id, member_id),
                        FOREIGN KEY(card_id) REFERENCES cards(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(member_id) REFERENCES team_members(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_team_assignments_card_id " +
                        "ON team_assignments(card_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_team_assignments_member_id " +
                        "ON team_assignments(member_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS intake_sessions (
                        id TEXT NOT NULL,
                        source_kind TEXT NOT NULL,
                        source_uri TEXT,
                        workspace_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        workflow_run_id TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cards ADD COLUMN reminder_nodes TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        /** Team collaboration phase 1: server-backed workspaces, roles, and milestone linkage. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE team_workspaces ADD COLUMN invite_code TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE team_workspaces ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE team_workspaces ADD COLUMN my_role TEXT NOT NULL DEFAULT 'member'",
                )
                db.execSQL(
                    "ALTER TABLE team_workspaces ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE team_members ADD COLUMN avatar_color TEXT NOT NULL DEFAULT 'blue'",
                )
                db.execSQL("ALTER TABLE cards ADD COLUMN milestone_id TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN updated_at TEXT")
            }
        }
    }
}
