from __future__ import annotations

from dataclasses import dataclass

try:
    import keyring  # type: ignore
    from keyring.errors import KeyringError  # type: ignore
except Exception:  # pragma: no cover
    keyring = None
    KeyringError = Exception

SERVICE = "GRXT WS Proxy"


@dataclass(slots=True)
class StoredSession:
    refresh_token: str = ""
    grxt_id: str = ""
    email: str = ""


class SessionStore:
    """Refresh tokens are persisted only through an OS keyring.

    If no working keyring is available the app remains usable for the current
    process, but deliberately does not fall back to a plaintext token file.
    """

    def load(self) -> StoredSession:
        if keyring is None:
            return StoredSession()
        try:
            return StoredSession(
                refresh_token=keyring.get_password(SERVICE, "refresh_token") or "",
                grxt_id=keyring.get_password(SERVICE, "grxt_id") or "",
                email=keyring.get_password(SERVICE, "email") or "",
            )
        except KeyringError:
            return StoredSession()

    def save(self, refresh_token: str, grxt_id: str, email: str) -> bool:
        if keyring is None:
            return False
        try:
            keyring.set_password(SERVICE, "refresh_token", refresh_token)
            keyring.set_password(SERVICE, "grxt_id", grxt_id)
            keyring.set_password(SERVICE, "email", email)
            return True
        except KeyringError:
            return False

    def clear(self) -> None:
        if keyring is None:
            return
        for name in ("refresh_token", "grxt_id", "email"):
            try:
                keyring.delete_password(SERVICE, name)
            except Exception:
                pass
