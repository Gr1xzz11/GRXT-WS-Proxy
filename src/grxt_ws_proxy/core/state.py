from __future__ import annotations

from dataclasses import dataclass, asdict
from enum import StrEnum


class CoreStatus(StrEnum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    DEGRADED = "degraded"
    ERROR = "error"


@dataclass(slots=True)
class ProxyState:
    status: CoreStatus = CoreStatus.STOPPED
    route: str = "auto"
    listen_host: str = "127.0.0.1"
    listen_port: int = 1443
    telegram_reachable: bool | None = None
    latency_ms: float | None = None
    last_error: str | None = None

    def to_dict(self) -> dict[str, object]:
        return asdict(self)
