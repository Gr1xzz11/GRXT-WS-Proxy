from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path

from .core.upstream import UpstreamSettings


def config_dir() -> Path:
    root = os.environ.get("XDG_CONFIG_HOME")
    if root:
        return Path(root) / "grxt-ws-proxy"
    return Path.home() / ".config" / "grxt-ws-proxy"


def config_path() -> Path:
    return config_dir() / "config.json"


def load_settings() -> UpstreamSettings:
    path = config_path()
    if not path.exists():
        settings = UpstreamSettings()
        save_settings(settings)
        return settings

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return UpstreamSettings(
            host=str(data.get("host", "127.0.0.1")),
            port=int(data.get("port", 1443)),
            secret=str(data.get("secret") or os.urandom(16).hex()),
            dc_ip=tuple(data.get("dc_ip") or ("2:149.154.167.220", "4:149.154.167.220")),
            buffer_kb=int(data.get("buffer_kb", 256)),
            pool_size=int(data.get("pool_size", 4)),
            fallback_cfproxy=bool(data.get("fallback_cfproxy", True)),
            cfproxy_domains=tuple(data.get("cfproxy_domains") or ()),
            cfworker_domains=tuple(data.get("cfworker_domains") or ()),
            fake_tls_domain=str(data.get("fake_tls_domain", "")),
        )
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        settings = UpstreamSettings()
        save_settings(settings)
        return settings


def save_settings(settings: UpstreamSettings) -> None:
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = asdict(settings)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
