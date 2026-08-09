from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass, field

from proxy.config import proxy_config
from proxy.tg_ws_proxy import _run, parse_dc_ip_list


@dataclass(slots=True)
class UpstreamSettings:
    host: str = "127.0.0.1"
    port: int = 1443
    secret: str = field(default_factory=lambda: os.urandom(16).hex())
    dc_ip: tuple[str, ...] = (
        "2:149.154.167.220",
        "4:149.154.167.220",
    )
    buffer_kb: int = 256
    pool_size: int = 4
    fallback_cfproxy: bool = True
    cfproxy_domains: tuple[str, ...] = ()
    cfworker_domains: tuple[str, ...] = ()
    fake_tls_domain: str = ""

    @property
    def telegram_link(self) -> str:
        if self.fake_tls_domain:
            domain_hex = self.fake_tls_domain.encode("ascii").hex()
            secret = f"ee{self.secret}{domain_hex}"
        else:
            secret = f"dd{self.secret}"
        return f"tg://proxy?server={self.host}&port={self.port}&secret={secret}"


def apply_settings(settings: UpstreamSettings) -> None:
    proxy_config.host = settings.host
    proxy_config.port = settings.port
    proxy_config.secret = settings.secret
    proxy_config.dc_redirects = parse_dc_ip_list(list(settings.dc_ip))
    proxy_config.buffer_size = max(4, settings.buffer_kb) * 1024
    proxy_config.pool_size = max(0, settings.pool_size)
    proxy_config.fallback_cfproxy = settings.fallback_cfproxy
    proxy_config.cfproxy_user_domains = list(settings.cfproxy_domains)
    proxy_config.cfproxy_worker_domains = list(settings.cfworker_domains)
    proxy_config.fake_tls_domain = settings.fake_tls_domain
    proxy_config.proxy_protocol = False
    proxy_config.force_test_dc = False


async def run_upstream(settings: UpstreamSettings, stop_event: asyncio.Event) -> None:
    apply_settings(settings)
    await _run(stop_event)
