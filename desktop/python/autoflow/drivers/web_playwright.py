"""Browser driver — real DOM automation via Playwright. This is the big win
over Android: find-by-selector/text is reliable, no accessibility-tree games."""
from __future__ import annotations

import time
from typing import Any


class WebDriver:
    """Owns a Playwright browser context + page. Created lazily on first
    open_url so automations that only touch native apps don't launch a browser."""

    def __init__(self, headed: bool = True) -> None:
        self.headed = headed
        self._pw = None
        self._context = None
        self.page = None

    # --- lifecycle -----------------------------------------------------------

    def _ensure_page(self, browser: str | None):
        if self.page is not None:
            return self.page
        from playwright.sync_api import sync_playwright

        self._pw = sync_playwright().start()
        launch_kwargs: dict[str, Any] = {"headless": not self.headed}
        engine = "firefox" if browser == "firefox" else "chromium"
        # Real Chrome/Edge channels look less like a bot than bundled Chromium.
        if browser in ("chrome", "msedge"):
            launch_kwargs["channel"] = browser
        browser_type = getattr(self._pw, engine)
        try:
            b = browser_type.launch(**launch_kwargs)
        except Exception:
            # Requested channel not installed — fall back to bundled Chromium.
            b = self._pw.chromium.launch(headless=not self.headed)
        self._context = b.new_context()
        self.page = self._context.new_page()
        return self.page

    def close(self) -> None:
        try:
            if self._context:
                self._context.browser.close()
        finally:
            if self._pw:
                self._pw.stop()

    # --- helpers -------------------------------------------------------------

    def _locator(self, selector: str | None, text: str | None, exact: bool):
        page = self.page
        if selector:
            return page.locator(selector).first
        if text is not None:
            return page.get_by_text(text, exact=exact).first
        raise ValueError("step needs 'selector' or 'text'")

    # --- steps ---------------------------------------------------------------

    def open_url(self, url: str, browser: str | None = None, **_):
        page = self._ensure_page(browser)
        page.goto(url, wait_until="domcontentloaded")

    def wait_for(self, selector=None, text=None, timeoutMs=8000, **_):
        sel = selector if selector else f"text={text}"
        self.page.wait_for_selector(sel, timeout=timeoutMs)

    def fill(self, selector=None, label=None, text="", exact=False, **_):
        loc = self.page.get_by_label(label).first if label else self.page.locator(selector).first
        loc.fill(text or "")

    def click(self, selector=None, text=None, exact=False, **_):
        self._locator(selector, text, exact).click()

    def ensure_checked(self, selector=None, text=None, checked=True, exact=False, **_):
        loc = self._locator(selector, text, exact)
        if loc.is_checked() != bool(checked):
            loc.set_checked(bool(checked))

    def click_confirm(self, selector=None, text=None, confirmText=None, attempts=3,
                      gapMs=4000, exact=False, **_):
        for _i in range(max(1, int(attempts))):
            loc = self._locator(selector, text, exact)
            if loc.count() == 0:
                break  # target gone (already submitted) — stop
            try:
                loc.click(timeout=3000)
            except Exception:
                pass
            if confirmText:
                try:
                    self.page.wait_for_selector(f"text={confirmText}", timeout=gapMs)
                    return
                except Exception:
                    continue
            else:
                time.sleep(gapMs / 1000)

    def verify(self, selector=None, text=None, present=True, exact=False, **_):
        loc = self._locator(selector, text, exact)
        found = loc.count() > 0 and loc.is_visible()
        if found != bool(present):
            raise AssertionError(
                f"verify failed: {'expected' if present else 'did not expect'} "
                f"{selector or text!r}"
            )

    def press(self, key="Enter", **_):
        self.page.keyboard.press(key)

    def reload(self, **_):
        self.page.reload(wait_until="domcontentloaded")

    def screenshot(self, path="autoflow-screenshot.png", **_):
        self.page.screenshot(path=path)

    def clear_site_data(self, **_):
        """Clears cookies + this origin's storage — the clean replacement for
        the Android Chrome-settings tap dance on site 5."""
        self._context.clear_cookies()
        try:
            self.page.evaluate("() => { localStorage.clear(); sessionStorage.clear(); }")
        except Exception:
            pass
