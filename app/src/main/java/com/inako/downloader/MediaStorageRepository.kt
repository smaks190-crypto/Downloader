package com.inako.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PublishedMedia(val uri: Uri, val mime: String)

class MediaStorageRepository(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun save(file: File, title: String, onInsert: suspend (PublishedMedia) -> Unit): PublishedMedia = withContext(Dispatchers.IO) {
        val extension = file.extension.lowercase()
        val mime = UrlResolver.mediaTypes[extension] ?: throw DownloadFailure(Problem.ENGINE)
        val audio = mime.startsWith("audio/")
        val root = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val name = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}\p{Cf}]"""), " ").trim(' ', '.')
            .take(110).ifBlank { "Видео" }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/Downloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("MediaStore insert failed")
        var saved = false
        val media = PublishedMedia(uri, mime)
        try {
            onInsert(media)
            file.inputStream().use { input ->
                val destination = resolver.openOutputStream(uri) ?: throw IOException("MediaStore stream not available")
                destination.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        ensureActive()
                        val length = input.read(buffer)
                        if (length < 0) break
                        output.write(buffer, 0, length)
                    }
                }
            }
            ensureActive()
            val updated = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            if (updated != 1) throw IOException("MediaStore publish failed")
            saved = true
            media
        } finally {
            if (!saved) runCatching { resolver.delete(uri, null, null) }
        }
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
}
