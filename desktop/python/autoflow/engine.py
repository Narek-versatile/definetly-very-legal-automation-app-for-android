"""The automation engine: runs an Automation's steps in order, with per-step
retry/backoff, structured logging and a failure screenshot — mirroring the
Android AutomationEngine.run() semantics."""
from __future__ import annotations

import time
from dataclasses import dataclass, field

from .drivers.generic import GenericDriver
from .drivers.native_uia import NativeDriver
from .drivers.web_playwright import WebDriver
from .models import Automation, Step, resolve

WEB_STEPS = {
    "open_url", "wait_for", "fill", "click", "ensure_checked", "click_confirm",
    "verify", "press", "reload", "screenshot", "clear_site_data",
}
NATIVE_STEPS = {"launch", "focus_window", "ui_click", "ui_fill", "close_window"}
GENERIC_STEPS = {"sleep", "manual", "click_xy", "type_text"}


@dataclass
class StepResult:
    index: int
    describe: str
    ok: bool
    message: str
    attempts: int


@dataclass
class RunResult:
    name: str
    ok: bool
    steps: list[StepResult] = field(default_factory=list)
    screenshot: str | None = None


class Engine:
    def __init__(self, variables: dict[str, str] | None = None, headed: bool = True):
        self.variables = dict(variables or {})
        self.web = WebDriver(headed=headed)
        self.native = NativeDriver()
        self.generic = GenericDriver()

    def close(self):
        self.web.close()

    def run(self, automation: Automation) -> RunResult:
        variables = {**automation.vars, **self.variables}
        result = RunResult(name=automation.name, ok=True)

        for index, step in enumerate(automation.steps):
            describe = self._describe(step)
            print(f"  [{index + 1}/{len(automation.steps)}] {describe}")
            attempts = 0
            last_err = "not run"
            while attempts < step.retry.attempts:
                attempts += 1
                try:
                    self._exec(step, variables)
                    last_err = ""
                    break
                except Exception as exc:  # noqa: BLE001 - report any driver error
                    last_err = f"{type(exc).__name__}: {exc}"
                    if attempts < step.retry.attempts:
                        time.sleep(step.retry.backoff_ms / 1000)

            ok = last_err == ""
            result.steps.append(StepResult(index, describe, ok, last_err or "ok", attempts))
            if not ok:
                result.ok = False
                result.screenshot = self._capture_failure(automation, index)
                print(f"      x {last_err}")
                break
            print("      ok")

        return result

    # --- dispatch ------------------------------------------------------------

    def _exec(self, step: Step, variables: dict[str, str]) -> None:
        params = {
            k: (resolve(v, variables) if isinstance(v, str) else v)
            for k, v in step.params.items()
        }
        driver, method = self._route(step.type)
        getattr(driver, method)(**params)

    def _route(self, step_type: str):
        if step_type in WEB_STEPS:
            return self.web, step_type
        if step_type in NATIVE_STEPS:
            return self.native, step_type
        if step_type in GENERIC_STEPS:
            return self.generic, step_type
        raise ValueError(f"unknown step type: {step_type}")

    def _describe(self, step: Step) -> str:
        p = step.params
        key = p.get("selector") or p.get("text") or p.get("url") or p.get("path") or ""
        return f"{step.type} {key}".strip()

    def _capture_failure(self, automation: Automation, index: int) -> str | None:
        if self.web.page is None:
            return None
        path = f"failure_{_slug(automation.name)}_{index}.png"
        try:
            self.web.page.screenshot(path=path)
            return path
        except Exception:
            return None


def _slug(text: str) -> str:
    return "".join(c if c.isalnum() else "-" for c in text).strip("-").lower()
