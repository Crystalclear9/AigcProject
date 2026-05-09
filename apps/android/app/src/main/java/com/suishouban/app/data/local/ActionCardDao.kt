package com.suishouban.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionCardDao {
    @Query("SELECT * FROM cards ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ActionCardEntity>>

    @Query(
        """
        SELECT * FROM cards
        WHERE (:type IS NULL OR card_type = :type)
          AND (:status IS NULL OR status = :status)
          AND (:keyword IS NULL OR title LIKE '%' || :keyword || '%' OR summary LIKE '%' || :keyword || '%' OR source_text LIKE '%' || :keyword || '%')
        ORDER BY created_at DESC
        """
    )
    fun observeFiltered(type: String?, status: String?, keyword: String?): Flow<List<ActionCardEntity>>

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ActionCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: ActionCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<ActionCardEntity>)

    @Update
    suspend fun update(card: ActionCardEntity)

    @Query("UPDATE cards SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: String)
}
