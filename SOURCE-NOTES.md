# Исходники движка и повторная сборка

В релизе находится **Neon-Player-0.3.7-dependency-sources.tar**: архивы соответствующих исходников libmpv, FFmpeg, зависимостей native-движка, подмодулей libplacebo и FreeType, а также yt-dlp и Android-обвязки. Точные адреса, ревизии и SHA256 — в [NATIVE-SOURCES.json](NATIVE-SOURCES.json).

Native-библиотеки не изменены относительно mpv-android 2026-09-17. Скрипты сборки, JNI и исправления upstream находятся в vendor/upstream/mpv-android-2026-09-17/buildscripts. Версии перечислены в UPSTREAM-RELEASE.txt. Распакуйте архивы зависимостей в соответствующие каталоги buildscripts/deps без верхней папки архива; исходники подмодулей — по путям из .gitmodules. Для native-сборки используется Android NDK r30 и upstream buildall.sh. При публикации движок не пересобирался с нуля: проверены готовые библиотеки официального выпуска.

## Полная версия Android

Нужны JDK 17, Android SDK 35, Python 3 и доступ к Maven Central / Google Maven.

```powershell
python bootstrap.py
.\gradlew.bat :app:exportDebugApks :app:testDebugUnitTest
```

bootstrap.py загружает официальный APK mpv-android по зафиксированному адресу, проверяет SHA256 и извлекает только библиотеки трёх нужных ABI. Свои исходники, шейдеры и обвязка уже находятся в репозитории: внешние папки Windows/Lite не требуются. APK появляются в dist/. Для модифицированной сборки используйте собственную подпись — закрытые ключи автора не публикуются.

## Lite 0.6.0

Исходники находятся в lite/. Для Windows с JDK 17, SDK 35 и Python:

```powershell
python lite/build-with-sdk.py путь-к-новому-apk.apk
```

Скрипт получает Java-зависимости из Maven Central, компилирует Java/ресурсы Android и создаёт APK с локальной отладочной подписью. Тексты лицензий этих зависимостей сохраняются в APK. Прежняя подпись APK Lite отличается от полной версии; это отдельные приложения с разными package ID.
