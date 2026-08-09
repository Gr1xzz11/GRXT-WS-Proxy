from __future__ import annotations

import asyncio
import logging
import signal
from pathlib import Path

from .state import CoreStatus, ProxyState
from .upstream import UpstreamSettings, run_upstream

LOG = logging.getLogger("grxt_ws_proxy.core")


class ProxyCore:
    def __init__(self, settings: UpstreamSettings | None = None) -> None:
        self.settings = settings or UpstreamSettings()
        self.state = ProxyState(
            listen_host=self.settings.host,
            listen_port=self.settings.port,
        )
        self._stop_event = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self._task is not None and not self._task.done():
            return

        self._stop_event = asyncio.Event()
        self.state.status = CoreStatus.STARTING
        self.state.last_error = None
        LOG.info("Starting real MTProto/WS core on %s:%d", self.settings.host, self.settings.port)

        self._task = asyncio.create_task(
            run_upstream(self.settings, self._stop_event),
            name="grxt-upstream-proxy",
        )
        await asyncio.sleep(0)

        if self._task.done():
            try:
                self._task.result()
            except Exception as exc:
                self.state.status = CoreStatus.ERROR
                self.state.last_error = str(exc)
                raise

        self.state.status = CoreStatus.RUNNING
        LOG.info("Proxy core started; Telegram link: %s", self.settings.telegram_link)

    async def stop(self) -> None:
        if self._task is None:
            self.state.status = CoreStatus.STOPPED
            return

        LOG.info("Stopping proxy core")
        self._stop_event.set()
        try:
            await asyncio.wait_for(self._task, timeout=5)
        except asyncio.TimeoutError:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
        finally:
            self._task = None
            self.state.status = CoreStatus.STOPPED

    async def run_forever(self) -> None:
        await self.start()
        assert self._task is not None
        try:
            await self._task
        finally:
            self.state.status = CoreStatus.STOPPED


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
    loop = asyncio.get_running_loop()
    shutdown = asyncio.Event()

    def request_shutdown() -> None:
        shutdown.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, request_shutdown)
        except (NotImplementedError, RuntimeError):
            pass

    await core.start()
    LOG.info("GRXT WS Proxy core is ready on 127.0.0.1:1443")
    await shutdown.wait()
    await core.stop()


def main() -> None:
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
