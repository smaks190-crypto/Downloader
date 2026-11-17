package com.inako.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val id = params.jobId
        val notifications = graph.notifications
        setNotification(params, notifications.progressId(id), notifications.progress(id, TransferProgress("В очереди")),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        jobs[id] = scope.launch {
            val retry = graph.queue.run(id) { notifications.update(id, it) }
            jobs.remove(id)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class LegacyTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id get() = inputData.getInt("id", 0)
    private val notifications get() = applicationContext.graph.notifications

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        notifications.progressId(id), notifications.progress(id, TransferProgress("В очереди")),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    override suspend fun doWork(): Result {
        if (id <= 0) return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (_: IllegalStateException) {
            applicationContext.graph.history.transitionActive(id, Status.PAUSED, Problem.SCHEDULE.text)
            return Result.failure()
        }
        return if (applicationContext.graph.queue.run(id) { notifications.update(id, it) }) Result.retry() else Result.success()
    }
}

class DownloadNotificationManager(private val context: Context) {
    private val system = context.getSystemService(NotificationManager::class.java)
    private val channel = "transfers"

    init {
        system.createNotificationChannel(NotificationChannel(channel, "Загрузки", NotificationManager.IMPORTANCE_LOW))
    }

    fun progressId(id: Int) = id * 2
    private fun resultId(id: Int) = progressId(id) + 1

    fun progress(id: Int, data: TransferProgress): Notification {
        val fraction = data.fraction
        val detail = progressDetail(data)
        return base(id).setContentTitle(data.stage).setContentText(detail.ifBlank { "Downloader" })
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(0, "Пауза", action(id, "pause"))
            .addAction(0, "Отменить", action(id, "cancel")).build()
    }

    fun update(id: Int, data: TransferProgress) = post(progressId(id), progress(id, data))
    fun finished(id: Int, title: String) = post(resultId(id), base(id).setContentTitle("Скачано")
        .setContentText(title).setAutoCancel(true).build())

    fun failed(id: Int, title: String, detail: String) = post(resultId(id), base(id)
        .setContentTitle(title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        .setAutoCancel(true).build())

    fun paused(id: Int) = post(resultId(id), base(id).setContentTitle("Загрузка на паузе")
        .addAction(0, "Продолжить", PendingIntent.getActivity(context, id,
            Intent(context, DownloadActionActivity::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Отменить", action(id, "cancel")).setAutoCancel(true).build())

    fun dismissResult(id: Int) = system.cancel(resultId(id))

    fun dismiss(id: Int) {
        system.cancel(progressId(id))
        system.cancel(resultId(id))
    }

    private fun base(id: Int) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_download)
        .setContentIntent(PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    private fun action(id: Int, action: String) = PendingIntent.getBroadcast(context, id,
        Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (system.areNotificationsEnabled()) system.notify(id, notification)
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        if (id <= 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    "pause" -> {
                        context.graph.queue.pause(id)
                        if (context.graph.history.get(id)?.status == Status.PAUSED) context.graph.notifications.paused(id)
                    }
                    "cancel" -> context.graph.queue.cancel(id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

fun progressDetail(data: TransferProgress): String = buildList {
    data.fraction?.let { add("${(it * 100).toInt()}%") }
    if (data.bytes > 0 || data.total != null) {
        val amount = bytesLabel(data.bytes)
        add(data.total?.let { "$amount / ${bytesLabel(it)}" } ?: amount)
    }
    data.rate?.takeIf { it > 0 }?.let { add("${bytesLabel(it)}/с") }
}.joinToString("  ·  ")

private fun bytesLabel(bytes: Long): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes Б" else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}
