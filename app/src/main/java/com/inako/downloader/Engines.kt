package com.inako.downloader

import android.content.Context
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class VideoMetadata(val title: String?, val thumbnail: String?)
data class DownloadedFile(val file: File, val title: String)

interface VideoExtractor {
    suspend fun download(record: DownloadRecord, url: HttpUrl, directory: File, events: DownloadEvents): DownloadedFile
}

interface DownloadEvents {
    fun metadata(metadata: VideoMetadata)
    fun progress(progress: TransferProgress)
}

interface DownloadEngine {
    suspend fun download(record: DownloadRecord, directory: File, events: DownloadEvents): DownloadedFile
}

class AndroidDownloadEngine(
    private val extractor: VideoExtractor,
    private val direct: DirectDownload
) : DownloadEngine {
    override suspend fun download(record: DownloadRecord, directory: File, events: DownloadEvents): DownloadedFile {
        val url = UrlResolver.validate(record.url) ?: throw DownloadFailure(Problem.UNSUPPORTED)
        val extension = UrlResolver.extension(url)
        val directEligible = !record.audio && record.height == 0
        // A direct media URL exposes one quality. Use the source file without re-encoding.
        if (directEligible && UrlResolver.isDirect(url) && UrlResolver.mediaTypes[extension]?.startsWith("video/") == true) {
            try {
                return direct.download(url, directory, events)
            } catch (error: DownloadFailure) {
                if (error.problem != Problem.UNSUPPORTED) throw error
            }
        }
        return try {
            extractor.download(record, url, directory, events)
        } catch (error: DownloadFailure) {
            if (error.problem != Problem.UNSUPPORTED || !directEligible || UrlResolver.isManifest(url)) throw error
            direct.download(url, directory, events)
        }
    }
}

internal fun formatSelector(height: Int): String {
    val limit = if (height > 0) "[height<=$height]" else ""
    return "bv$limit+ba/b$limit"
}

class YtDlpExtractor(private val context: Context) : VideoExtractor {
    private var ready = false

