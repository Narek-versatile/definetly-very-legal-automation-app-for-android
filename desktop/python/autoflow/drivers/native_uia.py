"""Native Windows app driver via UI Automation (pywinauto). Windows-only; all
imports are lazy so the package still loads/tests on Linux."""
from __future__ import annotations

import subprocess
import time


class NativeDriver:
    def __init__(self) -> None:
        self._app = None  # pywinauto Application, once connected

    def _require_windows(self):
        import sys
        if not sys.platform.startswith("win"):
            raise RuntimeError("native steps require Windows")

    def launch(self, path="", args=None, **_):
        self._require_windows()
        subprocess.Popen([path, *(args or [])])

    def focus_window(self, title="", **_):
        self._require_windows()
        from pywinauto import Desktop
        win = Desktop(backend="uia").window(title_re=f".*{title}.*")
        win.wait("visible", timeout=10)
        win.set_focus()
        self._win = win

    def ui_click(self, name=None, automationId=None, controlType=None, **_):
        self._require_windows()
        ctrl = self._find(name, automationId, controlType)
        ctrl.click_input()

    def ui_fill(self, name=None, automationId=None, text="", **_):
        self._require_windows()
        ctrl = self._find(name, automationId, None)
        ctrl.set_edit_text(text)

    def close_window(self, title="", **_):
        self._require_windows()
        from pywinauto import Desktop
        Desktop(backend="uia").window(title_re=f".*{title}.*").close()

    def _find(self, name, automation_id, control_type):
        win = getattr(self, "_win", None)
        if win is None:
            from pywinauto import Desktop
            win = Desktop(backend="uia")
        kwargs = {}
        if name:
            kwargs["title"] = name
        if automation_id:
            kwargs["auto_id"] = automation_id
        if control_type:
            kwargs["control_type"] = control_type
        ctrl = win.child_window(**kwargs)
        ctrl.wait("visible", timeout=10)
        return ctrl
