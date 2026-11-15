"""PowerContext @magpie-community/gateway-powercontext@0.2.1 fixture server.

A minimal HTTP service that speaks the three endpoints the plugin calls
(scope-bindings/resolve, context/prepare, handoff/hint) so a smoke test
can prove a community hooks plugin actually recalls memory through the
gateway — not merely installs.

The response shapes are taken from the actual 0.2.1 source (see
/tmp/community-actual/powercontext/powercontext.hooks.mjs for the full
text; the prepared() parser at line 429 and the scope() handler at
line 377 are the authoritative field checks).

Usage:
    from powercontext_fixture import PowerContextFixture

    pw = PowerContextFixture()
    pw.start()
    # ... configure the plugin with serverUrl = pw.base ...
    # ... run the chat ...
    pw.stop()

    # inspect
    pw.requests  # list of {"route", "body", "headers"}
    pw.base      # e.g. "http://127.0.0.1:12345"

Plugin options (minimum for recall without capture):
    {"serverUrl": pw.base, "capture": false}
For origin != "local", add "recallRemote": true.
For capture/store, add "capture": true (and idleCaptureMs if needed).
"""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCHEMA = "powercontext.prepared-context.v1"
CONTENT = "fixture memory: project uses Bun; the tests run through the gateway"
CONTENT_BYTES = len(CONTENT.encode("utf-8"))
HINT = "latest handoff: the fixture scope is active"
HINT_BYTES = len(HINT.encode("utf-8"))

# The plugin's prepared() parser (line 429) requires EXACTLY these four
# keys: content, content_bytes, schema, status (sorted alphabetically).
def prepared_body(content: str) -> dict:
    raw = content.encode("utf-8")
    return {"schema": SCHEMA, "status": "ready", "content": content,
            "content_bytes": len(raw)}

# The plugin's scope() parser (line 377) reads r.body?.scope_id
def scope_body(scope_id: str = "fixture-scope-1") -> dict:
    return {"scope_id": scope_id}


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def do_POST(self):
        fixture = self.server.fixture
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 <= length <= 1 << 20:
            self.send_error(413)
            return
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw) if raw else {}
        except ValueError:
            body = {}
        route = self.path.lstrip("/")
        with fixture.lock:
            fixture.requests.append({
                "route": route, "body": body,
                "headers": {k: v for k, v in self.headers.items()
                            if k.lower() in ("authorization", "user-agent")},
            })
        if route == "v1/scope-bindings/resolve":
            self._json(scope_body(fixture.scope_id))
        elif route == "v1/context/prepare":
            self._json(prepared_body(fixture.content))
        elif route == "v1/handoff/hint":
            self._json(prepared_body(fixture.hint))
        elif route == "v1/sources/content":
            self._json({"status": "ok", "id": "fixture-source-1"})
        else:
            self.send_error(404)

    def do_GET(self):
        self.send_error(404)

    def _json(self, payload):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class PowerContextFixture:
    """A PowerContext-compatible HTTP fixture for testing gateway hooks."""

    def __init__(self, scope_id="fixture-scope-1", content=CONTENT,
                 hint=HINT, max_bytes=8000):
        self.scope_id = scope_id
        self.content = content
        self.hint = hint
        self.max_bytes = max_bytes
        self.requests = []
        self.lock = threading.Lock()
        self._server = None
        self._thread = None
        self.base = None

    def start(self):
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self._server.fixture = self
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        self.base = "http://127.0.0.1:" + str(self._server.server_address[1])
        return self

    def stop(self):
        if self._server:
            self._server.shutdown()
            self._server.server_close()
            self._thread.join(timeout=5)

    def calls(self, route: str):
        with self.lock:
            return [r for r in self.requests if r["route"] == route]

    @property
    def scope_resolves(self):
        return self.calls("v1/scope-bindings/resolve")

    @property
    def prepares(self):
        return self.calls("v1/context/prepare")

    @property
    def hints(self):
        return self.calls("v1/handoff/hint")

    @property
    def captures(self):
        return self.calls("v1/sources/content")

    def assert_recalled(self):
        assert self.scope_resolves, "no scope-bindings/resolve call"
        assert self.prepares, "no context/prepare call"
        for r in self.scope_resolves:
            assert isinstance(r["body"].get("binding_keys"), list), \
                "binding_keys not a list in scope resolve"
        for r in self.prepares:
            assert r["body"].get("scope_id"), "prepare missing scope_id"
            assert r["body"].get("query"), "prepare missing query"

    def plugin_options(self, **overrides) -> dict:
        """The minimum options the plugin needs for this fixture."""
        opts = {"serverUrl": self.base, "capture": False, "maxBytes": self.max_bytes}
        opts.update(overrides)
        return opts
