package com.jonjonesbr.audiobookgen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface QueueItemDao {
    @Query("SELECT * FROM queue_items ORDER BY criadoEmMillis ASC")
    suspend fun getAll(): List<QueueItemEntity>

    @Query("SELECT COUNT(*) FROM queue_items")
    suspend fun count(): Int

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueItemEntity>)

    @Transaction
    suspend fun replaceAll(items: List<QueueItemEntity>) {
        clear()
        insertAll(items.takeLast(MAX_ITEMS))
    }

    companion object {
        const val MAX_ITEMS = 50
    }
}
