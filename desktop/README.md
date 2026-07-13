# AutoFlow — desktop automation engine (Python + .NET)

A small, reusable UI‑automation engine for Windows/desktop. It runs **portable
JSON automations** — an ordered list of typed steps — against **real browsers**
(via [Playwright](https://playwright.dev), true DOM access) and **native Windows
apps** (via UI Automation). It's the desktop sibling of the Android app in this
repo: same idea (a JSON "automation" of steps with retry/verify), but far more
reliable on a PC because Playwright gives real DOM selectors instead of an
accessibility tree.

There are **two independent implementations** that consume the **same JSON files**:

| | Path | Browser | Native | Tests |
|---|---|---|---|---|
| Python (reference) | [`python/`](python/) | Playwright | pywinauto | pytest |
| C# / .NET 8 | [`dotnet/`](dotnet/) | Microsoft.Playwright | FlaUI (UIA3) | xUnit |

The shared contract is [`schema/automation.schema.json`](schema/automation.schema.json);
the shared automations live in [`automations/`](automations/).

> **Honesty note (carried over from the Android app).** One bundled demo is a
> Minecraft server‑list *voter*. Those sites' terms forbid automated voting and
> use captchas specifically to stop it, so each vote automation ends at a
> `manual` pause where **you** solve the captcha. It's a stress test for the
> engine, not the product — the product is the general engine.

## The step vocabulary

Every step is `{ "type": "...", ...params, "retry": { "attempts": n, "backoffMs": ms } }`.
`{token}` placeholders in string params are filled from an automation's `vars`
and/or `--var` on the command line (e.g. `{username}`). Steps route to one of
three drivers:

- **web** (Playwright): `open_url`, `wait_for`, `fill`, `click`,
  `ensure_checked`, `click_confirm` (click + wait for a confirmation, with
  retries — stops early if the target disappears), `verify`, `press`, `reload`,
  `screenshot`, `clear_site_data`.
- **native** (UI Automation): `launch`, `focus_window`, `ui_click`, `ui_fill`,
  `close_window`.
- **generic**: `sleep`, `manual` (alert + pause for a human, e.g. a captcha),
  `click_xy`, `type_text`.

The engine runs steps in order, retries each with backoff, and on failure
captures a screenshot + structured log — mirroring the Android `AutomationEngine`.

## Quick start — Python

```bash
cd desktop/python
python -m pip install -e ".[dev,native]"      # 'native' is Windows-only
python -m playwright install chromium
python -m pytest -q                            # unit tests

# run an automation (headed browser by default)
python -m autoflow run ../automations/_selftest.json --var username=Nga1p
python -m autoflow list ../automations
```

## Quick start — .NET

```bash
cd desktop/dotnet
dotnet test                                    # unit tests
pwsh AutoFlow.Cli/bin/Debug/net8.0-windows/playwright.ps1 install chromium

dotnet run --project AutoFlow.Cli -- run ../automations/_selftest.json --var username=Nga1p
dotnet run --project AutoFlow.Cli -- list ../automations
```

Both CLIs share the same verbs and flags: `run <file> [--var k=v …] [--headless]
[--keep-open]` and `list <dir>`. Because both read the same JSON, any automation
runs identically under either engine.

## The self‑test (how CI proves the engine end‑to‑end)

[`automations/_selftest.json`](automations/_selftest.json) drives
[`testpages/form.html`](testpages/form.html) — a local page shaped like the real
vote sites (username field, an agree checkbox, a submit button that only shows
"Success…" when both are set) but with **no live captcha**. CI serves the page,
runs the automation headless under **both** engines, and asserts it reaches the
success text. This exercises `open_url → wait_for → fill → ensure_checked →
click_confirm → verify` without touching any third‑party site.

The native driver is proven separately by
[`automations/native-notepad.json`](automations/native-notepad.json) on Windows.

## CI & artifacts

- **`.github/workflows/desktop-python.yml`** — pytest on Ubuntu + Windows, the
  headless self‑test, and a one‑file `autoflow.exe` (PyInstaller) artifact.
- **`.github/workflows/desktop-dotnet.yml`** — `dotnet test` + the headless
  self‑test on Windows, and a self‑contained single‑file `autoflow.exe` artifact.

## Layout

```
desktop/
  schema/automation.schema.json   # the cross-implementation contract
  automations/*.json              # portable automations (used by both engines)
  testpages/form.html             # local page for the CI self-test
  python/                         # reference implementation
  dotnet/                         # parallel .NET implementation
```

## Not here (yet)

A GUI (the CLI comes first); macOS/Linux native drivers (the engine is portable —
only the native driver is OS‑specific); and anything that tries to defeat a
captcha (out of scope and against site terms — hence the `manual` pause).
