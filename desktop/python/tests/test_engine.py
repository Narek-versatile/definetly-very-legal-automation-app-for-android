import json
from pathlib import Path

from autoflow.engine import Engine
from autoflow.models import Automation, RetryPolicy, Step, load_automation, resolve

AUTOMATIONS = Path(__file__).resolve().parents[2] / "automations"


def test_resolve_substitutes_known_tokens_only():
    assert resolve("hi {username}!", {"username": "Nga1p"}) == "hi Nga1p!"
    assert resolve("{missing}", {}) == "{missing}"
    assert resolve(None, {}) is None


def test_all_shipped_automations_parse():
    files = list(AUTOMATIONS.glob("*.json"))
    assert files, "no automations found"
    for f in files:
        a = load_automation(f)
        assert a.name
        assert isinstance(a.steps, list)


def test_selftest_shape():
    a = load_automation(AUTOMATIONS / "_selftest.json")
    types = [s.type for s in a.steps]
    assert types[0] == "open_url"
    assert "ensure_checked" in types
    assert "verify" in types


def test_engine_retries_then_succeeds():
    engine = Engine()
    calls = {"n": 0}

    def flaky(**_):
        calls["n"] += 1
        if calls["n"] < 3:
            raise RuntimeError("boom")

    engine.generic.sleep = flaky
    auto = Automation(name="t", steps=[Step("sleep", RetryPolicy(attempts=3, backoff_ms=0), {"ms": 0})])
    result = engine.run(auto)
    assert result.ok
    assert result.steps[0].attempts == 3


def test_engine_fails_after_exhausting_retries():
    engine = Engine()

    def always_fail(**_):
        raise RuntimeError("nope")

    engine.generic.sleep = always_fail
    auto = Automation(name="t", steps=[Step("sleep", RetryPolicy(attempts=2, backoff_ms=0), {})])
    result = engine.run(auto)
    assert not result.ok
    assert result.steps[0].attempts == 2


def test_unknown_step_type_fails_run():
    engine = Engine()
    auto = Automation(name="t", steps=[Step("bogus", RetryPolicy(attempts=1, backoff_ms=0), {})])
    result = engine.run(auto)
    assert not result.ok


def test_automations_match_schema_types():
    schema = json.loads((AUTOMATIONS.parent / "schema" / "automation.schema.json").read_text())
    allowed = set(schema["$defs"]["step"]["properties"]["type"]["enum"])
    for f in AUTOMATIONS.glob("*.json"):
        for step in load_automation(f).steps:
            assert step.type in allowed, f"{f.name}: {step.type} not in schema"
