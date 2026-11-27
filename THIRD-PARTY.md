# Происхождение компонентов

- **Android bridge / native binaries:** [mpv-android](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17), тег 2026-09-17, upstream commit fdf74f6. MIT обвязки — `licenses/mpv-android-MIT.txt`. Пакет native содержит libmpv, FFmpeg, зависимости; MIT обвязки не перелицензирует их. Полный список версий — `UPSTREAM-RELEASE.txt`.
- Официальный upstream APK SHA256: `a502ea6b6357d1e48a3134f9bd848484990cdda18836bd8445523538d0f4a887`. Источник `.so` — `app-default-universal-debug.apk`, извлечены arm64-v8a / armeabi-v7a / x86_64. Это бинарная зависимость, не перепакованный пользовательский интерфейс mpv-android.
- **mpv / libmpv:** [Copyright](https://github.com/mpv-player/mpv/blob/master/Copyright), по умолчанию GPLv2+, условия зависят от конкретной сборки и зависимостей. Здесь используется обычная официальная сборка, не заявляется LGPL-only. Native upstream commit libmpv `0b7ed67` и FFmpeg `094a2f8` согласно release metadata. У исходников и бинарников native свои условия.
- Рецепты upstream сборки и JNI-исходники того же тега скачаны в `vendor/upstream/mpv-android-2026-09-17`; см. `buildscripts`. Официальный релиз фиксирует коммиты зависимостей. Для публичного распространения нужно отдельно подготовить полный комплект соответствующих native-исходников/патчей и условий; этот локальный alpha-пакет не объявлен публичным релизом.
- **SMBJ 0.14.0**, **Commons Net 3.11.1**, **JSch mwiede 0.2.21**, AndroidX и Kotlin подключаются через Maven Central/Google; лицензии зависимостей сохраняются в их артефактах. SMB-адаптер, обнаружение и SubRip переиспользованы из собственного Neon Lite.
- **ПК-шейдеры:** взяты из собственной сборки mpv Neon v12, без изменения исходных attribution. Источники и лицензии — `licenses/PC-SOURCES.json`, `licenses/pc-components`. Профили Anime4K повторяют шаблоны Windows; производительность на Android отдельно не гарантируется.
- **SponsorBlock:** клиент написан самостоятельно по публичному API. Данные/API — CC BY-NC-SA 4.0, https://sponsor.ajay.app/ . Записи сегментов не отправляются, статистика просмотров не отправляется.
- **JacRed / TorrServer:** API-клиенты, серверы и базы в APK не входят. Код серверов не копировался в клиент. Документы: https://github.com/jacred-fdb/jacred и https://github.com/YouROK/TorrServer .
- **Plllay:** визуальный/эргономический ориентир; ни Kotlin/XML/JS-код, ни иконки/изображения в приложении не используются. Скачанная исследовательская копия остаётся отдельно в `../research/Plllay`.
- **Неоновая уточка и TV banner:** сгенерированы встроенным image_gen 24 ноября 2026; промпты и пути в `ART-PROMPTS.md`. Собственные ControlButton/SeekTape нарисованы кодом, не копируют набор иконок Plllay.


AndroidX WindowManager 1.3.0 добавлен для FoldingFeature/WindowInfoTracker (Apache-2.0, AndroidX). Код PixelCopy-подсветки, точной перемотки, навигации и защищённого хранилища написан в Neon; исходники mpvRx/Mpv-infinity не переносились.


## AniSkip API (alpha.6)

Собственный небольшой клиент публичного API; сторонние исходники не копируются и не включаются в APK. Формат запроса/ответа сверен с официальным [клиентом](https://github.com/aniskip/aniskip-extension/blob/main/src/api/aniskip-http-client/aniskip-http-client.ts) и [типами](https://github.com/aniskip/aniskip-extension/blob/main/src/api/aniskip-http-client/aniskip-http-client.types.ts). Серверный проект [aniskip-api](https://github.com/aniskip/aniskip-api) распространяется под MIT; это не заявление о собственных правах на данные сервиса. Запрос выполняется только по явному действию пользователя (MAL ID, серия, длительность).


## Lucide control icons (alpha.7)

Набор линейных значков из [официального Lucide](https://github.com/lucide-icons/lucide/tree/main/icons), получен 24 ноября 2026. ISC License, Copyright © 2026 Lucide Icons and Contributors. Исходные SVG сохранены в docs/design/control-icons; нормализованные пути используются в ControlGlyphs.kt. Лицензия сохранена в licenses/lucide/LICENSE и включена в APK как assets/licenses/LUCIDE.txt. Остальные рисунки и play/pause — собственные.


## YouTube · 0.3.6

Подключён io.github.junkfood02.youtubedl-android:library:0.18.1 (GPL-3.0), официальный источник https://github.com/yausername/youtubedl-android . Используется штатный API init/execute/updateYoutubeDL; код стороннего клиента не копируется. В библиотеке поставляются yt-dlp и его runtime; отдельный FFmpeg/aria2 модуль не добавлен: видео и аудио воспроизводит libmpv, без скачивания/сборки файла на диске. Лицензия wrapper сохранена в APK assets/licenses/youtubedl-android-GPL-3.0.txt; ранее сохранённая yt-dlp.txt остаётся. Нативные файлы извлекаются стандартным Android-пакетным менеджером (legacy JNI packaging), как требует upstream.

В 0.3.6 дополнительно включён официальный yt-dlp zipapp 2026.11.23 (проверка SHA256 по release SHA2-256SUMS), чтобы первая попытка не использовала старый скрипт из wrapper. Исходники/хеш: licenses/YTDLP-2026.11.23-SOURCE.txt, бинарный zipapp: res/raw/neon_ytdlp. Более свежая явно обновлённая пользователем версия не откатывается.
