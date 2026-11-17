package com.inako.downloader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val palette = darkColorScheme(
    primary = Color(0xFF8CF1CA),
    onPrimary = Color(0xFF003827),
    background = Color(0xFF101615),
    surface = Color(0xFF18221F),
    surfaceVariant = Color(0xFF24312C),
    onSurface = Color(0xFFE3EFEB),
    onSurfaceVariant = Color(0xFFAABDB3),
    outline = Color(0xFF64786F)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    viewModel: DownloaderViewModel,
    open: (DownloadRecord) -> Unit,
    share: (DownloadRecord) -> Unit,
    requestNotifications: () -> Unit
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<Int?>(null) }

    MaterialTheme(colorScheme = palette) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).imePadding().fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_download), null, tint = palette.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("Downloader", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { viewModel.link.value = it.take(32_768) },
                        label = { Text("Ссылка на видео") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.download() },
                        enabled = UrlResolver.find(link) != null,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Скачать", fontWeight = FontWeight.SemiBold) }
                    if (state.records.isEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Text("Загрузки появятся здесь", style = MaterialTheme.typography.titleMedium)
                        Text("Вставьте ссылку или выберите Downloader в меню «Поделиться».",
                            color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val pending = state.records.filter { it.status in Status.active || it.status == Status.PAUSED }
                val history = state.records.filter { it.status !in Status.active && it.status != Status.PAUSED }
                if (pending.isNotEmpty()) item { Section("Загрузки") }
                items(pending, key = { it.id }) { record ->
                    RecordCard(record, state.progress[record.id]) {
                        if (record.status == Status.PAUSED) {
                            TextButton(onClick = { viewModel.resume(record.id) }) { Text("Продолжить") }
                        } else {
                            TextButton(onClick = { viewModel.pause(record.id) }) { Text("Пауза") }
                        }
                        TextButton(onClick = { viewModel.cancel(record.id) }) { Text("Отменить") }
                    }
                }
                if (history.isNotEmpty()) item { Section("История") }
                items(history, key = { it.id }) { record ->
                    RecordCard(record, null) {
                        when (record.status) {
                            Status.COMPLETE -> {
                                TextButton(onClick = { open(record) }) { Text("Открыть") }
                                TextButton(onClick = { share(record) }) { Text("Поделиться") }
                            }
                            Status.FAILED -> TextButton(onClick = { viewModel.resume(record.id) }) { Text("Повторить") }
                        }
                        TextButton(onClick = { pendingDelete = record.id }) { Text("Удалить") }
                    }
                }
            }
        }

        if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsPanel(settings, viewModel::settings, requestNotifications)
        }

        pendingDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Удалить загрузку?") },
                text = { Text("Запись и сохранённый файл будут удалены.") },
                confirmButton = { TextButton(onClick = { viewModel.delete(id); pendingDelete = null }) { Text("Удалить") } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } }
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RecordCard(record: DownloadRecord, progress: TransferProgress?, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(9.dp))
                    .background(palette.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_download), null, tint = palette.onSurfaceVariant)
                    record.thumbnail?.let {
                        AsyncImage(model = it, contentDescription = null, modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(record.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    val status = progress?.stage ?: record.detail ?: when (record.status) {
                        Status.COMPLETE -> "Скачано"
                        Status.CANCELLED -> "Отменено"
                        Status.FAILED -> "Ошибка загрузки"
                        Status.PAUSED -> "На паузе"
                        else -> "В очереди"
                    }
                    Text(status, color = if (record.status == Status.FAILED) palette.error else palette.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                val fraction = progress.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val detail = progressDetail(progress)
                if (detail.isNotBlank()) Text(detail, Modifier.padding(top = 6.dp),
                    color = palette.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun SettingsPanel(value: DownloadSettings, onChange: (DownloadSettings) -> Unit, notifications: () -> Unit) {
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Настройки")
        if (!value.audio) {
            val qualities = listOf(0 to "Максимальное", 1080 to "1080p", 720 to "720p", 480 to "480p").toMap()
            Picker("Качество", value.height, qualities) { onChange(value.copy(height = it)) }
            Picker("Предпочтительный формат", value.format, mapOf("mp4" to "MP4", "mkv" to "MKV", "source" to "Исходный")) {
                onChange(value.copy(format = it))
            }
        }
        Toggle("Только аудио (MP3)", value.audio) { onChange(value.copy(audio = it)) }
        Toggle("Только по Wi-Fi", value.wifi) { onChange(value.copy(wifi = it)) }
        Toggle("Скачивать сразу из меню «Поделиться»", value.automatic) { onChange(value.copy(automatic = it)) }
        Picker("Одновременные загрузки", value.parallel, (1..3).associateWith(Int::toString)) { onChange(value.copy(parallel = it)) }
        HorizontalDivider()
        TextButton(onClick = notifications) { Text("Разрешить уведомления о загрузках") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Picker(label: String, selected: T, choices: Map<T, String>, onChange: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) { Text(choices[selected].orEmpty()) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (key, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(key) })
                }
            }
        }
    }
}
