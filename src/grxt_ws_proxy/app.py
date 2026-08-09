from __future__ import annotations

import tkinter as tk
from tkinter import ttk


class GRXTProxyApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("GRXT WS Proxy")
        self.geometry("720x480")
        self.minsize(620, 420)

        self.connected = False
        self.status_var = tk.StringVar(value="Прокси выключен")
        self.route_var = tk.StringVar(value="Auto")

        self._build_ui()

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=24)
        root.pack(fill="both", expand=True)

        ttk.Label(root, text="GRXT WS Proxy", font=("Sans", 24, "bold")).pack(anchor="w")
        ttk.Label(
            root,
            text="Локальное подключение Telegram с автоматическим выбором маршрута",
        ).pack(anchor="w", pady=(4, 24))

        status = ttk.LabelFrame(root, text="Состояние", padding=18)
        status.pack(fill="x")
        ttk.Label(status, textvariable=self.status_var, font=("Sans", 15, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Label(status, text="127.0.0.1:1443").grid(row=1, column=0, sticky="w", pady=(8, 0))
        ttk.Label(status, text="Маршрут:").grid(row=0, column=1, sticky="e", padx=(30, 8))
        ttk.Label(status, textvariable=self.route_var).grid(row=0, column=2, sticky="w")
        status.columnconfigure(0, weight=1)

        actions = ttk.Frame(root)
        actions.pack(fill="x", pady=20)
        self.toggle_button = ttk.Button(actions, text="Включить", command=self._toggle)
        self.toggle_button.pack(side="left")
        ttk.Button(actions, text="Подключить Telegram", command=self._open_telegram).pack(side="left", padx=8)

        notebook = ttk.Notebook(root)
        notebook.pack(fill="both", expand=True, pady=(8, 0))

        diagnostics = ttk.Frame(notebook, padding=16)
        logs = ttk.Frame(notebook, padding=16)
        settings = ttk.Frame(notebook, padding=16)
        notebook.add(diagnostics, text="Диагностика")
        notebook.add(logs, text="Логи")
        notebook.add(settings, text="Настройки")

        ttk.Label(diagnostics, text="Telegram: ещё не проверялся").pack(anchor="w")
        ttk.Label(diagnostics, text="WebSocket: ещё не проверялся").pack(anchor="w", pady=6)
        ttk.Label(diagnostics, text="Задержка: —").pack(anchor="w")

        log_box = tk.Text(logs, height=10, state="disabled", wrap="word")
        log_box.pack(fill="both", expand=True)

        ttk.Checkbutton(settings, text="Оставлять proxy core запущенным после закрытия GUI").pack(anchor="w")
        ttk.Checkbutton(settings, text="Запускать вместе с системой").pack(anchor="w", pady=8)

    def _toggle(self) -> None:
        # Temporary UI lifecycle until the local core control API lands.
        self.connected = not self.connected
        self.status_var.set("Прокси работает" if self.connected else "Прокси выключен")
        self.toggle_button.configure(text="Выключить" if self.connected else "Включить")

    def _open_telegram(self) -> None:
        # Will be replaced with a generated tg://proxy link from core config.
        self.status_var.set("Сначала будет создан MTProto secret")


def main() -> None:
    app = GRXTProxyApp()
    app.mainloop()


if __name__ == "__main__":
    main()
