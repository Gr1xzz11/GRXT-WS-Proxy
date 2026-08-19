#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

try:
    # In a PyInstaller one-file build sys.executable points back to this EXE.
    # app.py starts the core as: <exe> -m grxt_ws_proxy.core.service.
    # Route that invocation directly to the core instead of opening another GUI.
    core_requested = (
        "--core" in sys.argv[1:]
        or sys.argv[1:3] == ["-m", "grxt_ws_proxy.core.service"]
    )
    if core_requested:
        from grxt_ws_proxy.core.service import main
    else:
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