    @Synchronized
    private fun initialize() {
        if (ready) return
        val directory = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Engine directory unavailable")
        val payload = File(directory, YoutubeDL.ytdlpBin)
        val temporary = File.createTempFile("payload-", ".tmp", directory)
        try {
            context.resources.openRawResource(R.raw.ytdlp).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(temporary.toPath(), payload.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        YoutubeDL.init(context)
        FFmpeg.init(context)
        ready = true
    }

    override suspend fun download(
        record: DownloadRecord, url: HttpUrl, directory: File, events: DownloadEvents
    ): DownloadedFile = runInterruptible(Dispatchers.IO) {
        try {
            initialize()
        } catch (_: YoutubeDLException) {
            throw DownloadFailure(Problem.ENGINE)
        }
        var title = record.title
        var lastProgress = 0L
        val request = YoutubeDLRequest(url.toString())
            .addOption("--ignore-config")
            .addOption("--no-update")
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--continue")
            .addOption("--no-color")
            .addOption("--no-simulate")
            .addOption("--newline")
            .addOption("--progress")
            .addOption("--progress-delta", "0.5")
            .addOption("--socket-timeout", "20")
            .addOption("--retries", "2")
            .addOption("--fragment-retries", "2")
            .addOption("--abort-on-unavailable-fragments")
            .addOption("--downloader", "m3u8,dash:native")
            .addOption("--print", "before_dl:INFO %(title,thumbnail)j")
            .addOption("--print", "after_move:FILE %(filepath)j")
            .addOption("--progress-template", "download:TRANSFER %(progress.downloaded_bytes,progress.total_bytes,progress.total_bytes_estimate,progress.speed,info.format_id)j")
            .addOption("--output", File(directory, "media.%(ext)s").absolutePath)
        if (record.audio) {
            request.addOption("--format", "ba/b")
                .addOption("--extract-audio")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0")
        } else {
            request.addOption("--format", formatSelector(record.height))
            when (record.format) {
                "mp4" -> request.addOption("--merge-output-format", "mp4/mkv")
                    .addOption("--remux-video", "mp4")
                "mkv" -> request.addOption("--merge-output-format", "mkv")
                    .addOption("--remux-video", "mkv")
            }
        }

        val response = try {
            YoutubeDL.execute(request, processId = "download-${record.id}", redirectErrorStream = false) { _, _, line ->
                when {
                    line.startsWith("INFO ") -> {
                        val data = readJson(line.substringAfter(' '))
                        val foundTitle = data?.value("title")?.toString()?.takeIf(String::isNotBlank)?.take(200)
                        if (foundTitle != null) title = foundTitle
                        val image = data?.value("thumbnail")?.toString()?.let(UrlResolver::validate)?.toString()
                        events.metadata(VideoMetadata(foundTitle, image))
                    }
                    line.startsWith("TRANSFER ") -> {
                        val time = SystemClock.elapsedRealtime()
                        if (time - lastProgress >= 450) {
                            lastProgress = time
                            val data = readJson(line.substringAfter(' '))
                            val id = data?.value("info.format_id")?.toString()?.take(40)
                            events.progress(TransferProgress(
                                if (id.isNullOrBlank()) "Загрузка" else "Поток $id",
                                data?.long("progress.downloaded_bytes") ?: 0,
                                data?.long("progress.total_bytes"),
                                data?.long("progress.speed")
                            ))
                        }
                    }
                    line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                        line.startsWith("[VideoRemuxer]") -> events.progress(TransferProgress("Обработка файла"))
                }
            }
        } catch (error: YoutubeDLException) {
            throw DownloadFailure(Problem.fromEngine(error.message.orEmpty()))
        } catch (_: YoutubeDL.CanceledException) {
            throw CancellationException("Transfer stopped")
        }
        if (response.exitCode != 0) throw DownloadFailure(Problem.fromEngine(response.err))
        val file = response.out.lineSequence().filter { it.startsWith("FILE ") }.lastOrNull()
            ?.substringAfter(' ')?.let { runCatching { JSONArray("[$it]").getString(0) }.getOrNull() }
            ?.let(::File)?.canonicalFile
        if (file == null || file.parentFile != directory.canonicalFile || !file.isFile || file.length() == 0L) {
            throw DownloadFailure(Problem.ENGINE)
        }
        DownloadedFile(file, title)
    }

    private fun readJson(value: String) = runCatching { org.json.JSONObject(value) }.getOrNull()
    private fun org.json.JSONObject.value(key: String): Any? = opt(key)?.takeUnless { it == org.json.JSONObject.NULL || it == "NA" }
    private fun org.json.JSONObject.long(key: String): Long? = (value(key) as? Number)?.toLong()?.takeIf { it >= 0 }
}

class DirectDownload(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
) {
    private val range = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    suspend fun download(url: HttpUrl, directory: File, events: DownloadEvents): DownloadedFile =
        kotlinx.coroutines.coroutineScope {
            val partial = File(directory, "direct.part")
            val validator = File(directory, "direct.validator")
            val offset = partial.length()
            val etag = if (offset > 0L && validator.isFile) validator.readText() else ""
            val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK)
                .header("Accept-Encoding", "identity")
                .apply {
                    if (etag.isNotBlank()) {
                        header("Range", "bytes=$offset-")
                        header("If-Range", etag)
                    }
                }.build()
            val call = client.newCall(request)
            // OkHttp Call.cancel closes the socket even when a body read is blocked.
            val canceller = launch(
                context = Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED
            ) {
                try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (response.code == 416 && etag.isNotBlank()) {
                                validator.delete()
                                throw DownloadFailure(Problem.NETWORK)
                            }
                            if (!response.isSuccessful) throw DownloadFailure(Problem.fromHttp(response.code))
                            val body = response.body ?: throw DownloadFailure(Problem.UNSUPPORTED)
                            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                            val urlExt = UrlResolver.extension(response.request.url)
                            val ext = when {
                                mime?.startsWith("video/") == true && UrlResolver.mediaTypes[urlExt] == mime -> urlExt
                                mime?.startsWith("video/") == true -> UrlResolver.mediaTypes.entries.firstOrNull { it.value == mime }?.key
                                mime == "application/octet-stream" && UrlResolver.mediaTypes[urlExt]?.startsWith("video/") == true -> urlExt
                                else -> null
                            } ?: throw DownloadFailure(Problem.UNSUPPORTED)
                            val responseRange = response.header("Content-Range")?.let(range::matchEntire)
                            val start = if (response.code == 206) {
                                val values = responseRange?.groupValues ?: throw DownloadFailure(Problem.NETWORK)
                                if (etag.isBlank() || values[1].toLongOrNull() != offset ||
                                    response.header("ETag") != etag || values[2].toLongOrNull() != values[3].toLongOrNull()?.minus(1)
                                ) {
                                    validator.delete()
                                    throw DownloadFailure(Problem.NETWORK)
                                }
                                offset
                            } else 0L
                            val total = responseRange?.groupValues?.get(3)?.toLongOrNull()
                                ?: body.contentLength().takeIf { it >= 0 }?.plus(start)
                            val strongEtag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                            if (start == 0L) {
                                validator.delete()
                                partial.outputStream().close()
                                if (strongEtag != null) validator.writeText(strongEtag)
                            }
                            val title = response.request.url.pathSegments.last().substringBeforeLast('.')
                                .take(200).ifBlank { url.host }
                            events.metadata(VideoMetadata(title, null))
                            var bytes = start
                            var sampleAt = SystemClock.elapsedRealtime()
                            var sampleBytes = bytes
                            partial.outputStream(append = start > 0).use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(128 * 1024)
                                    while (true) {
                                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                        val size = input.read(buffer)
                                        if (size < 0) break
                                        output.write(buffer, 0, size)
                                        bytes += size
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - sampleAt >= 450) {
                                            events.progress(TransferProgress("Загрузка", bytes, total,
                                                (bytes - sampleBytes) * 1000 / (now - sampleAt)))
                                            sampleAt = now
                                            sampleBytes = bytes
                                        }
                                    }
                                }
                            }
                            if (bytes == 0L || total != null && bytes != total) throw DownloadFailure(Problem.NETWORK)
                            events.progress(TransferProgress("Загрузка", bytes, total))
                            val file = File(directory, "direct.$ext")
                            if (!partial.renameTo(file)) throw DownloadFailure(Problem.STORAGE)
                            validator.delete()
                            DownloadedFile(file, title)
                        }
                    } catch (_: IOException) {
                        throw DownloadFailure(Problem.NETWORK)
                    }
                }
            } finally {
                canceller.cancel()
            }
        }

    private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)
}
