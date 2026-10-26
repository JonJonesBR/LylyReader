package com.jonjonesbr.audiobookgen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface QueueItemDao {
    // rowid = ordem de inserção: replaceAll grava a lista inteira na ordem da fila (inclusive a ordem manual que o
    // usuário escolheu). Antes ordenava por criadoEmMillis e a ordem manual se perdia ao reabrir o app.
    @Query("SELECT * FROM queue_items ORDER BY rowid ASC")
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
