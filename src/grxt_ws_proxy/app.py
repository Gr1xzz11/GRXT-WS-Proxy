from __future__ import annotations

import os
import socket
import subprocess
import sys
import threading
import tkinter as tk
import webbrowser
from pathlib import Path

from . import __version__
from .settings import load_settings
from .api import GrxtApi, GrxtApiError, load_or_create_device_id
from .session_store import SessionStore
from .updater import UpdateError, can_self_update, download_verified, schedule_linux_replace

BG = "#07090D"
SIDEBAR = "#0B0F16"
SURFACE = "#10141C"
SURFACE_2 = "#151A24"
BORDER = "#252B36"
TEXT = "#F8FAFC"
MUTED = "#8B96A8"
BLUE = "#3B82F6"
BLUE_HOVER = "#60A5FA"
GREEN = "#22C55E"
RED = "#EF4444"


class GRXTProxyApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("GRXT WS Proxy")
        self.geometry("1100x720")
        self.minsize(900, 620)
        self.configure(bg=BG)

        self.settings = load_settings()
        self.api = GrxtApi()
        self.session_store = SessionStore()
        self.device_id = load_or_create_device_id()
        self.access_token = ""
        self.refresh_token = ""
        self.account_grxt_id = ""
        self.account_email = ""
        self.api_state = "Проверка…"
        self.version_policy = None
        self.core_process: subprocess.Popen[bytes] | None = None
        self.keep_background = tk.BooleanVar(value=False)
        self.page = "Главная"
        self.nav_buttons: dict[str, tk.Button] = {}
        self.content: tk.Frame | None = None
        self.status_label: tk.Label | None = None
        self.toggle_button: tk.Button | None = None
        self.log_box: tk.Text | None = None
        self.top_status: tk.Label | None = None

        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self._startup_gate()

    def _wipe(self) -> None:
        for child in self.winfo_children():
            child.destroy()

    def _center_card(self, title: str, subtitle: str) -> tuple[tk.Frame, tk.Label]:
        self._wipe()
        outer = tk.Frame(self, bg=BG)
        outer.pack(fill="both", expand=True)
        card = tk.Frame(outer, bg=SURFACE, highlightthickness=1, highlightbackground=BORDER, padx=34, pady=30)
        card.place(relx=.5, rely=.5, anchor="center", width=470)
        tk.Label(card, text="GRXT", bg=SURFACE, fg=TEXT, font=("Sans", 20, "bold")).pack(anchor="w")
        tk.Label(card, text=title, bg=SURFACE, fg=TEXT, font=("Sans", 24, "bold")).pack(anchor="w", pady=(18, 4))
        label = tk.Label(card, text=subtitle, bg=SURFACE, fg=MUTED, justify="left", wraplength=390, font=("Sans", 11))
        label.pack(anchor="w", pady=(0, 18))
        return card, label

    def _startup_gate(self) -> None:
        card, status = self._center_card("GRXT WS Proxy", "Проверяем версию и соединение с GRXT API…")
        def run() -> None:
            try:
                policy = self.api.version_policy()
            except GrxtApiError as exc:
                self.after(0, lambda message=str(exc): self._api_unavailable(message))
                return
            self.after(0, lambda: self._after_version(policy))
        threading.Thread(target=run, daemon=True).start()

    def _api_unavailable(self, message: str) -> None:
        self.api_state = "Недоступен"
        self._stop_core()
        card, _ = self._center_card("Нет связи с GRXT", message + "\n\nДля запуска требуется онлайн-проверка версии и GRXT ID.")
        self._action(card, "ПОВТОРИТЬ", self._startup_gate, True).pack(anchor="w")

    def _after_version(self, policy) -> None:
        self.api_state = "Online"
        self.version_policy = policy
        if policy.update_required:
            self._stop_core()
            card, _ = self._center_card("Требуется обновление", f"Эта версия больше не поддерживается.\n\nТекущая: {policy.current}\nМинимальная: {policy.minimum}\nПоследняя: {policy.latest}")
            if policy.download_url:
                self._action(card, "ОБНОВИТЬ СЕЙЧАС", lambda: self._install_update(policy), True).pack(anchor="w")
            self._action(card, "ПРОВЕРИТЬ СНОВА", self._startup_gate).pack(anchor="w", pady=(10, 0))
            return
        stored = self.session_store.load()
        if stored.refresh_token:
            card, status = self._center_card("GRXT ID", "Восстанавливаем защищённую сессию…")
            def refresh() -> None:
                try:
                    session = self.api.refresh(stored.refresh_token, self.device_id)
                    self.after(0, lambda: self._accept_session(session))
                except GrxtApiError:
                    self.session_store.clear()
                    self.after(0, self._login_screen)
            threading.Thread(target=refresh, daemon=True).start()
        else:
            self._login_screen()

    def _install_update(self, policy) -> None:
        if not policy.download_url:
            return
        card, status = self._center_card("Обновление GRXT WS Proxy", f"Скачиваем {policy.latest} и проверяем SHA-256…")

        def run() -> None:
            try:
                downloaded = download_verified(policy.download_url, policy.sha256)
                if can_self_update():
                    schedule_linux_replace(downloaded)
                    self.after(0, lambda: (status.configure(text="Обновление проверено. Перезапускаем приложение…", fg=GREEN), self.after(350, self.destroy)))
                    return
                self.after(0, lambda: self._manual_update_ready(downloaded, policy))
            except UpdateError as exc:
                self.after(0, lambda message=str(exc), policy=policy: self._update_failed(message, policy))

        threading.Thread(target=run, daemon=True).start()

    def _manual_update_ready(self, downloaded: Path, policy) -> None:
        card, _ = self._center_card("Обновление проверено", "Файл скачан и SHA-256 совпал. Автозамена доступна в собранной .grxt версии.")
        self._action(card, "ОТКРЫТЬ ПАПКУ", lambda: webbrowser.open(downloaded.parent.as_uri()), True).pack(anchor="w")
        self._action(card, "НАЗАД", lambda: self._after_version(policy)).pack(anchor="w", pady=(10, 0))

    def _update_failed(self, message: str, policy) -> None:
        card, _ = self._center_card("Ошибка обновления", message)
        self._action(card, "ПОВТОРИТЬ", lambda: self._install_update(policy), True).pack(anchor="w")
        self._action(card, "ОТКРЫТЬ RELEASE", lambda: webbrowser.open(policy.download_url)).pack(anchor="w", pady=(10, 0))

    def _login_screen(self) -> None:
        self._stop_core()
        card, result = self._center_card("Вход в GRXT ID", "Авторизация обязательна для запуска прокси.")

        tk.Label(card, text="Email или GRXT ID", bg=SURFACE, fg=MUTED,
                 font=("Sans", 10, "bold")).pack(anchor="w", pady=(0, 6))
        login = tk.Entry(card, bg=SURFACE_2, fg=TEXT, insertbackground=TEXT,
                         relief="flat", highlightthickness=1, highlightbackground=BORDER,
                         highlightcolor=BLUE, font=("Sans", 12))
        login.pack(fill="x", ipady=11)

        tk.Label(card, text="Пароль", bg=SURFACE, fg=MUTED,
                 font=("Sans", 10, "bold")).pack(anchor="w", pady=(12, 6))
        password_row = tk.Frame(card, bg=SURFACE)
        password_row.pack(fill="x")
        password = tk.Entry(password_row, bg=SURFACE_2, fg=TEXT, insertbackground=TEXT,
                            relief="flat", highlightthickness=1, highlightbackground=BORDER,
                            highlightcolor=BLUE, show="•", font=("Sans", 12))
        password.pack(side="left", fill="x", expand=True, ipady=11)
        show_password = tk.BooleanVar(value=False)

        def toggle_password() -> None:
            password.configure(show="" if show_password.get() else "•")

        show = tk.Checkbutton(password_row, text="Показать", variable=show_password, command=toggle_password,
                              bg=SURFACE, fg=MUTED, selectcolor=SURFACE_2, activebackground=SURFACE,
                              activeforeground=TEXT, font=("Sans", 9), bd=0, highlightthickness=0)
        show.pack(side="right", padx=(10, 0))

        def submit(event=None) -> None:
            value = login.get().strip()
            secret = password.get()
            if not value or not secret:
                result.configure(text="Введите email/GRXT ID и пароль.", fg=RED)
                return
            button.configure(state="disabled")
            result.configure(text="Входим в GRXT ID…", fg=MUTED)

            def run() -> None:
                try:
                    session = self.api.login(value, secret, self.device_id)
                    self.after(0, lambda: self._accept_session(session))
                except GrxtApiError as exc:
                    self.after(0, lambda message=self._human_error(exc): (
                        result.configure(text=message, fg=RED), button.configure(state="normal")
                    ))

            threading.Thread(target=run, daemon=True).start()

        button = self._action(card, "ВОЙТИ", submit, True)
        button.pack(fill="x", pady=(16, 0))
        login.bind("<Return>", submit)
        password.bind("<Return>", submit)
        login.focus_set()

        links = tk.Frame(card, bg=SURFACE)
        links.pack(fill="x", pady=(14, 0))
        self._action(links, "Создать GRXT ID", lambda: webbrowser.open("https://grxt.dev/id/register")).pack(side="left")
        self._action(links, "Забыли пароль?", lambda: webbrowser.open("https://grxt.dev/id/forgot-password")).pack(side="right")

    @staticmethod
    def _human_error(exc: GrxtApiError) -> str:
        return {
            "invalid_credentials": "Неверный логин или пароль.",
            "email_not_verified": "Подтвердите email перед входом.",
            "rate_limited": "Слишком много попыток. Попробуйте позже.",
            "client_update_required": "Эта версия приложения больше не поддерживается.",
            "service_unavailable": "GRXT API сейчас недоступен.",
        }.get(exc.code, str(exc))

    def _accept_session(self, session) -> None:
        self.access_token = session.access_token
        self.refresh_token = session.refresh_token
        self.account_grxt_id = session.grxt_id
        self.account_email = session.email
        if session.refresh_token:
            self.session_store.save(session.refresh_token, session.grxt_id, session.email)
        self._wipe()
        self.nav_buttons.clear()
        self._build_shell()
        self._show_page("Главная")
        self.after(500, self._refresh_status)

    def _logout(self) -> None:
        token, refresh = self.access_token, self.refresh_token
        self._stop_core()
        self.access_token = self.refresh_token = ""
        self.account_grxt_id = self.account_email = ""
        self.session_store.clear()
        self._login_screen()
        if token:
            threading.Thread(target=lambda: self._logout_remote(token, refresh), daemon=True).start()

    def _logout_remote(self, token: str, refresh: str) -> None:
        try:
            self.api.logout(token, refresh)
        except GrxtApiError:
            pass

    def _authenticated(self) -> bool:
        if self.access_token:
            return True
        self._stop_core()
        self._login_screen()
        return False

    def _build_shell(self) -> None:
        shell = tk.Frame(self, bg=BG)
        shell.pack(fill="both", expand=True)

        sidebar = tk.Frame(shell, bg=SIDEBAR, width=220, highlightthickness=1, highlightbackground=BORDER)
        sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)

        brand = tk.Frame(sidebar, bg=SIDEBAR)
        brand.pack(fill="x", padx=22, pady=(24, 28))
        tk.Label(brand, text="GRXT", bg=SIDEBAR, fg=TEXT, font=("Sans", 20, "bold")).pack(anchor="w")
        tk.Label(brand, text="WS Proxy", bg=SIDEBAR, fg=MUTED, font=("Sans", 11)).pack(anchor="w", pady=(2, 0))

        for name in ("Главная", "Подключение", "Диагностика", "Аккаунт", "Настройки", "О программе"):
            button = tk.Button(
                sidebar, text=name, anchor="w", relief="flat", bd=0, cursor="hand2",
                bg=SIDEBAR, fg=MUTED, activebackground=SURFACE_2, activeforeground=TEXT,
                font=("Sans", 11, "bold"), padx=22, pady=12,
                command=lambda n=name: self._show_page(n),
            )
            button.pack(fill="x", padx=10, pady=2)
            self.nav_buttons[name] = button

        tk.Label(sidebar, text=f"v{__version__} · stable", bg=SIDEBAR, fg=MUTED, font=("Sans", 9)).pack(side="bottom", anchor="w", padx=22, pady=20)

        main = tk.Frame(shell, bg=BG)
        main.pack(side="left", fill="both", expand=True)
        top = tk.Frame(main, bg=BG, height=70)
        top.pack(fill="x", padx=30, pady=(20, 0))
        tk.Label(top, text="GRXT WS Proxy", bg=BG, fg=TEXT, font=("Sans", 18, "bold")).pack(side="left")
        self.top_status = tk.Label(top, text="● Отключено", bg=BG, fg=MUTED, font=("Sans", 10, "bold"))
        self.top_status.pack(side="right", pady=8)

        self.content = tk.Frame(main, bg=BG)
        self.content.pack(fill="both", expand=True, padx=30, pady=(10, 30))

    def _clear_content(self) -> None:
        assert self.content is not None
        for child in self.content.winfo_children():
            child.destroy()

    def _show_page(self, name: str) -> None:
        self.page = name
        for key, button in self.nav_buttons.items():
            button.configure(bg=SURFACE_2 if key == name else SIDEBAR, fg=TEXT if key == name else MUTED)
        self._clear_content()
        {
            "Главная": self._home,
            "Подключение": self._connection,
            "Диагностика": self._diagnostics,
            "Аккаунт": self._account,
            "Настройки": self._settings_page,
            "О программе": self._about,
        }[name]()

    def _title(self, title: str, subtitle: str) -> None:
        assert self.content is not None
        tk.Label(self.content, text=title, bg=BG, fg=TEXT, font=("Sans", 26, "bold")).pack(anchor="w")
        tk.Label(self.content, text=subtitle, bg=BG, fg=MUTED, font=("Sans", 11)).pack(anchor="w", pady=(4, 22))

    def _card(self, parent: tk.Widget, pad: int = 20) -> tk.Frame:
        frame = tk.Frame(parent, bg=SURFACE, highlightthickness=1, highlightbackground=BORDER, padx=pad, pady=pad)
        return frame

    def _metric(self, parent: tk.Widget, title: str, value: str) -> tk.Frame:
        card = self._card(parent, 18)
        tk.Label(card, text=title, bg=SURFACE, fg=MUTED, font=("Sans", 10)).pack(anchor="w")
        tk.Label(card, text=value, bg=SURFACE, fg=TEXT, font=("Sans", 16, "bold")).pack(anchor="w", pady=(7, 0))
        return card

    def _action(self, parent: tk.Widget, text: str, command, primary: bool = False) -> tk.Button:
        bg = BLUE if primary else SURFACE_2
        button = tk.Button(parent, text=text, command=command, relief="flat", bd=0, cursor="hand2",
                           bg=bg, fg=TEXT, activebackground=BLUE_HOVER if primary else BORDER,
                           activeforeground=TEXT, font=("Sans", 10, "bold"), padx=18, pady=11)
        return button

    def _home(self) -> None:
        assert self.content is not None
        self._title("Главная", "Telegram MTProto · WebSocket/TLS · GRXT")
        hero = self._card(self.content, 24)
        hero.pack(fill="x")
        tk.Label(hero, text="СОСТОЯНИЕ", bg=SURFACE, fg=MUTED, font=("Sans", 9, "bold")).pack(anchor="w")
        self.status_label = tk.Label(hero, text="Отключено", bg=SURFACE, fg=TEXT, font=("Sans", 25, "bold"))
        self.status_label.pack(anchor="w", pady=(7, 4))
        tk.Label(hero, text="Локальный защищённый маршрут для Telegram", bg=SURFACE, fg=MUTED, font=("Sans", 11)).pack(anchor="w")
        self.toggle_button = self._action(hero, "ПОДКЛЮЧИТЬ", self._toggle, True)
        self.toggle_button.pack(anchor="w", pady=(20, 0))
        if self.version_policy is not None and self.version_policy.update_available and not self.version_policy.update_required:
            notice = tk.Frame(hero, bg=SURFACE)
            notice.pack(fill="x", pady=(18, 0))
            tk.Label(notice, text=f"Доступно обновление {self.version_policy.latest}", bg=SURFACE, fg=BLUE_HOVER, font=("Sans", 10, "bold")).pack(side="left")
            if self.version_policy.download_url:
                self._action(notice, "Обновить", lambda: self._install_update(self.version_policy)).pack(side="right")

        grid = tk.Frame(self.content, bg=BG)
        grid.pack(fill="x", pady=(16, 0))
        for i in range(4): grid.columnconfigure(i, weight=1, uniform="metric")
        values = (("Сервер", f"{self.settings.host}:{self.settings.port}"), ("Маршрут", "Auto"), ("Протокол", "MTProto"), ("Версия", __version__))
        for i, (title, value) in enumerate(values):
            card = self._metric(grid, title, value)
            card.grid(row=0, column=i, sticky="nsew", padx=(0 if i == 0 else 6, 0 if i == 3 else 6))

    def _connection(self) -> None:
        assert self.content is not None
        self._title("Подключение", "Управление локальным proxy core и Telegram")
        card = self._card(self.content)
        card.pack(fill="x")
        tk.Label(card, text="Локальная точка подключения", bg=SURFACE, fg=TEXT, font=("Sans", 15, "bold")).pack(anchor="w")
        tk.Label(card, text=f"{self.settings.host}:{self.settings.port}", bg=SURFACE, fg=MUTED, font=("Sans", 12)).pack(anchor="w", pady=(7, 18))
        row = tk.Frame(card, bg=SURFACE)
        row.pack(anchor="w")
        self._action(row, "Открыть в Telegram", self._open_telegram, True).pack(side="left")
        self._action(row, "Открыть лог", self._open_log).pack(side="left", padx=10)

    def _diagnostics(self) -> None:
        assert self.content is not None
        self._title("Диагностика", "Текущее состояние компонентов")
        card = self._card(self.content)
        card.pack(fill="both", expand=True)
        self.diag_proxy = tk.Label(card, bg=SURFACE, fg=MUTED, font=("Sans", 12))
        self.diag_proxy.pack(anchor="w")
        tk.Label(card, text="WebSocket / fallback · активируется при соединении", bg=SURFACE, fg=MUTED, font=("Sans", 12)).pack(anchor="w", pady=8)
        tk.Label(card, text=f"GRXT API · {self.api_state}", bg=SURFACE, fg=GREEN if self.api_state == "Online" else MUTED, font=("Sans", 12)).pack(anchor="w")
        self.log_box = tk.Text(card, height=12, bg="#0B0E14", fg="#CBD5E1", insertbackground=TEXT, relief="flat", bd=0, wrap="word", font=("Monospace", 9))
        self.log_box.pack(fill="both", expand=True, pady=(18, 0))

    def _account(self) -> None:
        assert self.content is not None
        self._title("Аккаунт", "GRXT ID")
        card = self._card(self.content)
        card.pack(fill="x")
        tk.Label(card, text=self.account_grxt_id or "GRXT ID", bg=SURFACE, fg=TEXT, font=("Sans", 18, "bold")).pack(anchor="w")
        tk.Label(card, text=self.account_email or "—", bg=SURFACE, fg=MUTED, font=("Sans", 11)).pack(anchor="w", pady=(8, 18))
        row = tk.Frame(card, bg=SURFACE)
        row.pack(anchor="w")
        self._action(row, "Управление аккаунтом", lambda: webbrowser.open("https://grxt.dev/id"), True).pack(side="left")
        self._action(row, "Выйти", self._logout).pack(side="left", padx=10)

    def _settings_page(self) -> None:
        assert self.content is not None
        self._title("Настройки", "Поведение приложения")
        card = self._card(self.content)
        card.pack(fill="x")
        check = tk.Checkbutton(card, text="Оставлять proxy core после закрытия окна", variable=self.keep_background,
                               bg=SURFACE, fg=TEXT, selectcolor=SURFACE_2, activebackground=SURFACE,
                               activeforeground=TEXT, font=("Sans", 11))
        check.pack(anchor="w")

    def _about(self) -> None:
        assert self.content is not None
        self._title("О программе", "GRXT WS Proxy")
        card = self._card(self.content)
        card.pack(fill="x")
        for key, value in (("Версия", __version__), ("Канал", "Stable"), ("Платформа", sys.platform), ("Сайт", "grxt.dev")):
            row = tk.Frame(card, bg=SURFACE)
            row.pack(fill="x", pady=6)
            tk.Label(row, text=key, bg=SURFACE, fg=MUTED, font=("Sans", 11)).pack(side="left")
            tk.Label(row, text=value, bg=SURFACE, fg=TEXT, font=("Sans", 11, "bold")).pack(side="right")

    def _core_is_running(self) -> bool:
        if self.core_process is not None and self.core_process.poll() is None:
            return True
        try:
            with socket.create_connection((self.settings.host, self.settings.port), timeout=0.2):
                return True
        except OSError:
            return False

    def _start_core(self) -> None:
        if not self._authenticated(): return
        if self._core_is_running(): return
        self.core_process = subprocess.Popen([sys.executable, "-m", "grxt_ws_proxy.core.service"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=(os.name != "nt"))

    def _stop_core(self) -> None:
        process = self.core_process
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=6)
            except subprocess.TimeoutExpired: process.kill()
        self.core_process = None

    def _toggle(self) -> None:
        if not self._authenticated(): return
        self._stop_core() if self._core_is_running() else self._start_core()
        self.after(250, self._refresh_status)

    def _open_telegram(self) -> None:
        if not self._authenticated(): return
        if not self._core_is_running(): self._start_core()
        webbrowser.open(self.settings.telegram_link)

    def _log_path(self) -> Path:
        return Path.home() / ".local" / "state" / "grxt-ws-proxy" / "proxy.log"

    def _read_log_tail(self) -> str:
        path = self._log_path()
        if not path.exists(): return "Лог пока пуст."
        try: return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-120:])
        except OSError as exc: return f"Не удалось прочитать лог: {exc}"

    def _open_log(self) -> None:
        path = self._log_path(); path.parent.mkdir(parents=True, exist_ok=True); path.touch(exist_ok=True); webbrowser.open(path.as_uri())

    def _refresh_status(self) -> None:
        running = self._core_is_running()
        if self.top_status is not None and self.top_status.winfo_exists():
            self.top_status.configure(text="● Подключено" if running else "● Отключено", fg=GREEN if running else MUTED)
        if self.status_label is not None and self.status_label.winfo_exists():
            self.status_label.configure(text="Подключено" if running else "Отключено", fg=GREEN if running else TEXT)
        if self.toggle_button is not None and self.toggle_button.winfo_exists():
            self.toggle_button.configure(text="ОТКЛЮЧИТЬ" if running else "ПОДКЛЮЧИТЬ")
        if hasattr(self, "diag_proxy") and self.diag_proxy.winfo_exists():
            self.diag_proxy.configure(text="Proxy core · работает" if running else "Proxy core · выключен", fg=GREEN if running else MUTED)
        if self.log_box is not None and self.log_box.winfo_exists():
            self.log_box.delete("1.0", "end"); self.log_box.insert("1.0", self._read_log_tail())
        self.after(1500, self._refresh_status)

    def _on_close(self) -> None:
        if not self.keep_background.get(): self._stop_core()
        self.destroy()


def main() -> None:
    GRXTProxyApp().mainloop()

if __name__ == "__main__":
    main()
