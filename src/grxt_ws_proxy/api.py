from __future__ import annotations

from dataclasses import dataclass
import json
import platform
import socket
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from . import __version__

API_BASE = "https://api.grxt.dev"
APP_ID = "grxt-ws-proxy"
CHANNEL = "stable"


class GrxtApiError(RuntimeError):
    def __init__(self, code: str, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.status = status


@dataclass(slots=True)
class Session:
    access_token: str
    refresh_token: str
    expires_in: int
    grxt_id: str
    email: str
    email_verified: bool = False


@dataclass(slots=True)
class VersionPolicy:
    current: str
    latest: str
    minimum: str
    update_available: bool
    update_required: bool
    download_url: str = ""
    sha256: str = ""
    notes_url: str = ""


class GrxtApi:
    def __init__(self, base_url: str = API_BASE, timeout: float = 12.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None,
                 access_token: str | None = None) -> dict[str, Any]:
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=body, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Content-Type", "application/json")
        request.add_header("User-Agent", f"GRXT-WS-Proxy/{__version__} ({platform.system()})")
        request.add_header("X-GRXT-App", APP_ID)
        request.add_header("X-GRXT-Version", __version__)
        request.add_header("X-GRXT-Platform", platform.system().lower())
        if access_token:
            request.add_header("Authorization", f"Bearer {access_token}")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8", errors="replace")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            code, message = self._parse_error(raw, exc.code)
            raise GrxtApiError(code, message, exc.code) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise GrxtApiError("service_unavailable", f"GRXT API недоступен: {exc}") from exc

    @staticmethod
    def _parse_error(raw: str, status: int) -> tuple[str, str]:
        try:
            data = json.loads(raw)
            err = data.get("error")
            if isinstance(err, dict):
                return str(err.get("code") or f"http_{status}"), str(err.get("message") or err.get("code") or "Ошибка GRXT API")
            detail = data.get("detail")
            if isinstance(detail, str):
                return detail, detail
            return str(data.get("code") or f"http_{status}"), str(data.get("message") or detail or f"HTTP {status}")
        except Exception:
            return f"http_{status}", raw.strip() or f"HTTP {status}"

    @staticmethod
    def _session(data: dict[str, Any]) -> Session:
        user = data.get("user") or data.get("account") or {}
        if not isinstance(user, dict):
            user = {}
        access = str(data.get("access_token") or "")
        refresh = str(data.get("refresh_token") or "")
        if not access:
            raise GrxtApiError("invalid_response", "GRXT API не вернул access token")
        return Session(
            access_token=access,
            refresh_token=refresh,
            expires_in=int(data.get("expires_in") or 900),
            grxt_id=str(user.get("grxt_id") or data.get("grxt_id") or ""),
            email=str(user.get("email") or data.get("email") or ""),
            email_verified=bool(user.get("email_verified", data.get("email_verified", False))),
        )

    def login(self, login: str, password: str, device_id: str) -> Session:
        payload = {
            "login": login.strip(),
            "password": password,
            "device": {
                "device_id": device_id,
                "device_name": socket.gethostname() or "Linux PC",
                "platform": "linux",
                "app_name": APP_ID,
                "app_version": __version__,
            },
        }
        return self._session(self._request("POST", "/v1/auth/login", payload))

    def refresh(self, refresh_token: str, device_id: str) -> Session:
        payload = {"refresh_token": refresh_token}
        return self._session(self._request("POST", "/v1/auth/refresh", payload))

    def logout(self, access_token: str, refresh_token: str = "") -> None:
        self._request("POST", "/v1/auth/logout", None, access_token)

    def me(self, access_token: str) -> dict[str, Any]:
        return self._request("GET", "/v1/account/me", access_token=access_token)

    def version_policy(self) -> VersionPolicy:
        query = urllib.parse.urlencode({"app": APP_ID, "platform": "linux", "version": __version__, "channel": CHANNEL})
        data = self._request("GET", f"/v1/client/version?{query}")
        release = data.get("release") if isinstance(data.get("release"), dict) else {}
        latest = str(data.get("latest") or data.get("latest_version") or __version__)
        minimum = str(data.get("minimum") or data.get("minimum_version") or __version__)
        return VersionPolicy(
            current=str(data.get("current") or __version__),
            latest=latest,
            minimum=minimum,
            update_available=bool(data.get("update_available", latest != __version__)),
            update_required=bool(data.get("update_required", False)),
            download_url=str(release.get("download_url") or data.get("download_url") or ""),
            sha256=str(release.get("sha256") or data.get("sha256") or ""),
            notes_url=str(release.get("notes_url") or data.get("notes_url") or ""),
        )


def load_or_create_device_id() -> str:
    path = Path.home() / ".config" / "grxt-ws-proxy" / "device-id"
    try:
        if path.exists():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        path.parent.mkdir(parents=True, exist_ok=True)
        value = str(uuid.uuid4())
        path.write_text(value, encoding="utf-8")
        try:
            path.chmod(0o600)
        except OSError:
            pass
        return value
    except OSError:
        return str(uuid.uuid4())
