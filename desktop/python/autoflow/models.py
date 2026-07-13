"""Automation data model + JSON loading. Mirrors the Android `Step`/`Automation`
model so the same JSON files run on both. Steps are kept as a light (type,
retry, params) shape and dispatched by the engine."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class RetryPolicy:
    attempts: int = 1
    backoff_ms: int = 600

    @staticmethod
    def from_json(obj: dict | None) -> "RetryPolicy":
        obj = obj or {}
        return RetryPolicy(
            attempts=max(1, int(obj.get("attempts", 1))),
            backoff_ms=int(obj.get("backoffMs", 600)),
        )


@dataclass
class Step:
    type: str
    retry: RetryPolicy
    params: dict[str, Any]

    def s(self, key: str, default: Any = None) -> Any:
        return self.params.get(key, default)


@dataclass
class Automation:
    name: str
    description: str = ""
    vars: dict[str, str] = field(default_factory=dict)
    steps: list[Step] = field(default_factory=list)

    @staticmethod
    def from_json(obj: dict) -> "Automation":
        steps: list[Step] = []
        for raw in obj.get("steps", []):
            params = {k: v for k, v in raw.items() if k not in ("type", "retry")}
            steps.append(
                Step(
                    type=raw["type"],
                    retry=RetryPolicy.from_json(raw.get("retry")),
                    params=params,
                )
            )
        return Automation(
            name=obj["name"],
            description=obj.get("description", ""),
            vars=dict(obj.get("vars", {})),
            steps=steps,
        )


def load_automation(path: str | Path) -> Automation:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return Automation.from_json(data)


_TOKEN = re.compile(r"\{([a-zA-Z0-9_]+)\}")


def resolve(text: str | None, variables: dict[str, str]) -> str | None:
    """Substitutes {token} with values from `variables` (like the Android
    engine's resolve()). Unknown tokens are left as-is."""
    if text is None:
        return None
    return _TOKEN.sub(lambda m: variables.get(m.group(1), m.group(0)), text)
