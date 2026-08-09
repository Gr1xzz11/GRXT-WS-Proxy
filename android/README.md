# GRXT WS Proxy for Android

Native Android client for running a local Telegram MTProto proxy on the phone.

## Requirements

- Android 8.0 (API 26) or newer.
- Telegram installed on the same device.
- Network access.

## Usage

1. Install the APK.
2. Open **GRXT WS Proxy** and allow notifications when Android asks. The notification is used by the foreground proxy service.
3. Tap **Включить**.
4. Wait for **Прокси работает**.
5. Tap **Подключить Telegram** and confirm the MTProto proxy in Telegram.
6. You can close the app window; the proxy continues while the foreground-service notification is present.
7. Stop it from the app or from the notification action.

## Architecture

```text
Telegram Android
      |
      v
127.0.0.1:1443 (local MTProto)
      |
      v
GRXT Android foreground service
      |
      +--> WebSocket/TLS to Telegram web endpoints
      +--> direct Telegram TCP fallback
      |
      v
Telegram DC
```

The MTProto secret is generated once and stored in Android SharedPreferences.

## Build

From the repository root:

```bash
gradle -p android :app:assembleDebug
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions also publishes the `GRXT-WS-Proxy-Android` artifact for each Android build.

See `NOTICE` for upstream attribution.
