#!/usr/bin/env python3
"""Actions-only real core/RPC/TLS proof with ephemeral certificates and credentials.

The runner maps catalog.magpie.test to 127.0.0.1 in /etc/hosts. No production
validator, resolver, transport or trust bypass is installed. SSL_CERT_FILE adds
one fixture certificate to this child core's trust roots only.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HOST = "catalog.magpie.test"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def request(base, path, cookie, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Accept": "application/json", "Cookie": cookie}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=headers)
    try:
        with HTTP.open(req, timeout=12) as response:
            return response.status, response.read(65537), response.headers
    except urllib.error.HTTPError as response:
        return response.code, response.read(65537), response.headers


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def certificate(root, name):
    cert, key = root / (name + ".crt"), root / (name + ".key")
    subprocess.run([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-days", "2", "-subj", "/CN=" + HOST,
        "-addext", "subjectAltName=DNS:" + HOST,
        "-keyout", str(key), "-out", str(cert),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return cert, key, hashlib.sha256(der).hexdigest()


class Vendor:
    def __init__(self, cert, key, credential):
        self.calls = []
        self.expected_authorization = "Bearer " + credential
        self.redirect = None
        self.fallback = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                # Store only equality, never the actual synthetic credential.
                owner.calls.append((self.path, self.headers.get("Authorization") ==
                                    owner.expected_authorization,
                                    self.headers.get("Cookie") is None))
                if owner.redirect is not None:
                    self.send_response(302)
                    self.send_header("Location", owner.redirect)
                    self.end_headers()
                    return
                if owner.fallback and self.path == "/v1/models":
                    self.send_response(404)
                    self.end_headers()
                    return
                body = b'{"data":[{"id":"tls-fixture-model"}]}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(str(cert), str(key))
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "https://" + HOST + ":" + str(self.server.server_port) + "/v1"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    check(socket.gethostbyname(HOST) == "127.0.0.1", "runner TLS fixture DNS is not isolated")
    executable = Path(args.exe).resolve()
    check(executable.is_file(), "compiled host core is missing")
    evidence = {"productionRPC": True, "TLS": "TLSv1.2+", "checks": []}
    with tempfile.TemporaryDirectory(prefix="magpie-tls-smoke-") as directory:
        root = Path(directory)
        trusted_cert, trusted_key, trusted_pin = certificate(root, "trusted")
        private_cert, private_key, private_pin = certificate(root, "self-signed")
        vendor_key = "synthetic-tls-" + secrets.token_hex(20)
        trusted = Vendor(trusted_cert, trusted_key, vendor_key)
        private = Vendor(private_cert, private_key, vendor_key)
        admin_port, gateway_port = free_port(), free_port()
        while admin_port == gateway_port:
            gateway_port = free_port()
        management_key = secrets.token_hex(32)
        for name in ("config", "cache", "tmp", "empty-certs"):
            (root / name).mkdir()
        env = {"PATH": os.environ["PATH"], "HOME": str(root),
               "XDG_CONFIG_HOME": str(root / "config"), "XDG_CACHE_HOME": str(root / "cache"),
               "TMPDIR": str(root / "tmp"), "MAGPIE_WEB_KEY": management_key,
               "MAGPIE_ADDR": "127.0.0.1:" + str(gateway_port), "MAGPIE_BUN": "/usr/bin/false",
               "SSL_CERT_FILE": str(trusted_cert), "SSL_CERT_DIR": str(root / "empty-certs"),
               "HTTP_PROXY": "http://127.0.0.1:9", "HTTPS_PROXY": "http://127.0.0.1:9",
               "NO_PROXY": ""}
        process = subprocess.Popen([str(executable), "serve", "--web=127.0.0.1:" + str(admin_port)],
                                   env=env, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        credential_leaked = threading.Event()

        def drain():
            for line in iter(process.stdout.readline, b""):
                if management_key.encode() in line or vendor_key.encode() in line:
                    credential_leaked.set()

        drain_thread = threading.Thread(target=drain, daemon=True)
        drain_thread.start()
        base = "http://127.0.0.1:" + str(admin_port)
        cookie = "magpie_web_" + str(admin_port) + "=" + management_key

        def discover(body, status=200, code=None):
            actual, raw, headers = request(base, "/api/mobile/providers/models", cookie, body)
            check(actual == status, "unexpected discovery HTTP status: " + str(actual))
            for secret in (management_key, vendor_key):
                check(secret.encode() not in raw, "discovery response leaked a fixture credential")
            if code is not None:
                check(headers.get("X-Magpie-Mobile-Error-Code") == code, "unexpected fixed TLS error classification")
                check(HOST.encode() not in raw and trusted_pin.encode() not in raw and
                      private_pin.encode() not in raw, "TLS error echoed upstream identity")
            if status == 200:
                check(json.loads(raw)["models"][0]["id"] == "tls-fixture-model", "TLS catalog mismatch")
            return raw

        def passed(name):
            evidence["checks"].append(name)

        try:
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                check(process.poll() is None, "core exited before authenticated startup")
                try:
                    if request(base, "/api/mobile/capabilities", cookie)[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(.1)
            else:
                raise AssertionError("authenticated core never became ready")

            discover({"chat": private.base, "key": vendor_key, "serverPin": private_pin})
            check(private.calls == [("/v1/models", True, True)], "pinned self-signed GET/auth/cookie mismatch")
            passed("self-signed correct leaf pin: exactly one authenticated GET, no management cookie")
            private.calls.clear()
            discover({"chat": private.base, "key": vendor_key, "serverPin": trusted_pin}, 502,
                     "provider_models_tls_pin")
            check(not private.calls, "wrong pin reached HTTP handler")
            passed("self-signed wrong pin: zero HTTP and zero Authorization")
            discover({"chat": trusted.base, "key": vendor_key, "serverPin": ""})
            check(trusted.calls == [("/v1/models", True, True)], "empty pin did not retain system TLS")
            trusted.calls.clear()
            discover({"chat": trusted.base, "key": vendor_key, "serverPin": private_pin}, 502,
                     "provider_models_tls_pin")
            check(not trusted.calls, "CA-trusted wrong pin reached HTTP handler")
            passed("trusted certificate + wrong pin: zero HTTP; explicit empty pin retains normal TLS")
            discover({"chat": private.base, "key": vendor_key, "serverPin": ""}, 502,
                     "provider_models_tls_certificate")
            check(not private.calls, "empty pin trusted an untrusted self-signed certificate")
            passed("empty pin + untrusted self-signed: normal certificate refusal, zero HTTP")

            # Persist only through the real authenticated provider API. Read-only
            # discovery must not change this exact file, even when pin is overridden.
            status, raw, _ = request(base, "/api/mobile/providers/create", cookie,
                                    {"new": True, "id": "tls-pinned", "name": "TLS fixture",
                                     "chat": private.base, "key": vendor_key, "models": ["tls-fixture-model"],
                                     "noModels": False, "serverPin": private_pin})
            check(status == 200, "pinned provider creation failed")
            revision = json.loads(raw)["revision"]
            files = list((root / "config").rglob("providers.json"))
            check(len(files) == 1, "canonical provider file missing")
            before = files[0].read_bytes()
            stored = {"chat": private.base, "id": "tls-pinned", "revision": revision}
            discover(stored)
            check(private.calls == [("/v1/models", True, True)], "stored key+pin were not inherited together")
            private.calls.clear()
            discover({**stored, "serverPin": ""}, 502, "provider_models_tls_certificate")
            check(not private.calls, "explicit pin clearing inherited the stored pin")
            discover({**stored, "serverPin": trusted_pin}, 502, "provider_models_tls_pin")
            check(not private.calls, "replacement pin was ignored")
            discover({**stored, "revision": "0" * 64}, 409)
            check(not private.calls, "stale revision reached vendor")
            passed("same-revision stored credentials; explicit clear/replacement; stale revision has zero HTTP")

            changed = {**stored, "chat": trusted.base}
            discover(changed, 400)
            check(not trusted.calls, "changed origin reused stored key")
            discover({**changed, "key": vendor_key})
            check(trusted.calls == [("/v1/models", True, True)], "changed origin incorrectly inherited old pin")
            trusted.calls.clear()
            passed("changed origin requires explicit key and never inherits previous origin pin")

            private.fallback = True
            discover(stored)
            check(private.calls == [("/v1/models", True, True), ("/models", True, True)],
                  "same-origin 404 fallback changed TLS/auth constraints")
            private.calls.clear()
            private.fallback = False
            private.redirect = trusted.base + "/models"
            discover(stored, 502, "provider_models_redirect_rejected")
            check(private.calls == [("/v1/models", True, True)] and not trusted.calls,
                  "redirect target received HTTP or Authorization")
            private.calls.clear()
            passed("404 fallback remains pinned; redirect target receives zero HTTP/Authorization")
            discover({"chat": private.base, "key": vendor_key, "serverPin": "not-a-pin"}, 400)
            discover({"chat": "http://127.0.0.1:" + str(private.server.server_port),
                      "key": vendor_key, "serverPin": private_pin}, 400)
            check(not private.calls, "invalid pin/HTTP combination reached vendor")
            check(files[0].read_bytes() == before, "discovery modified canonical provider configuration")
            passed("invalid pin/HTTP+pin rejected before HTTP; all discovery leaves canonical file unchanged")
            process.terminate()
            process.wait(timeout=5)
            drain_thread.join(timeout=2)
            check(not drain_thread.is_alive(), "core output drain did not finish")
            check(not credential_leaked.is_set(), "core output leaked a fixture credential")
            evidence["passed"] = True
            Path(args.evidence).parent.mkdir(parents=True, exist_ok=True)
            Path(args.evidence).write_text(json.dumps(evidence, indent=2) + "\n")
            print("PASS: real core management RPC + TLS pin: " + str(len(evidence["checks"])) +
                  " safety checks; zero credentials in evidence")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            trusted.close()
            private.close()


if __name__ == "__main__":
    main()
