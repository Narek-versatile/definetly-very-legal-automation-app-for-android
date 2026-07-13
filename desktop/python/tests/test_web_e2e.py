"""Browser end-to-end: serve the local test form and run _selftest.json headless.
Skipped automatically when Playwright's browser isn't installed."""
import functools
import http.server
import socketserver
import threading
from pathlib import Path

import pytest

from autoflow.engine import Engine
from autoflow.models import load_automation

ROOT = Path(__file__).resolve().parents[3]  # repo root
TESTPAGES = ROOT / "desktop" / "testpages"
AUTOMATIONS = ROOT / "desktop" / "automations"


def _playwright_ready() -> bool:
    try:
        from playwright.sync_api import sync_playwright

        with sync_playwright() as pw:
            pw.chromium.launch(headless=True).close()
        return True
    except Exception:
        return False


@pytest.fixture()
def server():
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(TESTPAGES))
    with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
        port = httpd.server_address[1]
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{port}"
        finally:
            httpd.shutdown()


@pytest.mark.skipif(not _playwright_ready(), reason="playwright chromium not installed")
def test_selftest_end_to_end(server):
    automation = load_automation(AUTOMATIONS / "_selftest.json")
    engine = Engine(variables={"baseurl": server, "username": "CiTester"}, headed=False)
    try:
        result = engine.run(automation)
    finally:
        engine.close()
    assert result.ok, [s.__dict__ for s in result.steps]
