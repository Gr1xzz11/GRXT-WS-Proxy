from __future__ import annotations

import os
import socket
import subprocess
import sys
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import ttk

from .settings import load_settings


class GRXTProxyApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("GRXT WS Proxy")
        self.geometry("760x520")
        self.minsize(640, 440)

        self.settings = load_settings()
        self.core_process: subprocess.Popen[bytes] | None = None
        self.keep_background = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="Прокси выключен")
        self.route_var = tk.StringVar(value="Auto / WebSocket + fallback")
        self.telegram_var = tk.StringVar(value="Telegram: ещё не проверялся")
        self.ws_var = tk.StringVar(value="WebSocket: ещё не проверялся")

        self._build_ui()
        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.after(500, self._refresh_status)

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=24)
        root.pack(fill="both", expand=True)

        ttk.Label(root, text="GRXT WS Proxy", font=("Sans", 24, "bold")).pack(anchor="w")
        ttk.Label(
            root,
            text="Локальный MTProto → WebSocket/TLS прокси для Telegram Desktop",
        ).pack(anchor="w", pady=(4, 24))

        status = ttk.LabelFrame(root, text="Состояние", padding=18)
        status.pack(fill="x")
        ttk.Label(status, textvariable=self.status_var, font=("Sans", 15, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Label(status, text=f"{self.settings.host}:{self.settings.port}").grid(row=1, column=0, sticky="w", pady=(8, 0))
        ttk.Label(status, text="Маршрут:").grid(row=0, column=1, sticky="e", padx=(30, 8))
        ttk.Label(status, textvariable=self.route_var).grid(row=0, column=2, sticky="w")
        status.columnconfigure(0, weight=1)

        actions = ttk.Frame(root)
        actions.pack(fill="x", pady=20)
        self.toggle_button = ttk.Button(actions, text="Включить", command=self._toggle)
        self.toggle_button.pack(side="left")
        ttk.Button(actions, text="Подключить Telegram", command=self._open_telegram).pack(side="left", padx=8)
        ttk.Button(actions, text="Открыть лог", command=self._open_log).pack(side="left")

        notebook = ttk.Notebook(root)
        notebook.pack(fill="both", expand=True, pady=(8, 0))

        diagnostics = ttk.Frame(notebook, padding=16)
        logs = ttk.Frame(notebook, padding=16)
        settings = ttk.Frame(notebook, padding=16)
        notebook.add(diagnostics, text="Диагностика")
        notebook.add(logs, text="Логи")
        notebook.add(settings, text="Настройки")

        ttk.Label(diagnostics, textvariable=self.telegram_var).pack(anchor="w")
        ttk.Label(diagnostics, textvariable=self.ws_var).pack(anchor="w", pady=6)
        ttk.Label(diagnostics, text="Режим: upstream v1.9.1 core").pack(anchor="w")

        self.log_box = tk.Text(logs, height=10, state="disabled", wrap="word")
        self.log_box.pack(fill="both", expand=True)

        ttk.Checkbutton(
            settings,
            text="Оставлять proxy core запущенным после закрытия GUI",
            variable=self.keep_background,
        ).pack(anchor="w")
        ttk.Label(
            settings,
            text="Трей не используется. На GNOME приложение работает обычным окном.",
        ).pack(anchor="w", pady=(10, 0))

    def _core_is_running(self) -> bool:
        if self.core_process is not None and self.core_process.poll() is None:
            return True
        try:
            with socket.create_connection((self.settings.host, self.settings.port), timeout=0.2):
                return True
        except OSError:
            return False

    def _start_core(self) -> None:
        if self._core_is_running():
            return

        self.status_var.set("Запуск прокси…")
        self.core_process = subprocess.Popen(
            [sys.executable, "-m", "grxt_ws_proxy.core.service"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=(os.name != "nt"),
        )

    def _stop_core(self) -> None:
        process = self.core_process
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=6)
            except subprocess.TimeoutExpired:
                process.kill()
        self.core_process = None

    def _toggle(self) -> None:
        if self._core_is_running():
            self._stop_core()
        else:
            self._start_core()
        self.after(300, self._refresh_status)

    def _open_telegram(self) -> None:
        if not self._core_is_running():
            self._start_core()
        webbrowser.open(self.settings.telegram_link)

    def _log_path(self) -> Path:
        return Path.home() / ".local" / "state" / "grxt-ws-proxy" / "proxy.log"

    def _read_log_tail(self) -> str:
        path = self._log_path()
        if not path.exists():
            return "Лог пока пуст."
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            return "\n".join(lines[-120:])
        except OSError as exc:
            return f"Не удалось прочитать лог: {exc}"

    def _open_log(self) -> None:
        path = self._log_path()
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        webbrowser.open(path.as_uri())

    def _refresh_status(self) -> None:
        running = self._core_is_running()
        self.status_var.set("Прокси работает" if running else "Прокси выключен")
        self.toggle_button.configure(text="Выключить" if running else "Включить")
        self.telegram_var.set("Telegram proxy endpoint: доступен" if running else "Telegram proxy endpoint: выключен")
        self.ws_var.set("WebSocket/Fallback: активируется при соединении Telegram" if running else "WebSocket/Fallback: выключен")

        text = self._read_log_tail()
        self.log_box.configure(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.insert("1.0", text)
        self.log_box.configure(state="disabled")

        self.after(1500, self._refresh_status)

    def _on_close(self) -> None:
        if not self.keep_background.get():
            self._stop_core()
        self.destroy()


def main() -> None:
    app = GRXTProxyApp()
    app.mainloop()


if __name__ == "__main__":
    main()
