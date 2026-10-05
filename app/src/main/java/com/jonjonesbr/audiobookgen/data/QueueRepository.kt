package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.service.QueueManager
import android.content.Context
import android.content.SharedPreferences

class QueueRepository private constructor(
    private val dao: QueueItemDao,
) {
    suspend fun loadItems(legacyPrefs: SharedPreferences): List<QueueItem> {
        val entities = dao.getAll()
        val databaseItems = entities.map { it.toQueueItem() }
        if (databaseItems.isNotEmpty()) {
            if (entities.any { it.status == QueueStatus.PROCESSANDO.name }) {
                replaceAll(databaseItems)
            }
            return databaseItems
        }

        val legacyItems = QueueManager.itemsFromJson(
            legacyPrefs.getString(QueueManager.PREF_KEY, null)
        )
        if (legacyItems.isNotEmpty()) {
            replaceAll(legacyItems)
        }
        return legacyItems
    }

    suspend fun replaceAll(items: List<QueueItem>) {
        dao.replaceAll(items.map { it.toEntity() })
    }

    companion object {
        @Volatile private var instance: QueueRepository? = null

        fun get(context: Context): QueueRepository =
            instance ?: synchronized(this) {
                instance ?: QueueRepository(
                    LylyDatabase.get(context).queueItemDao()
                ).also { instance = it }
            }
    }
}
