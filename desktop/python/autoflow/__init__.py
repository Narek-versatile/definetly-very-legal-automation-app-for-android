"""AutoFlow — a small cross-platform automation engine (browser via Playwright,
native Windows apps via UI Automation), driven by portable JSON automations."""
from .engine import Engine, RunResult, StepResult
from .models import Automation, Step, load_automation, resolve

__all__ = [
    "Engine",
    "RunResult",
    "StepResult",
    "Automation",
    "Step",
    "load_automation",
    "resolve",
]
__version__ = "0.1.0"
