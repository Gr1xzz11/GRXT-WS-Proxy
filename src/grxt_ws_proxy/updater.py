from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import shlex
import stat
import subprocess
import sys
import tempfile
import urllib.request


class UpdateError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified(url: str, expected_sha256: str, *, timeout: float = 45.0) -> Path:
    if not url:
        raise UpdateError("GRXT API не вернул ссылку на обновление")
    if not expected_sha256:
        raise UpdateError("GRXT API не вернул SHA-256 обновления")
    target = Path(tempfile.mkdtemp(prefix="grxt-update-")) / "GRXT-WS-Proxy.grxt"
    request = urllib.request.Request(url, headers={"User-Agent": "GRXT-WS-Proxy-Updater"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response, target.open("wb") as out:
            shutil.copyfileobj(response, out)
    except Exception as exc:
        shutil.rmtree(target.parent, ignore_errors=True)
        raise UpdateError(f"Не удалось скачать обновление: {exc}") from exc
    actual = sha256_file(target)
    if actual.lower() != expected_sha256.strip().lower():
        shutil.rmtree(target.parent, ignore_errors=True)
        raise UpdateError("SHA-256 обновления не совпал. Файл удалён.")
    target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return target


def can_self_update() -> bool:
    return bool(getattr(sys, "frozen", False)) and os.name != "nt"


def schedule_linux_replace(downloaded: Path) -> None:
    if not can_self_update():
        raise UpdateError("Автозамена доступна только в собранной Linux .grxt версии")
    current = Path(sys.executable).resolve()
    if not os.access(current.parent, os.W_OK):
        raise UpdateError("Нет прав на замену установленного .grxt. Установите обновление вручную.")
    helper_dir = Path(tempfile.mkdtemp(prefix="grxt-updater-helper-"))
    helper = helper_dir / "apply-update.sh"
    backup = current.with_suffix(current.suffix + ".old")
    script = f"""#!/bin/sh
set -eu
PID={os.getpid()}
CURRENT={shlex.quote(str(current))}
NEW={shlex.quote(str(downloaded))}
BACKUP={shlex.quote(str(backup))}
while kill -0 \"$PID\" 2>/dev/null; do sleep 0.2; done
rm -f \"$BACKUP\"
if [ -e \"$CURRENT\" ]; then mv \"$CURRENT\" \"$BACKUP\"; fi
if mv \"$NEW\" \"$CURRENT\"; then
  chmod +x \"$CURRENT\"
  \"$CURRENT\" >/dev/null 2>&1 &
  sleep 1
  rm -f \"$BACKUP\"
else
  [ -e \"$BACKUP\" ] && mv \"$BACKUP\" \"$CURRENT\"
  exit 1
fi
rm -rf {shlex.quote(str(helper_dir))}
"""
    helper.write_text(script, encoding="utf-8")
    helper.chmod(0o700)
    subprocess.Popen(["/bin/sh", str(helper)], start_new_session=True, close_fds=True)
