package com.inako.downloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShareActivity : ComponentActivity() {
    private var incoming: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent) else finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        incoming?.cancel()
        incoming = lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            try {
                val url = ShareIntentHandler.link(intent)
                val options = graph.settings.state.value
                when {
                    url == null -> toast("В сообщении нет ссылки на видео")
                    !options.automatic -> startActivity(Intent(this@ShareActivity, MainActivity::class.java)
                        .putExtra(MainActivity.LINK, url.toString()))
                    else -> toast(graph.queue.enqueue(url, options).label())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(Problem.SCHEDULE.text)
            }
            finish()
        }
    }
}

class DownloadActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            val id = intent.getIntExtra("id", 0)
            if (id > 0) {
                when (graph.queue.resume(id)) {
                    Submission.STARTED -> {
                        graph.notifications.dismissResult(id)
                        toast("Загрузка поставлена в очередь")
                    }
                    Submission.FAILED -> toast(Problem.SCHEDULE.text)
                    Submission.DUPLICATE -> Unit
                }
            }
            finish()
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: DownloaderViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        importDraft(intent)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33) {
            val onboarding = getPreferences(MODE_PRIVATE)
            if (!onboarding.getBoolean("offered_notifications", false)) {
                onboarding.edit().putBoolean("offered_notifications", true).apply()
                requestNotifications()
            }
        }
        lifecycleScope.launch { viewModel.messages.collect { toast(it) } }
        setContent {
            DownloaderScreen(viewModel, open = { openFile(it, false) }, share = { openFile(it, true) },
                requestNotifications = ::requestNotifications)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importDraft(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun importDraft(intent: Intent) {
        intent.getStringExtra(LINK)?.let { viewModel.link.value = it }
        intent.removeExtra(LINK)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openFile(record: DownloadRecord, share: Boolean) {
        val uri = record.uri?.let(Uri::parse) ?: return
        val mime = record.mime ?: "video/*"
        val target = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (share) {
            target.type = mime
            target.putExtra(Intent.EXTRA_STREAM, uri)
            target.clipData = ClipData.newUri(contentResolver, record.title, uri)
        } else target.setDataAndType(uri, mime)
        try {
            startActivity(if (share) Intent.createChooser(target, "Поделиться файлом") else target)
        } catch (_: ActivityNotFoundException) {
            toast("Нет приложения для этого файла")
        } catch (_: SecurityException) {
            toast("Файл недоступен")
        }
    }

    companion object { const val LINK = "download-link" }
}

data class DownloaderUi(
    val records: List<DownloadRecord> = emptyList(),
    val progress: Map<Int, TransferProgress> = emptyMap()
)

class DownloaderViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val graph = application.graph
    val link = MutableStateFlow("")
    val settings = graph.settings.state
    val ui = combine(graph.history.observe(), graph.queue.progress, ::DownloaderUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloaderUi())
    private val mutableMessages = MutableSharedFlow<String>()
    val messages = mutableMessages.asSharedFlow()

    fun refresh() = action { graph.queue.markInterrupted() }

    fun download() = action {
        val raw = link.value
        val url = UrlResolver.find(raw)
        if (url == null) {
            mutableMessages.emit("Вставьте ссылку HTTP или HTTPS")
        } else {
            val result = graph.queue.enqueue(url, settings.value)
            if (result == Submission.STARTED && link.value == raw) link.value = ""
            mutableMessages.emit(result.label())
        }
    }

    fun settings(value: DownloadSettings) = graph.settings.set(value)
    fun pause(id: Int) = action { graph.queue.pause(id) }
    fun cancel(id: Int) = action { graph.queue.cancel(id) }
    fun resume(id: Int) = action {
        val result = graph.queue.resume(id)
        if (result == Submission.STARTED) graph.notifications.dismissResult(id)
        mutableMessages.emit(result.label())
    }
    fun delete(id: Int) = action { graph.queue.delete(id) }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableMessages.emit("Не удалось завершить действие")
        }
    }
}

private fun Submission.label() = when (this) {
    Submission.STARTED -> "Загрузка поставлена в очередь"
    Submission.DUPLICATE -> "Эта ссылка уже в списке загрузок"
    Submission.FAILED -> Problem.SCHEDULE.text
}

private fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
