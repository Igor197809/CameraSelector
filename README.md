# CameraSelector

Простое Android-приложение без сторонних библиотек.

## Возможности
- вывод списка Camera ID, которые Android разрешает открыть приложению;
- подпись направления камеры (задняя/передняя/внешняя) и доступных фокусных расстояний;
- живой предпросмотр;
- фото JPEG;
- видео MP4 (H.264 + AAC);
- сохранение в `DCIM/CameraSelector` и появление в Галерее;
- Android 7.0+ (minSdk 24).

## Сборка в Android Studio
1. Откройте папку `CameraSelectorApp` как проект.
2. Дождитесь Gradle Sync.
3. Build > Build APK(s).
4. APK будет в `app/build/outputs/apk/debug/app-debug.apk`.

Проект использует только системный Camera2 API и Android SDK, внешних библиотек нет.
