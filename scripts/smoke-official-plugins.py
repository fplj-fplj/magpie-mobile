#!/usr/bin/env python3
"""Real plugin integration on the Android-host core, end to end.

Three plugin kinds are installed from path fixtures, the real Bun host is
started, and every chat is answered through the real gateway to a vendor
fixture that speaks OpenAI's wire. The Android bridge's fetch injection is
exercised for real: an authless fetch and the loopback placeholder to the
gateway's own origin are authenticated, an explicit key is left as it was
sent, and a cross-origin redirect is never followed when the request
carries credentials — so no magpie key ever reaches a server outside the
gateway.

The real outside installs are part of the suite, not stubs: three
community npm packages and one GitHub repository at a fixed commit, all
installed through the real bun/npm/git pipeline, proving the Android
plugin path works with what users actually install.

The key lifecycle is asserted through the management API: off → refused
and no new key; remove → still refused after a core restart; restore (the
App's explicit POST) → answered again.
"""
import argparse, io, json, os, secrets, signal, socket, subprocess, sys, tarfile, tempfile, threading, time, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CORE_VERSION = "0.1.3781-androidhost.2"
VENDOR_KEY = "synthetic-provider-key-never-real"
COMMUNITY_NPM = [
    "@magpie-community/tools-calc@0.1.0",
    "@magpie-community/middleware-word-guard@0.1.1",
    "@magpie-community/gateway-powercontext@0.2.1",
]
GITHUB_SPEC = "github:guotree/magpie-websearch#3ff2a8deec6742d1f74e3a65c3b006405ac27615"
GITHUB_PACKAGE = "@guotree/magpie-websearch"

def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0)); return s.getsockname()[1]

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

def request(base, path, method="GET", body=None, headers=None, timeout=120):
    data = None if body is None else json.dumps(body).encode()
    h = {"Accept": "application/json"}; h.update(headers or {})
    if data is not None: h["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=h, method=method)
    try:
        with http.open(req, timeout=timeout) as r: return r.status, r.read(1 << 20)
    except urllib.error.HTTPError as e: return e.code, e.read(1 << 20)

def check(condition, message):
    if not condition: raise AssertionError(message)

def must(base, path, method="GET", body=None, headers=None, expect=200, timeout=120):
    code, raw = request(base, path, method, body, headers, timeout)
    check(code == expect, f"{method} {path}: HTTP {code}: {raw[:500].decode(errors='replace')}")
    return raw

vendor_base = ""
registry_base = ""

class Vendor(BaseHTTPRequestHandler):
    auths, paths, bodies = [], [], []
    lock = threading.Lock()
    def log_message(self, *args): pass
    def _record(self):
        with Vendor.lock:
            Vendor.paths.append(self.path)
            Vendor.auths.append(self.headers.get("Authorization") or "")
    def do_GET(self):
        self._record()
        if self.path in ("/models", "/v1/models"):
            self._json({"data": [{"id": "vendor-upstream", "object": "model"}]})
        else:
            self.send_error(404)
    def do_POST(self):
        self._record()
        if self.path not in ("/chat/completions", "/v1/chat/completions"):
            self.send_error(404); return
        length = int(self.headers.get("Content-Length") or 0)
        try: body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError: body = {}
        with Vendor.lock: Vendor.bodies.append(body)
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            for chunk in (
                '{"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant"}}]}',
                '{"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"OK"}}]}',
                '{"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}',
            ):
                self.wfile.write(("data: " + chunk + "\n\n").encode()); self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
        else:
            self._json({"id": "c1", "object": "chat.completion",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}]})
    def _json(self, payload):
        raw = json.dumps(payload).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

class HookServer(BaseHTTPRequestHandler):
    auths, paths = [], []
    lock = threading.Lock()
    def log_message(self, *args): pass
    def do_GET(self):
        with HookServer.lock:
            HookServer.paths.append(self.path)
            HookServer.auths.append(self.headers.get("Authorization") or "")
        if self.path.startswith("/hook-hit"):
            raw = b"hooked"
            self.send_response(200); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        elif self.path.startswith("/redirect"):
            self.send_response(302); self.send_header("Location", vendor_base + "/landing"); self.end_headers()
        else:
            self.send_error(404)

