package com.suishouban.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CardRefinementDao {
    @Transaction
    @Query("SELECT * FROM action_plans WHERE status = 'accepted' ORDER BY updated_at DESC")
    fun observeAcceptedPlans(): Flow<List<ActionPlanWithItems>>

    @Transaction
    @Query("SELECT * FROM action_plans WHERE parent_card_id = :cardId LIMIT 1")
    fun observePlan(cardId: String): Flow<ActionPlanWithItems?>

    @Transaction
    @Query("SELECT * FROM action_plans WHERE parent_card_id = :cardId LIMIT 1")
    suspend fun findPlan(cardId: String): ActionPlanWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(plan: ActionPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<PlanItemEntity>)

    @Query("DELETE FROM action_plans WHERE parent_card_id = :cardId")
    suspend fun deletePlanForCard(cardId: String)

    @Transaction
    suspend fun replacePlan(plan: ActionPlanEntity, items: List<PlanItemEntity>) {
        deletePlanForCard(plan.parentCardId)
        upsertPlan(plan)
        if (items.isNotEmpty()) upsertItems(items)
    }

    @Transaction
    suspend fun acceptPlan(
        plan: ActionPlanEntity,
        items: List<PlanItemEntity>,
        attachments: List<CardAttachmentEntity>,
    ) {
        replacePlan(plan, items)
        if (attachments.isNotEmpty()) upsertAttachments(attachments)
    }

    @Query("SELECT * FROM card_attachments WHERE card_id = :cardId ORDER BY created_at")
    fun observeAttachments(cardId: String): Flow<List<CardAttachmentEntity>>

    @Query("SELECT * FROM card_attachments WHERE card_id = :cardId ORDER BY created_at")
    suspend fun findAttachments(cardId: String): List<CardAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<CardAttachmentEntity>)

    @Query("DELETE FROM card_attachments WHERE id = :attachmentId")
    suspend fun deleteAttachment(attachmentId: String)

    @Query("SELECT * FROM card_refinement_preferences WHERE card_id = :cardId LIMIT 1")
    fun observePreference(cardId: String): Flow<CardRefinementPreferenceEntity?>

    @Query("SELECT * FROM card_refinement_preferences WHERE card_id = :cardId LIMIT 1")
    suspend fun findPreference(cardId: String): CardRefinementPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: CardRefinementPreferenceEntity)
}
