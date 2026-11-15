#!/usr/bin/env python3
"""Real plugin integration on the Android-host core, end to end.

Three plugin kinds are installed from path fixtures, the real Bun host is
started, and every chat is answered through the real gateway to a vendor
fixture that speaks OpenAI's wire. The Android bridge's fetch injection is
exercised for real: the authless fetch and the "Bearer magpie" placeholder
to the gateway's own origin are authenticated, an explicit key is left as
it was sent, and no magpie key ever reaches a server outside the gateway
(proven by recording every Authorization each fixture server took).

The real outside installs are part of the suite: three community npm
packages and one GitHub repository at a fixed commit, installed through
the real bun/npm/git pipeline.

The key lifecycle is asserted through the management API: off → refused
and no new key; remove → still refused after a core restart; restore (the
App's explicit POST) → answered again.
"""
import argparse, json, os, secrets, signal, shutil, socket, subprocess, sys, tempfile, threading, time, urllib.error, urllib.request
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

def await_check(condition, message, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition(): return
        time.sleep(0.1)
    raise AssertionError(message)

def must(base, path, method="GET", body=None, headers=None, expect=200, timeout=120):
    code, raw = request(base, path, method, body, headers, timeout)
    check(code == expect, f"{method} {path}: HTTP {code}: {raw[:500].decode(errors='replace')}")
    return raw

vendor_base = ""

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
        elif self.path == "/landing":
            raw = b"redirect-landed"
            self.send_response(200); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        else:
            self.send_error(404)

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
      { type: "api", label: "API key" },
      { type: "oauth", label: "Slow web",
        authorize: async () => ({ url: "https://fixture.invalid/authorize", method: "auto",
          callback: async (code, opts) => new Promise((resolve, reject) => {
            const timer = setTimeout(() => resolve({type:"failed"}), 30000)
            opts?.signal?.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("cancelled", "AbortError")) })
          }) }) },
    ],
  },
})
"""
MIDDLEWARE_JS = """export function onRequest(body) {
  body.messages[body.messages.length - 1].content += "|middleware-rewritten"
  return body
}
"""
HOOKS_JS = """export async function onRequest(body, ctx) {
  const text = String(body.messages?.at(-1)?.content || "")
  if (text.includes("hook-throw")) throw new Error("fixture failure")
  if (text.includes("hook-timeout") || text.includes("hook-cancel")) {
    const fs = await import("node:fs")
    fs.appendFileSync(ctx.options.failureOutput, "started\\n")
    await new Promise((resolve, reject) => {
      const timer = setTimeout(resolve, 30000)
      ctx.signal.addEventListener("abort", () => {
        clearTimeout(timer)
        fs.appendFileSync(ctx.options.failureOutput, "aborted\\n")
        reject(new DOMException("fixture aborted", "AbortError"))
      }, {once:true})
    })
  }
  const plain = await fetch(ctx.options.modelsURL)
  const placeholder = await fetch(ctx.options.modelsURL, { headers: { Authorization: "Bearer magpie" } })
  const explicit = await fetch(ctx.options.modelsURL, { headers: { Authorization: "Bearer not-a-real-key" } })
  const viaRequest = await fetch(new Request(ctx.options.modelsURL), { headers: {} })
  await fetch(ctx.options.hookURL, { signal: ctx.signal }).catch(() => {})
  let models = 0
  if (plain.ok) { try { models = JSON.parse(await plain.text()).data.length } catch {} }
  const bridge = {
    status: plain.status, placeholder: placeholder.status,
    explicit: explicit.status, requestForm: viaRequest.status, models,
  }
  const fs = await import("node:fs")
  fs.appendFileSync(ctx.options.bridgeOutput, JSON.stringify(bridge) + "\\n")
  ctx.state.link = "linked"
  body.messages[body.messages.length - 1].content += "|hook-rewritten"
  return body
}
export async function onResponse(reply, ctx) {
  const fs = await import("node:fs")
  fs.appendFileSync(ctx.options.output, JSON.stringify({reply, success:ctx.success, state:ctx.state.link}) + "\\n")
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

all_core_children = []
core_instances = []

def record_core_children(core_pid):
    for pid in os.listdir("/proc"):
        if not pid.isdigit(): continue
        try:
            stat = Path("/proc/" + pid + "/stat").read_text()
            ppid = int(stat.rsplit(")", 1)[1].split()[1])
            if ppid == core_pid: all_core_children.append(pid)
        except (OSError, IndexError, ValueError): pass

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
        core_instances.append(self)
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
        record_core_children(self.proc.pid)
    def _drain(self):
        for line in iter(self.proc.stdout.readline, b""):
            self.tail.append(line.decode(errors="replace")); self.tail[:] = self.tail[-40:]
    def api(self, path, method="GET", body=None, expect=200, timeout=120):
        return must(self.admin, path, method, body, self.cookie, expect, timeout)
    def stop(self):
        record_core_children(self.proc.pid)
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

bridge_report = None

def last_bridge():
    if bridge_report is None or not bridge_report.is_file(): return {}
    lines = bridge_report.read_text().splitlines()
    return json.loads(lines[-1]) if lines else {}

def main(exe, bun):
    assert exe.is_file(), "core executable missing: " + str(exe)
    assert bun.is_file(), "bun executable missing: " + str(bun)

    global vendor_base
    vendor_srv = ThreadingHTTPServer(("127.0.0.1", 0), Vendor)
    threading.Thread(target=vendor_srv.serve_forever, daemon=True).start()
    vendor_base = "http://127.0.0.1:" + str(vendor_srv.server_address[1])
    hooks_srv = ThreadingHTTPServer(("127.0.0.1", 0), HookServer)
    threading.Thread(target=hooks_srv.serve_forever, daemon=True).start()
    hooks_base = "http://127.0.0.1:" + str(hooks_srv.server_address[1])

    root = Path(tempfile.mkdtemp(prefix="magpie-official-plugins-"))
    for name in ("config", "cache", "tmp"):
        (root / name).mkdir()
    admin_port, gateway_port = free_port(), free_port()
    while gateway_port == admin_port: gateway_port = free_port()
    admin_key = secrets.token_hex(32)

    try:
        core = Core(exe, root, admin_port, gateway_port, admin_key, bun)
        try:
            run_suite(core, root, hooks_base)
        finally:
            for instance in reversed(core_instances): instance.stop()
        time.sleep(2)
        alive = [pid for pid in all_core_children if Path("/proc/" + pid).exists()]
        check(not alive, "the core's children (bun host) survived: " + ", ".join(alive))
        with Vendor.lock:
            leaked = [a for a in Vendor.auths if "sk-magpie-" in a and a != "Bearer " + VENDOR_KEY]
            vendor_set = set(Vendor.auths)
        check(not leaked, "a magpie key reached the vendor: " + repr(leaked))
        check(vendor_set == {"Bearer " + VENDOR_KEY}, "the vendor saw unexpected authorizations: " + repr(vendor_set))
        with HookServer.lock:
            hook_leaks = [a for a in HookServer.auths if "sk-magpie-" in a]
        check(not hook_leaks, "a magpie key reached the hook server: " + repr(hook_leaks))
    finally:
        for instance in reversed(core_instances): instance.stop()
        for server in (vendor_srv, hooks_srv):
            server.shutdown()
            server.server_close()
        shutil.rmtree(root, ignore_errors=True)
    print("official plugin smoke: PASS (" + CORE_VERSION + ")")

def run_suite(core, root, hooks_base):
    cap = json.loads(core.api("/api/mobile/capabilities"))
    check(cap.get("coreVersion") == CORE_VERSION, "capabilities coreVersion: " + json.dumps(cap))
    check(cap.get("fullAdmin") is True and cap.get("androidHost") is True, "not the Android host: " + json.dumps(cap))

    user_key = json.loads(core.api("/api/caller-keys/add-key", "POST", {"Name": "smoke"}))["secret"]
    check(user_key.startswith("sk-magpie-"), "the user key is not a gateway key")
    check(request(core.gateway, "/api/hello")[0] == 401, "anonymous Android gateway probe was accepted")
    hello = json.loads(must(core.gateway, "/api/hello", headers={"Authorization": "Bearer " + user_key}))
    check(hello.get("name") == "magpie" and hello.get("version") == CORE_VERSION,
          "/api/hello: " + json.dumps(hello))

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

    out = root / "responses.jsonl"
    global bridge_report
    bridge_report = root / "bridge-reports.jsonl"
    failure_output = root / "hook-aborts.txt"
    hook_options = {
        "output": str(out), "hookURL": hooks_base + "/hook-hit",
        "bridgeOutput": str(bridge_report),
        "failureOutput": str(failure_output),
        "modelsURL": core.gateway + "/v1/models", "gatewayTimeoutMs": 20000}
    core.api("/api/plugins/options", "POST", {"Spec": specs["hooks"], "Options": hook_options})

    # community npm packages and the GitHub plugin, from the real outside.
    for spec in COMMUNITY_NPM:
        state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": spec}, timeout=300))
        pkg_name = spec.rsplit("@", 1)[0] if spec.count("@") > 1 else spec
        entry = next((e for e in state["plugins"] if e["spec"] == spec or e.get("package") == pkg_name), None)
        check(entry is not None and not entry.get("error"),
              spec + " install: " + json.dumps(entry))
        print("community npm installed:", spec)
    state = json.loads(core.api("/api/plugins/add", "POST", {"Spec": GITHUB_SPEC}, timeout=300))
    gh_entry = next((e for e in state["plugins"] if e.get("package") == GITHUB_PACKAGE), None)
    check(gh_entry is not None and not gh_entry.get("error"), "github add: " + json.dumps(gh_entry))
    print("github plugin installed:", GITHUB_PACKAGE)

    # warm the host: a bogus chat starts the Bun host through the
    # gateway's hook chain; then wait for the provider to be listed.
    request(core.gateway, "/v1/chat/completions", "POST",
            {"model": "warm-up", "messages": [{"role": "user", "content": "hi"}]},
            {"Authorization": "Bearer " + user_key}, timeout=30)
    prov = None
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        state = json.loads(core.api("/api/plugins"))
        prov = next((e for e in state["plugins"] if e["spec"] == specs["provider"]), None)
        providers = json.loads(core.api("/api/providers")).get("plugins", [])
        if prov and prov.get("named") and any(p.get("pid") == "fixpath" for p in providers): break
        time.sleep(0.5)
    else:
        raise AssertionError("the provider never appeared: " + json.dumps(prov))

    # api sign-in: the method has no authorize, so the key is stored
    # directly as the auth (the normal api path, not keyless).
    done = json.loads(core.api("/api/plugin-signin", "POST", {"Provider": "fixpath", "Method": 0, "Key": VENDOR_KEY}))
    deadline = time.monotonic() + 30
    while done.get("state") != "done" and time.monotonic() < deadline:
        check(done.get("state") not in ("failed", "canceled"), "API sign-in failed")
        time.sleep(0.2)
        done = json.loads(core.api("/api/signin/" + done["id"]))
    check(done.get("state") == "done", "API sign-in did not finish")
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
        "chat": vendor_base + "/v1", "baseAPI": "chat", "models": ["sync-model"]})

    def chat(model, stream, expected="OK"):
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
        check(said == expected, "chat " + model + " said: " + repr(said) + " body: " + raw[:400].decode(errors="replace"))

    for model in ("fixpath/m-path", "syncvendor/sync-model"):
        code, raw = request(core.gateway, "/v1/models", headers={"Authorization": "Bearer " + user_key})
        check(code == 200 and model in raw.decode(), "models list lacks " + model + ": " + raw[:500].decode(errors="replace"))
        chat(model, False)
        chat(model, True)

    with Vendor.lock: bodies = list(Vendor.bodies)
    check(len(bodies) >= 4, "the vendor saw " + str(len(bodies)) + " chats")
    for body in bodies:
        text = json.dumps(body.get("messages", []))
        check("middleware-rewritten" in text, "the middleware's request rewrite is missing")
        check("hook-rewritten" in text, "the async hook's request rewrite is missing")
    check(bridge_report.is_file(), "async hook produced no bridge reports")
    for line in bridge_report.read_text().splitlines():
        bridge = json.loads(line)
        check(bridge.get("status") == 200, "the authless fetch was not authenticated: " + json.dumps(bridge))
        check(bridge.get("placeholder") == 200, "the Bearer magpie placeholder was not replaced: " + json.dumps(bridge))
        check(bridge.get("explicit") == 401, "an explicit key was not left as sent: " + json.dumps(bridge))
        check(bridge.get("requestForm") == 200, "fetch(Request, init) lost the bridge: " + json.dumps(bridge))
    check(out.is_file() and '"content":"OK"' in out.read_text(), "onResponse wrote no reply lines")
    check(any(json.loads(line).get("success") is True and json.loads(line).get("state") == "linked"
              for line in out.read_text().splitlines()), "onResponse lost successful completion or request-owned state")
    verify_actual_bridge_redirect(core, root, hooks_base)

    community_state = json.loads(core.api("/api/plugins"))
    word_guard = next(e for e in community_state["plugins"] if e.get("package") == "@magpie-community/middleware-word-guard")
    check(word_guard.get("middleware", {}).get("calls", 0) > 0, "community middleware did not run")
    core.api("/api/plugins/options", "POST", {"Spec": word_guard["spec"], "Options": {
        "words": ["community-block"], "action": "reject"}})
    with Vendor.lock: before_reject = len(Vendor.bodies)
    rejected = request(core.gateway, "/v1/chat/completions", "POST", {
        "model": "syncvendor/sync-model", "stream": False,
        "messages": [{"role": "user", "content": "community-block"}]}, {"Authorization": "Bearer " + user_key})
    check(rejected[0] == 400, "community word guard did not reject its configured word")
    with Vendor.lock: check(len(Vendor.bodies) == before_reject, "rejected community request reached the vendor")
    core.api("/api/plugins/options", "POST", {"Spec": word_guard["spec"], "Options": {
        "words": ["OK"], "action": "mask", "replies": True, "mask": "community-mask"}})
    chat("syncvendor/sync-model", False, "community-mask")
    chat("syncvendor/sync-model", True, "community-mask")
    core.api("/api/plugins/options", "POST", {"Spec": word_guard["spec"], "Options": {}})
    print("community word guard reject, response and SSE transform: PASS")

    # MCP tools: the names are <plugin>_<tool>, so take them from the list.
    listed = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST",
                             {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, {"Authorization": "Bearer " + user_key}))
    tools_raw = json.dumps(listed.get("result", {}))
    check("fixture_echo" in tools_raw, "the fixture tool is not listed: " + tools_raw[:500])
    check("calc" in tools_raw.lower(), "the community tools-calc tool is not listed: " + tools_raw[:500])
    tool_items = (listed.get("result") or {}).get("tools") or []
    echo_name = next((t["name"] for t in tool_items if "fixture_echo" in t.get("name", "")), None)
    check(echo_name, "the fixture_echo tool name: " + json.dumps(tool_items)[:500])
    called = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST",
                             {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                              "params": {"name": echo_name, "arguments": {"q": "hi"}}},
                             {"Authorization": "Bearer " + user_key}))
    check("echo:hi" in json.dumps(called.get("result", {})), "the tool call failed: " + json.dumps(called)[:400])
    calc = next((t for t in tool_items if "calc" in t.get("name", "") and
                 "expression" in t.get("inputSchema", {}).get("properties", {})), None)
    check(calc, "community calculator expression tool was not found")
    calculated = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST", {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": calc["name"], "arguments": {"expression": "0.1+0.2"}}},
        {"Authorization": "Bearer " + user_key}))
    calc_text = "".join(c.get("text", "") for c in calculated.get("result", {}).get("content", []))
    check(json.loads(calc_text).get("exact") == "3/10", "community calculator did not execute exact arithmetic")
    print("community tools-calc real MCP execution: PASS")

    core.api("/api/plugins/off", "POST", {"Spec": specs["tools"], "Off": True})
    off_tools = json.loads(must(core.gateway, "/mcp/magpie/tools", "POST", {
        "jsonrpc": "2.0", "id": 4, "method": "tools/list"}, {"Authorization": "Bearer " + user_key}))
    check(echo_name not in [t["name"] for t in off_tools.get("result", {}).get("tools", [])],
          "disabled fixture tool is still advertised")
    core.api("/api/plugins/off", "POST", {"Spec": specs["tools"], "Off": False})

    def hook_failure(text):
        raw = must(core.gateway, "/v1/chat/completions", "POST", {
            "model": "syncvendor/sync-model", "stream": False,
            "messages": [{"role": "user", "content": text}]}, {"Authorization": "Bearer " + user_key})
        check(json.loads(raw)["choices"][0]["message"]["content"] == "OK", "failed hook prevented the request")
        with Vendor.lock: last = Vendor.bodies[-1]
        check("hook-rewritten" not in json.dumps(last.get("messages", [])), "failed hook committed a rewrite")

    hook_failure("hook-throw")
    state = json.loads(core.api("/api/plugins"))
    diag = next(e for e in state["plugins"] if e["spec"] == specs["hooks"])["gateway"]
    check(diag["lastOutcome"] == "error" and diag["failures"] > 0, "hook exception was not diagnosed")
    core.api("/api/plugins/options", "POST", {"Spec": specs["hooks"], "Options": {**hook_options, "gatewayTimeoutMs": 500}})
    started = time.monotonic()
    hook_failure("hook-timeout")
    check(time.monotonic() - started < 10, "hook timeout did not bound the gateway request")
    await_check(lambda: failure_output.is_file() and "aborted" in failure_output.read_text(), "timed-out hook was not signalled")
    state = json.loads(core.api("/api/plugins"))
    diag = next(e for e in state["plugins"] if e["spec"] == specs["hooks"])["gateway"]
    check(diag["lastOutcome"] == "timeout", "hook timeout was not diagnosed")

    core.api("/api/plugins/options", "POST", {"Spec": specs["hooks"], "Options": hook_options})
    failure_output.write_text("")
    with Vendor.lock: count_before_cancel = len(Vendor.bodies)
    cancelled_body = json.dumps({"model": "syncvendor/sync-model", "stream": False,
                                "messages": [{"role": "user", "content": "hook-cancel"}]}).encode()
    with socket.create_connection(("127.0.0.1", core.gateway_port), timeout=10) as cancelled:
        head = ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:" + str(core.gateway_port) +
                "\r\nAuthorization: Bearer " + user_key + "\r\nContent-Type: application/json\r\nContent-Length: " +
                str(len(cancelled_body)) + "\r\nConnection: close\r\n\r\n").encode()
        cancelled.sendall(head + cancelled_body)
        await_check(lambda: "started" in failure_output.read_text(), "cancelled request never reached the hook")
        cancelled.shutdown(socket.SHUT_RDWR)
    await_check(lambda: "aborted" in failure_output.read_text(), "client disconnect did not cancel the Bun hook", timeout=10)
    with Vendor.lock: check(len(Vendor.bodies) == count_before_cancel, "cancelled hook request reached the vendor")
    print("async hook exception, timeout and client cancellation: PASS")

    # off and on.
    core.api("/api/plugins/off", "POST", {"Spec": specs["hooks"], "Off": True})
    state = json.loads(core.api("/api/plugins"))
    entry = next(e for e in state["plugins"] if e["spec"] == specs["hooks"])
    check(entry.get("off") is True, "the plugin is not off in the list")
    time.sleep(1.5)
    chat("syncvendor/sync-model", False)
    with Vendor.lock: last = Vendor.bodies[-1]
    check("hook-rewritten" not in json.dumps(last.get("messages", [])), "the off plugin's hooks still ran")
    core.api("/api/plugins/off", "POST", {"Spec": specs["hooks"], "Off": False})
    time.sleep(1.5)

    # update all plugins through the real npm check (the op switch's
    # "update"), proving the update path runs.
    state = json.loads(core.api("/api/plugins/update", "POST", {}, timeout=300))
    check("plugins" in state, "update: " + json.dumps(state)[:300])
    print("plugin update: ok (" + str(len(state.get("plugins", []))) + " plugins)")

    # the bridge key lifecycle, through the management API.
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