class Registry(BaseHTTPRequestHandler):
    version = "1.0.0"
    lock = threading.Lock()
    source = {}
    def log_message(self, *args): pass
    def do_GET(self):
        if self.path in ("/@fixture%2fsmoke", "/@fixture/smoke"):
            with Registry.lock: ver = Registry.version
            versions = {v: {"name": "@fixture/smoke", "version": v,
                            "dist": {"tarball": registry_base + "/@fixture/smoke/-/smoke-" + v + ".tgz"}}
                        for v in ("1.0.0", "1.1.0")}
            self._json({"name": "@fixture/smoke", "dist-tags": {"latest": ver}, "versions": versions})
        elif "/smoke-" in self.path and self.path.endswith(".tgz"):
            with Registry.lock: ver = Registry.version
            raw = npm_tarball(Registry.source, ver)
            self.send_response(200); self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        else:
            self.send_error(404)
    def _json(self, payload):
        raw = json.dumps(payload).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

def npm_tarball(source, ver):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for name, body in source.items():
            if name == "package.json":
                pkg = json.loads(body); pkg["version"] = ver; body = json.dumps(pkg)
            data = body.encode()
            info = tarfile.TarInfo("package/" + name); info.size = len(data); info.mode = 0o644
            tf.addfile(info, io.BytesIO(data))
    return buf.getvalue()

PROVIDER_JS = """const ID = "fixpath"
export const FixturePlugin = async () => ({
  config: async (cfg) => {
    cfg.provider = cfg.provider ?? {}
    cfg.provider[ID] = {
      name: "Fixpath", npm: "@ai-sdk/openai-compatible",
      api: process.env.FAKE_API || "https://fixture.invalid/v1",
      models: { "m-path": { name: "Path model", limit: { context: 1000, output: 100 } } },
    }
  },
  auth: {
    provider: ID,
    methods: [
      { type: "api", label: "API key", authorize: async (inputs) => ({ type: "success", key: inputs.credential || "public-fixture" }) },
      { type: "oauth", label: "Slow web",
        authorize: async (inputs, { signal }) => {
          await new Promise((resolve, reject) => {
            const timer = setTimeout(resolve, 30000)
            signal?.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("cancelled", "AbortError")) })
          })
          return { type: "success", url: "http://127.0.0.1:9/callback", callback: async () => ({ type: "success", refresh: "r-fixpath" }) }
        },
        callback: async () => ({ type: "success", refresh: "r-fixpath" }) },
    ],
  },
})
"""
MIDDLEWARE_JS = """export function onRequest(body) { return { ...body, note: "recalled" } }
"""
HOOKS_JS = """export async function onRequest(body, ctx) {
  const plain = await fetch(ctx.options.modelsURL)
  const explicit = await fetch(ctx.options.modelsURL, { headers: { Authorization: "Bearer not-a-real-key" } })
  const redirect = await fetch(ctx.options.redirectURL, { headers: { Authorization: "Bearer sk-magpie-fakevalue" } })
  const viaRequest = await fetch(new Request(ctx.options.modelsURL), { headers: {} })
  await fetch(ctx.options.hookURL, { signal: ctx.signal }).catch(() => {})
  let models = 0
  if (plain.ok) { try { models = JSON.parse(await plain.text()).data.length } catch {} }
  return { ...body, tag: "hooked", bridge: {
    status: plain.status, models, explicit: explicit.status,
    redirect: redirect.status, requestForm: viaRequest.status,
  } }
}
export async function onResponse(reply, ctx) {
  const fs = await import("node:fs")
  fs.appendFileSync(ctx.options.output, JSON.stringify(reply) + "\\n")
}
"""
TOOLS_JS = """export const ToolsPlugin = async () => ({
  tool: { fixture_echo: { description: "Echo the text back",
    args: { type: "object", properties: { q: { type: "string" } }, required: ["q"] },
    execute: async (args) => "echo:" + args.q } },
})
"""
NPM_PLUGIN_JS = PROVIDER_JS.replace('"fixpath"', '"npmprov"')

def write_dir(path, files):
    path.mkdir(parents=True, exist_ok=True)
    for name, body in files.items(): (path / name).write_text(body, encoding="utf-8")

