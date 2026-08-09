# GRXT WS Proxy

A desktop-first Telegram local proxy project based on the architecture of Flowseal/tg-ws-proxy.

## Goals

- Reliable Telegram connectivity when direct connections are unavailable or degraded.
- First-class Linux/GNOME and Windows support.
- No system-tray dependency.
- Separate GUI and proxy core so the core can run independently.
- Automatic route selection, health checks, fallback and recovery.
- Built-in diagnostics and readable logs.

## Planned architecture

```text
Telegram Desktop
      |
      v
Local MTProto endpoint (127.0.0.1:1443)
      |
      v
GRXT Proxy Core
      |
      +--> Auto route / health checks
      |
      +--> WebSocket/TLS transport
      +--> Cloudflare-compatible route
      +--> Direct fallback (optional)
      |
      v
Telegram DC

GRXT Desktop GUI <---- local control API ----> GRXT Proxy Core
```

## Desktop behavior

The application will use a normal desktop window instead of a tray icon. Closing the GUI can either stop the proxy or leave the core running in the background, depending on user settings.

## Status

Initial repository scaffold. The upstream networking core and v1.9.1 behavior will be integrated incrementally while the desktop/control layer is redesigned.

## Upstream

Architecture inspired by and intended to reuse compatible MIT-licensed components from:

- Flowseal/tg-ws-proxy

Upstream copyright and license notices must be preserved for reused code.
