from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

from .state import CoreStatus, ProxyState

LOG = logging.getLogger("grxt_ws_proxy.core")


class ProxyCore:
    """Lifecycle shell for the networking core.

    The upstream MTProto/WebSocket implementation will be adapted behind this
    interface. Keeping lifecycle/state separate prevents the desktop UI from
    owning network sockets and makes headless operation possible.
    """

    def __init__(self) -> None:
        self.state = ProxyState()
        self._stop_event = asyncio.Event()

    async def start(self) -> None:
        if self.state.status is not CoreStatus.STOPPED:
            return
        self.state.status = CoreStatus.STARTING
        LOG.info("Starting proxy core on %s:%d", self.state.listen_host, self.state.listen_port)

        # TODO: attach adapted Flowseal MTProto -> WebSocket transport here.
        self.state.status = CoreStatus.RUNNING

    async def stop(self) -> None:
        LOG.info("Stopping proxy core")
        self._stop_event.set()
        self.state.status = CoreStatus.STOPPED

    async def run_forever(self) -> None:
        await self.start()
        await self._stop_event.wait()


def configure_logging() -> None:
    log_dir = Path.home() / ".local" / "state" / "grxt-ws-proxy"
    log_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=[
            logging.FileHandler(log_dir / "proxy.log", encoding="utf-8"),
            logging.StreamHandler(),
        ],
    )


async def _main() -> None:
    configure_logging()
    core = ProxyCore()
    await core.run_forever()


def main() -> None:
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