class Core:
    def __init__(self, exe, root, admin_port, gateway_port, admin_key, bun):
        self.exe, self.root, self.bun = exe, root, bun
        self.admin_port, self.gateway_port, self.admin_key = admin_port, gateway_port, admin_key
        self.admin = "http://127.0.0.1:" + str(admin_port)
        self.gateway = "http://127.0.0.1:" + str(gateway_port)
        env = {"PATH": os.environ["PATH"], "HOME": str(root), "TMPDIR": str(root / "tmp"),
               "XDG_CONFIG_HOME": str(root / "config"), "XDG_CACHE_HOME": str(root / "cache"),
               "MAGPIE_WEB_KEY": admin_key, "MAGPIE_ADDR": "127.0.0.1:" + str(gateway_port),
               "MAGPIE_BUN": str(bun), "FAKE_API": vendor_base + "/v1"}
        self.proc = subprocess.Popen([str(exe), "serve", "--web=127.0.0.1:" + str(admin_port)],
                                     env=env, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.tail = []
        threading.Thread(target=self._drain, daemon=True).start()
        self.cookie = {"Cookie": "magpie_web_" + str(admin_port) + "=" + admin_key}
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise AssertionError("core exited during startup:\n" + "\n".join(self.tail))
            try:
                code, _ = request(self.admin, "/api/mobile/capabilities", headers=self.cookie, timeout=5)
                if code == 200: break
            except (OSError, urllib.error.URLError): pass
            time.sleep(0.1)
        else:
            raise AssertionError("management never became ready:\n" + "\n".join(self.tail))
    def _drain(self):
        for line in iter(self.proc.stdout.readline, b""):
            self.tail.append(line.decode(errors="replace")); self.tail[:] = self.tail[-40:]
    def api(self, path, method="GET", body=None, expect=200, timeout=120):
        return must(self.admin, path, method, body, self.cookie, expect, timeout)
    def stop(self):
        if self.proc.poll() is None: self.proc.send_signal(signal.SIGTERM)
        try: self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill(); self.proc.wait(timeout=5)

def bridge_key_id(root):
    record = root / "config" / "magpie" / "android-plugin-gateway-key.json"
    check(record.is_file(), "the bridge key record is missing: " + str(record))
    data = json.loads(record.read_text())
    key_id = data.get("id") if isinstance(data, dict) else None
    check(isinstance(key_id, str) and key_id, "the bridge key record has no id: " + json.dumps(data))
    return key_id

def last_bridge():
    with Vendor.lock:
        return (Vendor.bodies[-1] if Vendor.bodies else {}).get("bridge") or {}

def main():
    opts = argparse.ArgumentParser()
    opts.add_argument("--exe", required=True); opts.add_argument("--bun", required=True)
    args = opts.parse_args()
    exe, bun = Path(args.exe).resolve(), Path(args.bun).resolve()
    assert exe.is_file(), "core executable missing: " + str(exe)
    assert bun.is_file(), "bun executable missing: " + str(bun)

    global vendor_base, registry_base
    vendor_srv = ThreadingHTTPServer(("127.0.0.1", 0), Vendor)
    threading.Thread(target=vendor_srv.serve_forever, daemon=True).start()
    vendor_base = "http://127.0.0.1:" + str(vendor_srv.server_address[1])
    hooks_srv = ThreadingHTTPServer(("127.0.0.1", 0), HookServer)
    threading.Thread(target=hooks_srv.serve_forever, daemon=True).start()
    hooks_base = "http://127.0.0.1:" + str(hooks_srv.server_address[1])
    Registry.source = {"package.json": '{"name":"@fixture/smoke","version":"0.0.0","type":"module","main":"./index.js"}',
                       "index.js": NPM_PLUGIN_JS}
    registry_srv = ThreadingHTTPServer(("127.0.0.1", 0), Registry)
    threading.Thread(target=registry_srv.serve_forever, daemon=True).start()
    registry_base = "http://127.0.0.1:" + str(registry_srv.server_address[1])

    root = Path(tempfile.mkdtemp(prefix="magpie-official-plugins-"))
    for d in ("config", "cache", "tmp"): (root / d).mkdir()
    admin_port, gateway_port = free_port(), free_port()
    while gateway_port == admin_port: gateway_port = free_port()
    admin_key = secrets.token_hex(32)

    with vendor_srv, hooks_srv, registry_srv:
        core = Core(exe, root, admin_port, gateway_port, admin_key, bun)
        try:
            run_suite(core, root, hooks_base)
        finally:
            core.stop()
        time.sleep(1)
        orphans = []
        for pid in os.listdir("/proc"):
            if not pid.isdigit(): continue
            try:
                if os.readlink("/proc/" + pid + "/exe").endswith("/bun"): orphans.append(pid)
            except OSError: pass
        check(not orphans, "bun processes survived the core: " + ", ".join(orphans))
        with Vendor.lock:
            leaked = [a for a in Vendor.auths if "sk-magpie-" in a and a != "Bearer " + VENDOR_KEY]
            vendor_set = set(Vendor.auths)
        check(not leaked, "a magpie key reached the vendor: " + repr(leaked))
        check(vendor_set == {"Bearer " + VENDOR_KEY}, "the vendor saw unexpected authorizations: " + repr(vendor_set))
        with HookServer.lock:
            hook_leaks = [a for a in HookServer.auths if "sk-magpie-" in a and a != "Bearer sk-magpie-fakevalue"]
        check(not hook_leaks, "a magpie key reached the hook server: " + repr(hook_leaks))
    print("official plugin smoke: PASS (" + CORE_VERSION + ")")

def run_suite(core, root, hooks_base):
    cap = json.loads(core.api("/api/mobile/capabilities"))
    check(cap.get("coreVersion") == CORE_VERSION, "capabilities coreVersion: " + json.dumps(cap))
    check(cap.get("fullAdmin") is True and cap.get("androidHost") is True, "not the Android host: " + json.dumps(cap))
    version = json.loads(core.api("/version"))
    check(version == {"name": "magpie", "version": CORE_VERSION}, "/version: " + json.dumps(version))

    fixtures = root / "fixtures"
    prov_dir = fixtures / "provider"
    write_dir(prov_dir, {"package.json": '{"name":"fixture-provider","version":"0.0.1","type":"module","main":"./index.js"}', "index.js": PROVIDER_JS})
    mw_dir = fixtures / "middleware"
    write_dir(mw_dir, {"package.json": '{"name":"fixture-middleware","version":"0.0.1","magpie":{"middleware":"./middleware.js"}}', "middleware.js": MIDDLEWARE_JS})
    hooks_dir = fixtures / "hooks"
    write_dir(hooks_dir, {"package.json": '{"name":"fixture-hooks","version":"0.0.1","type":"module","magpie":{"hooks":"./request.js"}}', "request.js": HOOKS_JS})
    tools_dir = fixtures / "tools"
    write_dir(tools_dir, {"package.json": '{"name":"fixture-tools","version":"0.0.1","type":"module","main":"./index.js"}', "index.js": TOOLS_JS})

    specs = {}
    for name, path in (("provider", prov_dir), ("middleware", mw_dir), ("hooks", hooks_dir), ("tools", tools_dir)):
        state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": str(path)}, timeout=300))
        entry = next((e for e in state["plugins"] if e["spec"] == str(path)), None)
        check(entry is not None and not entry.get("error"), name + " add: " + json.dumps(entry))
        specs[name] = str(path)

    # the hooks plugin's options, before the host starts: the gateway and
    # hook addresses its fetches take, the output file onResponse appends.
    out = root / "responses.jsonl"
    core.api("/api/plugins/options", "POST", {"Spec": specs["hooks"], "Options": {
        "output": str(out), "hookURL": hooks_base + "/hook-hit",
        "redirectURL": hooks_base + "/redirect",
        "modelsURL": core.gateway + "/v1/models", "gatewayTimeoutMs": 20000}})

    user_key = json.loads(core.api("/api/caller-keys/add-key", "POST", {"Name": "smoke"}))["secret"]
    check(user_key.startswith("sk-magpie-"), "the user key is not a gateway key")

    # the community npm packages and the GitHub plugin, from the real
    # outside: actual npm downloads and an actual git clone at a commit.
    for spec in COMMUNITY_NPM:
        state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": spec}, timeout=300))
        pkg_name = spec.rsplit("@", 1)[0] if spec.count("@") > 1 else spec
        entry = next((e for e in state["plugins"] if e["spec"] == spec or e.get("package") == pkg_name), None)
        check(entry is not None, spec + " not in the list after add: " + json.dumps([e.get("spec") for e in state["plugins"]]))
        print("community npm installed:", spec)
    state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": GITHUB_SPEC}, timeout=300))
    gh_entry = next((e for e in state["plugins"] if e.get("package") == GITHUB_PACKAGE or GITHUB_SPEC in e.get("spec", "")), None)
    check(gh_entry is not None and not gh_entry.get("error"), "github add: " + json.dumps(gh_entry))
    print("github plugin installed:", GITHUB_SPEC)

    # warm the host: a bogus chat starts the Bun host through the
    # gateway's hook chain; then wait for the provider to be listed.
    request(core.gateway, "/v1/chat/completions", "POST",
            {"model": "warm-up", "messages": [{"role": "user", "content": "hi"}]},
            {"Authorization": "Bearer " + user_key}, timeout=30)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        state = json.loads(core.api("/api/plugins"))
        prov = next((e for e in state["plugins"] if e["spec"] == specs["provider"]), None)
        if prov and prov.get("named") and "fixpath" in (prov.get("providers") or []): break
        time.sleep(0.5)
    else:
        raise AssertionError("the provider never appeared: " + json.dumps(prov))

    done = json.loads(core.api("/api/plugin-signin", "POST", {"Provider": "fixpath", "Method": 0, "Key": VENDOR_KEY}))
    check(done.get("state") == "done", "api sign-in: " + json.dumps(done))
    started = json.loads(core.api("/api/plugin-signin", "POST", {"Provider": "fixpath", "Method": 1}))
    check(started.get("id") and started.get("state") in ("installing", "waiting"), "oauth sign-in: " + json.dumps(started))
    core.api("/api/signin/" + started["id"] + "/cancel", "POST", {}, expect=204)
    for _ in range(50):
        code, raw = request(core.admin, "/api/signin/" + started["id"], headers=core.cookie)
        if code == 404 or (code == 200 and json.loads(raw).get("state") in ("canceled", "failed")): break
        time.sleep(0.1)
    else:
        raise AssertionError("the cancelled sign-in never ended: " + raw[:300].decode(errors="replace"))

    core.api("/api/provider/save", "POST", {
        "id": "syncvendor", "name": "Sync vendor", "key": VENDOR_KEY,
        "chat": vendor_base + "/v1", "baseAPI": "chat", "models": ["syncvendor/sync-model"]})

    def chat(model, stream):
        body = {"model": model, "stream": stream, "messages": [{"role": "user", "content": "hello"}]}
        raw = must(core.gateway, "/v1/chat/completions", "POST", body, {"Authorization": "Bearer " + user_key})
        said = ""
        if stream:
            for line in raw.decode().splitlines():
                p = line[6:] if line.startswith("data: ") else ""
                if p and p != "[DONE]":
                    said += json.loads(p)["choices"][0]["delta"].get("content") or ""
        else:
            said = json.loads(raw)["choices"][0]["message"]["content"]
        check(said == "OK", "chat " + model + " said: " + repr(said) + " body: " + raw[:400].decode(errors="replace"))

    for model in ("fixpath/m-path", "syncvendor/sync-model"):
        code, raw = request(core.gateway, "/v1/models", headers={"Authorization": "Bearer " + user_key})
        check(code == 200 and model in raw.decode(), "models list lacks " + model + ": " + raw[:500].decode(errors="replace"))
        chat(model, False)
        chat(model, True)

    with Vendor.lock: bodies = list(Vendor.bodies)
    check(len(bodies) >= 4, "the vendor saw " + str(len(bodies)) + " chats")
    for body in bodies:
        check(body.get("note") == "recalled", "the middleware's note is missing: " + json.dumps(body)[:300])
        check(body.get("tag") == "hooked", "the hooks' tag is missing: " + json.dumps(body)[:300])
        bridge = body.get("bridge") or {}
        check(bridge.get("status") == 200 and bridge.get("models", 0) > 0,
              "the authless fetch was not authenticated: " + json.dumps(bridge))
        check(bridge.get("explicit") == 401, "an explicit key was not left as sent: " + json.dumps(bridge))
        check(bridge.get("requestForm") == 200, "fetch(Request, init) lost the bridge: " + json.dumps(bridge))
        check(bridge.get("redirect") == 302, "the cross-origin redirect was followed: " + json.dumps(bridge))
    with Vendor.lock:
        check(not any(p.startswith("/landing") for p in Vendor.paths), "the redirect landed on the other origin")
    check(out.is_file() and '"content":"OK"' in out.read_text(), "onResponse wrote no reply lines")

    listed = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST",
                             {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, {"Authorization": "Bearer " + user_key}))
    check("fixture_echo" in json.dumps(listed.get("result", {})), "the fixture tool is not listed")
    called = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST",
                             {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                              "params": {"name": "fixture_echo", "arguments": {"q": "hi"}}},
                             {"Authorization": "Bearer " + user_key}))
    check("echo:hi" in json.dumps(called.get("result", {})), "the tool call failed: " + json.dumps(called)[:400])
    tools_listed = json.dumps(listed.get("result", {}))
    check("calc" in tools_listed.lower(), "the community tools-calc tool is not listed")

    core.api("/api/plugins/off", "POST", {"Spec": specs["hooks"], "Off": True})
    state = json.loads(core.api("/api/plugins"))
    entry = next(e for e in state["plugins"] if e["spec"] == specs["hooks"])
    check(entry.get("off") is True, "the plugin is not off in the list")
    time.sleep(1.5)
    chat("syncvendor/sync-model", False)
    with Vendor.lock: last = Vendor.bodies[-1]
    check(last.get("tag") != "hooked", "the off plugin's hooks still ran")
    core.api("/api/plugins/off", "POST", {"Spec": specs["hooks"], "Off": False})
    time.sleep(1.5)

    core.api("/api/plugins/registry", "POST", {"URL": registry_base})
    state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": "@fixture/smoke"}, timeout=300))
    npm_entry = next((e for e in state["plugins"] if e["spec"] == "@fixture/smoke"), None)
    check(npm_entry is not None and npm_entry.get("version") == "1.0.0", "npm add: " + json.dumps(npm_entry))
    with Registry.lock: Registry.version = "1.1.0"
    checked = json.loads(core.api("/api/plugins/check", "POST", {}, timeout=120))
    latest = next((e.get("latest") for e in checked.get("plugins", []) if e.get("spec") == "@fixture/smoke"), None)
    check(latest == "1.1.0", "check found " + repr(latest))
    core.api("/api/plugins/update", "POST", {}, timeout=300)
    state = json.loads(core.api("/api/plugins"))
    entry = next(e for e in state["plugins"] if e["spec"] == "@fixture/smoke")
    check(entry.get("version") == "1.1.0", "npm update left " + repr(entry.get("version")))
    core.api("/api/plugins/remove", "POST", {"Spec": "@fixture/smoke"})
    state = json.loads(core.api("/api/plugins"))
    check(not any(e["spec"] == "@fixture/smoke" for e in state["plugins"]), "the removed plugin is still listed")
    core.api("/api/plugins/registry", "POST", {"URL": ""})

    key_id = bridge_key_id(root)
    core.api("/api/caller-keys/off-key", "POST", {"Key": key_id})
    keys = json.loads(core.api("/api/caller-keys"))["keys"]
    mine = [k for k in keys if k.get("id") == key_id]
    check(len(mine) == 1 and mine[0].get("off") is True, "the off bridge key: " + json.dumps(mine))
    check(bridge_key_id(root) == key_id, "the off key was replaced in the record")
    chat("syncvendor/sync-model", False)
    check(last_bridge().get("status") == 401, "the disabled key's fetches were not refused: " + json.dumps(last_bridge()))
    core.stop()
    core = Core(core.exe, core.root, core.admin_port, core.gateway_port, core.admin_key, core.bun)
    chat("syncvendor/sync-model", False)
    check(last_bridge().get("status") == 401, "a new key was issued after the key was disabled: " + json.dumps(last_bridge()))
    check(bridge_key_id(root) == key_id, "the record moved without an explicit restore")
    core.api("/api/caller-keys/remove-key", "POST", {"Key": key_id})
    core.stop()
    core = Core(core.exe, core.root, core.admin_port, core.gateway_port, core.admin_key, core.bun)
    chat("syncvendor/sync-model", False)
    check(last_bridge().get("status") == 401, "the removed key's fetches were not refused after restart: " + json.dumps(last_bridge()))
    restored = json.loads(core.api("/api/androidhost/plugin-gateway-access/restore", "POST", {}))
    check(restored == {"ok": True}, "restore: " + json.dumps(restored))
    for _ in range(50):
        chat("syncvendor/sync-model", False)
        if last_bridge().get("status") == 200: break
        time.sleep(0.3)
    else:
        raise AssertionError("the restored bridge never answered: " + json.dumps(last_bridge()))
    check(bridge_key_id(root) != key_id, "the restored record still names the removed key")

if __name__ == "__main__":
    main()
