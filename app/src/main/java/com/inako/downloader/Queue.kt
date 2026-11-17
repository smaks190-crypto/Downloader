package com.inako.downloader

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class DownloaderApp : Application() {
    val graph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as DownloaderApp).graph

class AppGraph(context: Context) {
    val settings = SettingsRepository(context)
    val history = HistoryDatabase.create(context).history()
    val storage = MediaStorageRepository(context)
    val notifications = DownloadNotificationManager(context)
    val engine: DownloadEngine = AndroidDownloadEngine(YtDlpExtractor(context), DirectDownload())
    val queue = DownloadQueueManager(context, history, settings, storage, notifications, engine)
}

enum class Submission { STARTED, DUPLICATE, FAILED }

internal fun shouldRetry(problem: Problem, attempt: Int) = problem.retry && (problem == Problem.WIFI || attempt < 3)

class DownloadQueueManager(
    private val context: Context,
    private val history: DownloadHistoryRepository,
    private val settings: SettingsRepository,
    private val storage: MediaStorageRepository,
    private val notifications: DownloadNotificationManager,
    private val engine: DownloadEngine
) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val slots = MutableStateFlow(0)
    private val slotLock = Mutex()
    private val operationLocks = Array(32) { Mutex() }
    private val submissionLock = Mutex()
    private val mutableProgress = MutableStateFlow<Map<Int, TransferProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()

    suspend fun enqueue(url: HttpUrl, options: DownloadSettings): Submission = submissionLock.withLock {
        val record = DownloadRecord(
            url = url.newBuilder().fragment(null).build().toString(),
            title = url.host, height = options.height,
            audio = options.audio, format = options.format, wifi = options.wifi
        )
        withContext(NonCancellable) {
            val id = history.enqueue(record) ?: return@withContext Submission.DUPLICATE
            scheduleOrFail(record.copy(id = id))
        }
    }

    suspend fun resume(id: Int): Submission = submissionLock.withLock {
        withContext(NonCancellable) {
            val record = history.get(id) ?: return@withContext Submission.FAILED
            if (history.resume(id) == 0) return@withContext Submission.DUPLICATE
            scheduleOrFail(record)
        }
    }

    private suspend fun scheduleOrFail(record: DownloadRecord): Submission {
        val scheduled = try {
            if (Build.VERSION.SDK_INT >= 34) {
                val builder = JobInfo.Builder(record.id, ComponentName(context, TransferJobService::class.java))
                    .setUserInitiated(true)
                    .setBackoffCriteria(15_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                if (record.wifi) {
                    builder.setRequiredNetwork(NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build())
                } else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
            } else {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (record.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
                val request = OneTimeWorkRequestBuilder<LegacyTransferWorker>()
                    .setInputData(workDataOf("id" to record.id))
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(context).enqueueUniqueWork(workName(record.id), ExistingWorkPolicy.REPLACE, request)
                        .result.get()
                }
                true
            }
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: ExecutionException) {
            false
        }
        if (scheduled) return Submission.STARTED
        history.transitionActive(record.id, Status.PAUSED, Problem.SCHEDULE.text)
        return Submission.FAILED
    }

    suspend fun markInterrupted() {
        if (Build.VERSION.SDK_INT < 34) return
        submissionLock.withLock {
            for (record in history.active()) {
                if (scheduler.getPendingJob(record.id) == null) {
                    history.transitionActive(record.id, Status.PAUSED, "Загрузка прервана системой")
                }
            }
        }
    }

    suspend fun pause(id: Int) {
        submissionLock.withLock {
            if (history.transitionActive(id, Status.PAUSED) > 0) stopScheduled(id)
        }
    }

    suspend fun cancel(id: Int) {
        val changed = submissionLock.withLock {
            if (history.cancel(id) == 0) return@withLock false
            stopScheduled(id)
            true
        }
        if (changed) withContext(Dispatchers.IO) {
            lock(id).withLock { directory(id).deleteRecursively() }
        }
    }

    private suspend fun stopScheduled(id: Int) {
        if (Build.VERSION.SDK_INT >= 34) scheduler.cancel(id) else withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
        }
        notifications.dismiss(id)
    }

    suspend fun delete(id: Int) {
        withContext(Dispatchers.IO) {
            lock(id).withLock {
                val record = history.get(id) ?: return@withLock
                if (record.status !in setOf(Status.COMPLETE, Status.FAILED, Status.CANCELLED)) return@withLock
                record.uri?.let { storage.delete(Uri.parse(it)) }
                directory(id).deleteRecursively()
                history.delete(id)
                notifications.dismiss(id)
            }
        }
    }

    suspend fun run(id: Int, updateNotification: (TransferProgress) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            lock(id).withLock {
                if (history.get(id)?.status !in Status.active) return@withLock false
                acquireSlot()
                try {
                    if (history.begin(id) == 0) return@withLock false
                    val record = history.get(id) ?: return@withLock false
                    if (record.wifi && !isWifi()) {
                        history.transitionActive(id, Status.QUEUED, Problem.WIFI.text)
                        return@withLock true
                    }
                    perform(record, updateNotification)
                } finally {
                    slots.update { it - 1 }
                    mutableProgress.update { it - id }
                }
            }
        }

    private suspend fun perform(record: DownloadRecord, updateNotification: (TransferProgress) -> Unit): Boolean =
        coroutineScope {
            val id = record.id
            val directory = directory(id)
            val progressEvents = Channel<TransferProgress>(Channel.CONFLATED)
            val metadataEvents = Channel<VideoMetadata>(Channel.CONFLATED)
            val progressJob = launch {
                for (event in progressEvents) {
                    mutableProgress.update { it + (id to event) }
                    updateNotification(event)
                }
            }
            val metadataJob = launch {
                for (event in metadataEvents) history.updateMetadata(id, event.title, event.thumbnail)
            }
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw DownloadFailure(Problem.STORAGE)
                progressEvents.trySend(TransferProgress("Ищем видео"))
                val downloaded = engine.download(record, directory, object : DownloadEvents {
                    override fun metadata(metadata: VideoMetadata) { metadataEvents.trySend(metadata) }
                    override fun progress(progress: TransferProgress) { progressEvents.trySend(progress) }
                })
                metadataEvents.close()
                metadataJob.join()
                history.updateMetadata(id, downloaded.title, null)
                progressEvents.trySend(TransferProgress("Сохраняем в медиатеку"))
                val saved = storage.save(downloaded.file, downloaded.title)
                withContext(NonCancellable) {
                    val completed = try {
                        history.complete(id, saved.uri.toString(), saved.mime) == 1
                    } catch (error: Exception) {
                        storage.delete(saved.uri)
                        throw error
                    }
                    progressJob.cancelAndJoin()
                    if (completed) {
                        directory.deleteRecursively()
                        notifications.finished(id, downloaded.title)
                    } else storage.delete(saved.uri)
                }
                false
            } catch (error: CancellationException) {
                throw error
            } catch (error: DownloadFailure) {
                fail(record, error.problem)
            } catch (_: IOException) {
                fail(record, Problem.STORAGE)
            } catch (_: SecurityException) {
                fail(record, Problem.STORAGE)
            } catch (_: Exception) {
                fail(record, Problem.ENGINE)
            } finally {
                progressJob.cancel()
                metadataEvents.close()
                progressEvents.close()
                metadataJob.join()
            }
        }

    private suspend fun fail(record: DownloadRecord, problem: Problem): Boolean {
        val retry = shouldRetry(problem, record.attempts)
        val detail = if (retry && problem != Problem.WIFI) "Повторим при доступной сети" else problem.text
        if (history.transitionActive(record.id, if (retry) Status.QUEUED else Status.FAILED, detail) == 0) return false
        if (!retry) notifications.failed(record.id, record.title, problem.text)
        return retry
    }

    private suspend fun acquireSlot() {
        while (true) {
            combine(slots, settings.state) { active, options -> active < options.parallel }.first { it }
            val acquired = slotLock.withLock {
                if (slots.value >= settings.state.value.parallel) false else {
                    slots.update { it + 1 }
                    true
                }
            }
            if (acquired) return
        }
    }

    private fun isWifi(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun directory(id: Int) = File(context.noBackupFilesDir, "transfers/$id")
    private fun lock(id: Int) = operationLocks[id % operationLocks.size]
    private fun workName(id: Int) = "download-$id"
}
