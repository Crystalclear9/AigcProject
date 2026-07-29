package com.suishouban.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE id = :id LIMIT 1")
    suspend fun find(id: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profiles")
    suspend fun deleteAllProfiles()

    @Query("SELECT * FROM profile_signal_stats")
    suspend fun allStats(): List<ProfileSignalStatEntity>

    @Query(
        "SELECT * FROM profile_signal_stats " +
            "WHERE metric = :metric AND option_value = :option LIMIT 1"
    )
    suspend fun findStat(metric: String, option: String): ProfileSignalStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStat(stat: ProfileSignalStatEntity)

    @Query("DELETE FROM profile_signal_stats")
    suspend fun clearStats()
}

