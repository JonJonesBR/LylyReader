package com.jonjonesbr.audiobookgen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QueueItemEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LylyDatabase : RoomDatabase() {
    abstract fun queueItemDao(): QueueItemDao

    companion object {
        @Volatile private var instance: LylyDatabase? = null

        fun get(context: Context): LylyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LylyDatabase::class.java,
                    "lylyreader.db",
                ).build().also { instance = it }
            }
    }
}
