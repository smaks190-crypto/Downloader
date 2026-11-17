package com.inako.downloader

import android.content.Intent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlResolver {
    private val links = Regex("""(?i)https?://[^\s<>\u0000-\u001f\u007f"']+""")
    private val unbalancedClosers = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun find(text: CharSequence?): HttpUrl? = links.findAll(text?.take(32_768) ?: "")
        .mapNotNull { match ->
            var candidate = match.value.trimEnd('.', ',', ';', '!', '?')
            while (candidate.lastOrNull() in unbalancedClosers) {
                val end = candidate.last()
                val start = unbalancedClosers.getValue(end)
                if (candidate.count { it == end } <= candidate.count { it == start }) break
                candidate = candidate.dropLast(1)
            }
            validate(candidate)
        }.firstOrNull()

    fun validate(value: String): HttpUrl? {
        if (value.length !in 1..8192 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) return null
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority) return null
        return value.toHttpUrlOrNull()
    }

    fun extension(url: HttpUrl) = url.pathSegments.last().substringAfterLast('.', "").lowercase()
    fun isDirect(url: HttpUrl) = extension(url) in mediaTypes
    fun isManifest(url: HttpUrl) = extension(url) in setOf("m3u8", "mpd")

    val mediaTypes = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "mkv" to "video/x-matroska", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp", "mpeg" to "video/mpeg", "ts" to "video/mp2t",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav", "aac" to "audio/aac"
    )
}

object ShareIntentHandler {
    fun link(intent: Intent): HttpUrl? = when (intent.action) {
        Intent.ACTION_SEND -> if (intent.type == "text/plain") {
            UrlResolver.find(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        } else null
        Intent.ACTION_VIEW -> intent.dataString?.let(UrlResolver::validate)
        else -> null
    }
}

enum class Problem(val text: String, val retry: Boolean = false) {
    DRM("Этот поток защищён DRM"),
    AUTH("Источник требует входа или разрешения на просмотр"),
    EXPIRED("Ссылка истекла или видео удалено"),
    NETWORK("Соединение прервалось. Проверьте сеть", true),
    UNSUPPORTED("По этой ссылке не удалось найти доступное видео"),
    ENGINE("Не удалось обработать видео. Попробуйте другой формат"),
    STORAGE("Не удалось сохранить файл. Проверьте свободное место"),
    SCHEDULE("Android не запустил загрузку. Нажмите «Продолжить»"),
    WIFI("Ожидание подключения к Wi-Fi", true);

    companion object {
        fun fromEngine(message: String): Problem {
            val s = message.lowercase()
            return when {
                "drm" in s || "widevine" in s -> DRM
                listOf("sign in", "log in", "login", "authentication", "private video", "members-only", "http error 401", "http error 403", "age-restricted", "cookies").any { it in s } -> AUTH
                listOf("expired", "http error 404", "http error 410", "video unavailable", "has been removed").any { it in s } -> EXPIRED
                listOf("timed out", "temporary failure", "unable to download webpage", "network is unreachable", "connection", "http error 429", "http error 5").any { it in s } -> NETWORK
                listOf("unsupported url", "no video formats", "no video could be found", "requested format is not available", "no suitable extractor").any { it in s } -> UNSUPPORTED
                else -> ENGINE
            }
        }

        fun fromHttp(code: Int): Problem = when (code) {
            401, 403, 451 -> AUTH
            404, 410 -> EXPIRED
            408, 425, 429, in 500..599 -> NETWORK
            else -> UNSUPPORTED
        }
    }
}

class DownloadFailure(val problem: Problem) : Exception(problem.text)
