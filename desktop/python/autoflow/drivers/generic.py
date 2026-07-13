"""OS-agnostic + coordinate/keyboard steps. pyautogui is imported lazily so a
headless/browser-only run (e.g. CI) never needs a display."""
from __future__ import annotations

import time


class GenericDriver:
    def sleep(self, ms=1000, **_):
        time.sleep(max(0, int(ms)) / 1000)

    def manual(self, message="Do the manual step, then wait...", timeoutMs=20000, **_):
        # Alert the user and pause - for flows the engine can't finish (captchas).
        print(f"\n  [manual] {message}\n  Waiting up to {int(timeoutMs)/1000:.0f}s "
              f"(press Enter to continue sooner)...")
        _wait_or_enter(int(timeoutMs) / 1000)

    def click_xy(self, x=0, y=0, **_):
        import pyautogui
        pyautogui.click(int(x), int(y))

    def type_text(self, text="", **_):
        import pyautogui
        pyautogui.typewrite(text, interval=0.02)


def _wait_or_enter(seconds: float) -> None:
    """Wait up to `seconds`, returning early if the user presses Enter.
    Falls back to a plain sleep where select-on-stdin isn't available."""
    try:
        import select
        import sys

        ready, _, _ = select.select([sys.stdin], [], [], seconds)
        if ready:
            sys.stdin.readline()
    except Exception:
        time.sleep(seconds)
