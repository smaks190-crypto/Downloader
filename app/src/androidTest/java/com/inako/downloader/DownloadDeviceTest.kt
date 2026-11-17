package com.inako.downloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    @Test fun roomAtomicallyDeduplicatesAndPersistsStateTransitions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, HistoryDatabase::class.java).build()
        try {
            val history = db.history()
            val record = DownloadRecord(url = "https://example.org/video", title = "Video", height = 0, audio = false, format = "mp4", wifi = false)
            val attempts = listOf(async { history.enqueue(record) }, async { history.enqueue(record) })
            val id = attempts.map { it.await() }.filterNotNull().single()
            assertEquals(1, history.begin(id))
            assertEquals(1, history.transitionActive(id, Status.PAUSED))
            assertEquals(0, history.complete(id, "content://test/video", "video/mp4"))
            assertEquals(1, history.resume(id))
            assertEquals(1, history.begin(id))
            assertEquals(1, history.complete(id, "content://test/video", "video/mp4"))
            assertEquals(Status.COMPLETE, history.get(id)?.status)
        } finally {
            db.close()
        }
    }

    @Test fun resolvesShareAndDownloadsAutomaticallyWhileBackgrounded() = runBlocking {
        fixtureServer(slow = true).use { server ->
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Вот видео ${server.url("/direct.mp4")}")
            val targets = app.packageManager.queryIntentActivities(intent.setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(targets.any { it.activityInfo.name == ShareActivity::class.java.name })
            val url = requireNotNull(ShareIntentHandler.link(intent)).toString()
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val id = withTimeout(30_000) {
                while (true) {
                    app.graph.history.pending(url)?.let { return@withTimeout it.id }
                    delay(100)
                }
                error("missing queue entry")
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            withTimeout(25_000) {
                while (app.graph.queue.progress.value[id]?.bytes?.let { it > 0 } != true) delay(100)
            }
            val result = withTimeout(60_000) {
                while (true) {
                    val record = app.graph.history.get(id)
                    require(record?.status != Status.FAILED && record?.status != Status.PAUSED) { "${record?.status}: ${record?.detail}" }
                    if (record?.status == Status.COMPLETE) return@withTimeout record
                    delay(200)
                }
                error("missing completed entry")
            }
            try {
                val uri = Uri.parse(result.uri)
                assertTrue(tracks(app, uri).any { it.startsWith("video/") })
                assertTrue(app.contentResolver.openInputStream(uri)?.use { it.read() } != -1)
            } finally {
                app.graph.queue.delete(id)
            }
        }
    }

    @Test fun ytDlpAndFfmpegMergeDashAndReadOpenHls() = runBlocking {
        fixtureServer(slow = false).use { server ->
            for ((index, route) in listOf("/dash/stream.mpd", "/stream.m3u8", "/page").withIndex()) {
                val directory = File(app.cacheDir, "engine-test-$index").apply { mkdirs() }
                try {
                    val url = server.url(route)
                    val record = DownloadRecord(id = 9000 + index, url = url.toString(), title = "Fixture",
                        height = 0, audio = false, format = "mp4", wifi = false)
                    val result = withTimeout(75_000) {
                        app.graph.engine.download(record, directory, object : DownloadEvents {
                            override fun metadata(metadata: VideoMetadata) = Unit
                            override fun progress(progress: TransferProgress) = Unit
                        })
                    }
                    val types = tracks(app, Uri.fromFile(result.file))
                    assertTrue("$route: $types", types.any { it.startsWith("video/") })
                    assertTrue("$route: $types", types.any { it.startsWith("audio/") })
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private fun fixtureServer(slow: Boolean) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                if (path == "/page") return MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><head><title>Fixture</title></head><body><video src='/direct.mp4'></video></body></html>")
                val bytes = runCatching { assets.open("generated$path").use { it.readBytes() } }.getOrNull()
                    ?: return MockResponse().setResponseCode(404)
                val type = when {
                    path.endsWith(".mpd") -> "application/dash+xml"
                    path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                    path.endsWith(".ts") -> "video/mp2t"
                    else -> "video/mp4"
                }
                val response = MockResponse().setHeader("Content-Type", type)
                if (request.method == "HEAD") return response.setHeader("Content-Length", bytes.size)
                response.setBody(Buffer().write(bytes))
                if (slow) response.throttleBody(4096, 100, TimeUnit.MILLISECONDS)
                return response
            }
        }
        start()
    }

    private fun tracks(context: Context, uri: Uri): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, emptyMap())
            return (0 until extractor.trackCount).mapNotNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
            }
        } finally {
            extractor.release()
        }
    }
}
