# GRXT WS Proxy

Desktop-first local Telegram MTProto → WebSocket/TLS proxy for Linux/GNOME and Windows.

The project uses the networking engine from Flowseal/tg-ws-proxy pinned to commit `b8a563400805c6993256b2333149fd847cfa5bbf` and replaces the tray-first desktop behavior with a normal application window.

## What works

- Local MTProto endpoint on `127.0.0.1:1443`.
- Telegram Desktop connection through `tg://proxy`.
- WebSocket/TLS transport to Telegram DCs.
- Automatic upstream fallback behavior, including Cloudflare-compatible and direct TCP fallback from the pinned upstream engine.
- Persistent MTProto secret in `~/.config/grxt-ws-proxy/config.json`.
- Separate GUI and background proxy-core process.
- No tray/AppIndicator dependency.
- Live proxy logs in the GUI and in `~/.local/state/grxt-ws-proxy/proxy.log`.
- Option to leave the core running after the GUI closes.
- Linux and Windows PyInstaller build workflow.

## Linux / GNOME

Requirements: Python 3.11+, Tk, Git.

### Run from source

```bash
git clone https://github.com/Gr1xzz11/GRXT-WS-Proxy.git
cd GRXT-WS-Proxy
python3 -m venv .venv
source .venv/bin/activate
pip install -U pip
pip install .
grxt-ws-proxy
```

On Arch/CachyOS, install Tk first if necessary:

```bash
sudo pacman -S tk
```

### Install into the application menu

```bash
bash scripts/install_linux.sh
```

Then launch **GRXT WS Proxy** from GNOME's application menu.

## Windows

```powershell
git clone https://github.com/Gr1xzz11/GRXT-WS-Proxy.git
cd GRXT-WS-Proxy
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -U pip
pip install .
grxt-ws-proxy
```

GitHub Actions also builds a standalone `GRXT-WS-Proxy.exe` artifact.

## Usage

1. Start GRXT WS Proxy.
2. Click **Включить**.
3. Wait until the status says **Прокси работает**.
4. Click **Подключить Telegram**.
5. Telegram Desktop opens the generated local MTProto proxy link; confirm it in Telegram.

The proxy secret is generated once and then reused. The GUI itself does not need to remain open if **Оставлять proxy core запущенным после закрытия GUI** is enabled.

## Architecture

```text
Telegram Desktop
      |
      v
127.0.0.1:1443 (MTProto)
      |
      v
GRXT Proxy Core
      |
      +--> WebSocket/TLS
      +--> Cloudflare-compatible fallback
      +--> direct TCP fallback
      |
      v
Telegram DC

GRXT Desktop GUI ---- process lifecycle ----> GRXT Proxy Core
```

## Upstream and license

The networking engine is supplied by the MIT-licensed Flowseal/tg-ws-proxy project and is intentionally pinned to a specific upstream commit for reproducible behavior. Preserve the upstream copyright/license notices when redistributing its code or binaries.
