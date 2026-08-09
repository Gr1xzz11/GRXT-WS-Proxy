# GRXT WS Proxy

Desktop-first local Telegram MTProto → WebSocket/TLS proxy based on the networking engine from Flowseal/tg-ws-proxy.

## Current state

The first working integration is now in place:

- normal desktop window, no tray requirement;
- Linux/GNOME-friendly behavior;
- GUI and proxy core run as separate processes;
- real upstream MTProto/WebSocket engine is started by the GRXT core;
- persistent MTProto secret in `~/.config/grxt-ws-proxy/config.json`;
- local endpoint defaults to `127.0.0.1:1443`;
- `tg://proxy` link can be opened directly from the GUI;
- upstream WebSocket and fallback logic remains available;
- logs are written to `~/.local/state/grxt-ws-proxy/proxy.log`.

## Install from source

Python 3.11+ and Git are required for the current development build.

```bash
git clone https://github.com/Gr1xzz11/GRXT-WS-Proxy.git
cd GRXT-WS-Proxy
git checkout agent/initial-desktop-core
python -m venv .venv
source .venv/bin/activate
pip install -e .
grxt-ws-proxy
```

The current build pins the upstream networking engine to commit:

`b8a563400805c6993256b2333149fd847cfa5bbf`

## Architecture

```text
Telegram Desktop
      |
      v
127.0.0.1:1443
      |
      v
GRXT Proxy Core
      |
      v
Flowseal MTProto/WS engine
      |
      +--> WebSocket/TLS
      +--> Cloudflare-compatible fallback
      +--> direct fallback where available
      |
      v
Telegram DC

GRXT Desktop GUI ---- process lifecycle ----> GRXT Proxy Core
```

The GUI intentionally does not depend on a system-tray icon. Closing the GUI stops the core by default; the user can choose to leave the core running in the background.

## Next work

- remove the temporary Git dependency by vendoring/refactoring the required MIT-licensed networking modules;
- add real route health checks and latency measurements;
- add Auto Route scoring and automatic recovery;
- expose route/fallback status in the GUI;
- add systemd user autostart for Linux;
- add Windows packaging and Linux standalone packaging.

## Upstream and license

Networking behavior is based on MIT-licensed code from Flowseal/tg-ws-proxy. Upstream copyright and license notices must be preserved for reused code.