def verify_actual_bridge_redirect(core, root, destination):
    """Execute the exact bridge prelude emitted by Go, using two real HTTP origins."""
    host_source = core.exe.parent / "upstream" / "internal" / "plugin" / "host.js"
    source = host_source.read_bytes()
    generated = [p.read_bytes() for p in (root / "cache").rglob("host-*.js")]
    prelude = next((data[:-len(source)] for data in generated
                    if data.endswith(source) and data.startswith(b"// magpie's Android host bridge")), None)
    check(prelude, "no actual Go-emitted plugin bridge script found")
    seen = []
    class Relay(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            seen.append(self.headers.get("Authorization") or "")
            self.send_response(302)
            self.send_header("Location", destination + "/landing")
            self.end_headers()
    relay = ThreadingHTTPServer(("127.0.0.1", 0), Relay)
    thread = threading.Thread(target=relay.serve_forever, daemon=True)
    thread.start()
    try:
        script = root / "bridge-redirect-probe.js"
        target = "http://127.0.0.1:" + str(relay.server_port) + "/redirect"
        script.write_bytes(prelude + ("\nconst r = await fetch(" + json.dumps(target) + ");\nconsole.log(JSON.stringify({status:r.status}));\n").encode())
        env = {"PATH": os.environ["PATH"], "HOME": str(root),
               "MAGPIE_ADDR": "127.0.0.1:" + str(relay.server_port)}
        result = subprocess.run([str(core.bun), str(script)], cwd=root, env=env,
                                capture_output=True, timeout=20)
        check(result.returncode == 0, "real Bun bridge redirect probe failed")
        check(json.loads(result.stdout).get("status") == 200, "redirect did not reach the second origin")
        check(len(seen) == 1 and seen[0].startswith("Bearer sk-magpie-key-"), "the Go bridge did not inject its key on the first origin")
        with HookServer.lock:
            landed = [auth for path, auth in zip(HookServer.paths, HookServer.auths) if path == "/landing"]
        check(landed and all(not auth for auth in landed), "injected authorization crossed the origin boundary")
        print("real Go bridge prelude / Bun cross-origin redirect: PASS")
    finally:
        relay.shutdown()
        relay.server_close()
        thread.join(timeout=5)

if __name__ == "__main__":
    import argparse
    opts = argparse.ArgumentParser(description=__doc__)
    opts.add_argument("--exe", required=True, help="the Android-host core executable")
    opts.add_argument("--bun", required=True, help="the Linux Bun executable for the plugin host")
    parsed = opts.parse_args()
    main(Path(parsed.exe).resolve(), Path(parsed.bun).resolve())
