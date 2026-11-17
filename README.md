# Downloader

Android-загрузчик видео по HTTP/HTTPS. В приложении-источнике нажмите **Поделиться → Downloader**. Ссылка автоматически попадёт в очередь. Можно также вставить её на главном экране. Чтобы показывались уведомления на Android 13+, откройте «Настройки» в Downloader и разрешите уведомления.

Видео сохраняются в *Movies/Downloader* через MediaStore, аудио — в *Music/Downloader*. Настройки качества применяются при постановке загрузки в очередь. У прямого файла есть одно исходное качество. В адаптивных потоках процент относится к текущему потоку. При неизвестном общем объёме показываются полученные байты и скорость, когда движок их сообщает.

## Сборка и проверки

Требуются JDK 17+, Gradle 8.13, Android SDK 36, Build Tools 35.0.0, Bash, curl и sha256sum. Собираются arm64-v8a и x86_64. Минимальная версия Android — 10 (API 29), target SDK — 36. Для инструментальных тестов нужен FFmpeg с libx264.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
bash scripts/device-fixtures.sh
gradle :app:connectedDebugAndroidTest
```

APK находится в `app/build/outputs/apk/debug/app-debug.apk`. [GitHub Actions](https://github.com/smaks190-crypto/Downloader/actions/workflows/android.yml) публикует ZIP-артефакт **Downloader-debug** после сборки. В отдельной задаче запускаются проверки на x86_64-эмуляторе Android 17. Инструментальные тесты генерируют и используют локальные видео и потоки.

## Как работает

- `ShareIntentHandler` принимает SEND/VIEW. Room атомарно блокирует повторы активной ссылки. `DownloadQueueManager` сохраняет очередь и применяет ограничение одновременных загрузок.
- На Android 14+ используется JobScheduler (user-initiated data transfer), на Android 10–13 — foreground Worker. После остановки приложения через системный диспетчер на Android 14+ запуск можно возобновить вручную из очереди.
- yt-dlp обрабатывает поддерживаемые сайты, открытые HLS/DASH и запускает generic extractor для HTML. FFmpeg объединяет дорожки, меняет контейнер и извлекает MP3. OkHttp скачивает прямые видеофайлы и возобновляет частичный файл, если сервер поддерживает Range и выдаёт совпадающий сильный ETag.
- Открытые источники доступны в пределах возможностей движка. Страницы с обязательной авторизацией, DRM, закрытым доступом, сложными проверками браузера и истёкшими ссылками могут отклонять запрос. Приложение обращается к источнику медиа напрямую.
- Движок обновляется пересборкой APK. В `yt-dlp.lock` фиксируются версия и SHA-256 официального zipapp. Загрузчик пакует только файл, прошедший проверку хеша. npm, Python и FFmpeg ставить на телефон отдельно не нужно.

## Лицензии и исходники зависимостей

Код Downloader распространяется на условиях GPL-3.0. Текст лицензии включён в APK.

- [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760), GPL-3.0. Дерево этой версии содержит Android-исполняемые файлы FFmpeg, CPython и QuickJS, упакованные разработчиками библиотеки. [FFmpeg 7.0.1](https://ffmpeg.org/releases/ffmpeg-7.0.1.tar.xz), [CPython 3.12](https://github.com/python/cpython/tree/3.12), [QuickJS](https://bellard.org/quickjs/).
- [yt-dlp 2026.10.26](https://github.com/yt-dlp/yt-dlp/tree/2026.10.26), Unlicense. Используется официальный zipapp, включающий компоненты MIT и ISC. Уведомления поставляются внутри пакета.
- AndroidX, Kotlin, Coil и OkHttp распространяются по Apache-2.0.
