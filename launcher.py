#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

try:
    from grxt_ws_proxy.app import main
except ModuleNotFoundError as exc:
    if exc.name == "proxy":
        raise SystemExit(
            "Не найден сетевой движок tg-ws-proxy.\n"
            "Запусти ./GRXT-WS-Proxy.run — он создаст .venv и установит зависимости."
        ) from exc
    raise

if __name__ == "__main__":
    main()
