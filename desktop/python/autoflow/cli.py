"""AutoFlow CLI:  autoflow run <file.json> [--var k=v ...] [--headed/--headless]
                  autoflow list <dir>"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .engine import Engine
from .models import load_automation


def _parse_vars(pairs: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for pair in pairs or []:
        if "=" not in pair:
            raise SystemExit(f"--var expects k=v, got: {pair!r}")
        k, v = pair.split("=", 1)
        out[k] = v
    return out


def cmd_run(args) -> int:
    automation = load_automation(args.file)
    variables = _parse_vars(args.var)
    print(f"Running: {automation.name}")
    engine = Engine(variables=variables, headed=args.headed)
    try:
        result = engine.run(automation)
    finally:
        if not args.keep_open:
            engine.close()
    passed = sum(1 for s in result.steps if s.ok)
    print(f"\n{'OK' if result.ok else 'FAILED'} - {passed}/{len(result.steps)} steps"
          + (f"  (screenshot: {result.screenshot})" if result.screenshot else ""))
    return 0 if result.ok else 1


def cmd_list(args) -> int:
    for path in sorted(Path(args.dir).glob("*.json")):
        try:
            a = load_automation(path)
            print(f"{path.name:40s} {a.name}")
        except Exception as exc:  # noqa: BLE001
            print(f"{path.name:40s} <invalid: {exc}>")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="autoflow", description="Cross-platform automation engine.")
    sub = p.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="run an automation JSON file")
    run.add_argument("file")
    run.add_argument("--var", action="append", default=[], help="k=v (repeatable), e.g. --var username=Nga1p")
    run.add_argument("--headless", dest="headed", action="store_false", default=True,
                     help="run the browser headless (default: headed)")
    run.add_argument("--keep-open", action="store_true", help="leave the browser open after finishing")
    run.set_defaults(func=cmd_run)

    lst = sub.add_parser("list", help="list automations in a directory")
    lst.add_argument("dir")
    lst.set_defaults(func=cmd_list)
    return p


def main(argv: list[str] | None = None) -> int:
    # Windows consoles default to a legacy codepage (cp1252); force UTF-8 so any
    # Unicode in an automation's messages never crashes the run on output.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass
    args = build_parser().parse_args(argv if argv is not None else sys.argv[1:])
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
