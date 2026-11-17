package com.inako.downloader

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val COMPLETE = "complete"
    const val CANCELLED = "cancelled"
    val active = setOf(QUEUED, RUNNING)
}

data class DownloadSettings(
    val height: Int = 0,
    val audio: Boolean = false,
    val format: String = "mp4",
    val wifi: Boolean = false,
    val automatic: Boolean = true,
    val parallel: Int = 2
)

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(
        DownloadSettings(
            preferences.getInt("height", 0), preferences.getBoolean("audio", false),
            preferences.getString("format", "mp4") ?: "mp4", preferences.getBoolean("wifi", false),
            preferences.getBoolean("automatic", true), preferences.getInt("parallel", 2).coerceIn(1, 3)
        )
    )
    val state = mutable.asStateFlow()

    fun set(settings: DownloadSettings) {
        preferences.edit().putInt("height", settings.height).putBoolean("audio", settings.audio)
            .putString("format", settings.format).putBoolean("wifi", settings.wifi)
            .putBoolean("automatic", settings.automatic).putInt("parallel", settings.parallel).apply()
        mutable.value = settings
    }
}

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val created: Long = System.currentTimeMillis(),
    val status: String = Status.QUEUED,
    val height: Int,
    val audio: Boolean,
    val format: String,
    val wifi: Boolean,
    val attempts: Int = 0,
    val detail: String? = null,
    val uri: String? = null,
    val mime: String? = null
)

@Dao
abstract class DownloadHistoryRepository {
    @Query("SELECT * FROM downloads ORDER BY id DESC")
    abstract fun observe(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract suspend fun get(id: Int): DownloadRecord?

    @Query("SELECT * FROM downloads WHERE url = :url AND status IN ('queued','running','paused') ORDER BY id DESC LIMIT 1")
    abstract suspend fun pending(url: String): DownloadRecord?

    @Query("SELECT * FROM downloads WHERE status IN ('queued','running')")
    abstract suspend fun active(): List<DownloadRecord>

    @Insert
    abstract suspend fun insert(record: DownloadRecord): Long

    @Transaction
    open suspend fun enqueue(record: DownloadRecord): Int? {
        if (pending(record.url) != null) return null
        return insert(record).toInt()
    }

    @Query("UPDATE downloads SET status = 'running', attempts = attempts + 1, detail = NULL WHERE id = :id AND status IN ('queued','running')")
    abstract suspend fun begin(id: Int): Int

    @Query("UPDATE downloads SET title = COALESCE(:title, title), thumbnail = COALESCE(:thumbnail, thumbnail) WHERE id = :id AND status = 'running'")
    abstract suspend fun updateMetadata(id: Int, title: String?, thumbnail: String?)

    @Query("UPDATE downloads SET status = :status, detail = :detail WHERE id = :id AND status IN ('queued','running')")
    abstract suspend fun transitionActive(id: Int, status: String, detail: String? = null): Int

    @Query("UPDATE downloads SET status = 'queued', attempts = 0, detail = NULL WHERE id = :id AND status IN ('paused','failed')")
    abstract suspend fun resume(id: Int): Int

    @Query("UPDATE downloads SET status = 'cancelled', detail = NULL WHERE id = :id AND status IN ('paused','queued','running')")
    abstract suspend fun cancel(id: Int): Int

    @Query("UPDATE downloads SET status = 'complete', uri = :uri, mime = :mime, detail = NULL WHERE id = :id AND status = 'running'")
    abstract suspend fun complete(id: Int, uri: String, mime: String): Int

    @Query("DELETE FROM downloads WHERE id = :id AND status IN ('complete','failed','cancelled')")
    abstract suspend fun delete(id: Int)
}

@Database(entities = [DownloadRecord::class], version = 1)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): DownloadHistoryRepository

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}

data class TransferProgress(
    val stage: String,
    val bytes: Long = 0,
    val total: Long? = null,
    val rate: Long? = null
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}
