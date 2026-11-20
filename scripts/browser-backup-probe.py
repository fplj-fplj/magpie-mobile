#!/usr/bin/env python3
"""CI-only, no-install Android external browser environment probe.

Copy to scripts/browser-backup-probe.py next to the standalone workflow.
This does not exercise Magpie, authenticated URLs, backup downloads, or imports.
Only Python's standard library and the runner-provided Android SDK are used.
No accounts, cookies, request headers, environment dumps, or logcat are collected.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


DEVICE_PORT = 8765  # Not in Chromium's general restricted port list; do not use :9.
PAGE_PATH = "/browser-probe/"
PAGE_MARKER = "MAGPIE_BROWSER_PROBE_READY"
PROBE_URL = f"http://127.0.0.1:{DEVICE_PORT}{PAGE_PATH}"
PAGE_BYTES = (
    "<!doctype html><html lang=en><meta charset=utf-8>"
    "<meta name=viewport content='width=device-width,initial-scale=1'>"
    "<title>Magpie external browser probe</title>"
    f"<h1>{PAGE_MARKER}</h1>"
    "<p>This local page contains no credentials or account data.</p></html>"
).encode("utf-8")
PACKAGE_RE = r"(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*|android"
COMPONENT_RE = re.compile(rf"(?<![\w.])({PACKAGE_RE})/(\.?[A-Za-z_$][\w.$]*)")
FORBIDDEN_ACTIVITIES = ("resolveractivity", "chooseractivity", "intentresolver", "customtab", "webview")
DOCUMENTS_PACKAGES = {"com.android.documentsui", "com.google.android.documentsui"}
CHROME_PACKAGE = "com.android.chrome"
CHROME_FIRST_RUN = CHROME_PACKAGE + "/org.chromium.chrome.browser.firstrun.FirstRunActivity"
WELCOME_TITLE_ID = CHROME_PACKAGE + ":id/title"
SKIP_ACCOUNT_ID = CHROME_PACKAGE + ":id/signin_fre_dismiss_button"
SKIP_ACCOUNT_TEXT = "Use without an account"
CHROME_TABBED = CHROME_PACKAGE + "/org.chromium.chrome.browser.ChromeTabbedActivity"
NOTIFICATIONS_TITLE = "Chrome notifications make things easier"
NOTIFICATIONS_DECLINE_ID = CHROME_PACKAGE + ":id/negative_button"
# Exact labels observed in both CI image artifacts, never an open-ended
# "continue/accept/skip" matcher. Unknown subsequent pages are evidence-only.
SAFE_UI_LABELS = {
    PAGE_MARKER, "Welcome to Chrome", SKIP_ACCOUNT_TEXT, "Add account to device",
    "Sign in to browse easier across devices", "Magpie external browser probe",
    "This local page contains no credentials or account data.", PROBE_URL,
    NOTIFICATIONS_TITLE, "No thanks",
}
SAFE_UI_ATTRIBUTES = {
    "rotation", "index", "resource-id", "class", "package", "checkable", "checked",
    "clickable", "enabled", "focusable", "focused", "scrollable", "long-clickable",
    "password", "selected", "bounds", "display-id", "visible-to-user", "text", "content-desc",
}


def sanitize_hierarchy(root: ET.Element) -> None:
    """Keep geometry/selectors; discard arbitrary text, descriptions, and extras."""
    for node in root.iter():
        for key in list(node.attrib):
            if key not in SAFE_UI_ATTRIBUTES:
                del node.attrib[key]
            elif key in ("text", "content-desc"):
                value = node.attrib[key]
                if value and value not in SAFE_UI_LABELS:
                    node.attrib[key] = "[redacted]"


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def components(text: str) -> list[str]:
    result: list[str] = []
    for match in COMPONENT_RE.finditer(text):
        package, activity = match.groups()
        component = package + "/" + (package + activity if activity.startswith(".") else activity)
        if component not in result:
            result.append(component)
    return result


def component_package(component: str | None) -> str | None:
    return component.split("/", 1)[0] if component else None


def package_names(text: str) -> set[str]:
    result = set()
    for line in text.splitlines():
        if not line.startswith("package:"):
            continue
        token = line.removeprefix("package:").split()[0]
        package = token.rsplit("=", 1)[-1]
        if re.fullmatch(PACKAGE_RE, package):
            result.add(package)
    return result


def first_match(pattern: str, text: str) -> str | None:
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else None


class Probe:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.output = Path(args.output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.path = self.output / "report.json"
        try:
            self.report = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.report = {"schemaVersion": 1, "probeKind": "fresh-emulator-independent-browser"}
        self.report.update(
            status="running", browserAvailable=False, availabilityReason="probe_in_progress",
            defaultActionViewReady=False, independentBrowserForeground=False, filePickerHandlersPresent=False,
            browserPreparationRequested=args.prepare_browser, firstRunPrepared=False,
            firstRunPreparation={"requested": args.prepare_browser,
                                 "status": "pending" if args.prepare_browser else "not_requested",
                                 "allowedActions": ["chrome_welcome_use_without_account", "chrome_notifications_no_thanks"] if args.prepare_browser else [],
                                 "unknownScreenPolicy": "capture_and_fail", "attempts": []},
            collectionStartedAt=now(), serial=args.serial,
            requestedImage={"apiLevel": 35, "arch": "x86_64", "target": args.target, "profile": "pixel_6"},
            scope="Environment discovery only; no backup, download, or import acceptance claim.",
            policy={"noAppInstalled": True, "noAccountOrLoginActions": True,
                    "noAutomatedConsentClicks": not args.prepare_browser,
                    "automatedUIClicksPerformed": 0,
                    "automatedUIClickPolicy": "exact_observed_chrome_no_account_and_notification_decline_only" if args.prepare_browser else "none",
                    "savedHierarchyTextPolicy": "known_static_labels_only",
                    "noCredentialsInURL": True, "noRequestHeadersRecorded": True, "loopbackURL": PROBE_URL},
            errors=[], intentEvidence={}, packages={}, launchAttempts=[],
            scriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        )
        self.adb = shutil.which("adb")
        self.server: http.server.ThreadingHTTPServer | None = None
        self.server_thread: threading.Thread | None = None
        self.reverse_created = False
        self.page_hits: list[dict] = []
        self.hits_lock = threading.Lock()
        self.candidates: set[str] = set()
        self.preparation_blocked = False
        self.flush()

    def flush(self) -> None:
        temporary = self.path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(self.report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(self.path)

    def command(self, args: list[str], timeout: int = 30) -> dict:
        try:
            result = subprocess.run(args, capture_output=True, timeout=timeout, check=False)
            return {"argv": args, "returnCode": result.returncode,
                    "stdout": result.stdout.decode("utf-8", errors="replace"),
                    "stderr": result.stderr.decode("utf-8", errors="replace")}
        except (OSError, subprocess.TimeoutExpired) as error:
            evidence = {"argv": args, "returnCode": None, "stdout": "", "stderr": type(error).__name__}
            self.report["errors"].append({"kind": "command_failed", "argv": args, "error": type(error).__name__})
            return evidence

    def adb_command(self, args: list[str], timeout: int = 30) -> dict:
        if not self.adb:
            raise RuntimeError("adb_not_available")
        return self.command([self.adb, "-s", self.args.serial, *args], timeout)

    def shell(self, args: list[str], timeout: int = 30) -> dict:
        # adb shell transports a shell string. Quote every argument, including */*.
        return self.adb_command(["shell", shlex.join(args)], timeout)

    def inventory(self) -> None:
        state = self.adb_command(["get-state"])
        self.report["adbState"] = state
        if state["returnCode"] != 0 or state["stdout"].strip() != "device":
            raise RuntimeError("expected_emulator_not_connected")
        props = {}
        for name in (
            "ro.build.fingerprint", "ro.build.version.sdk", "ro.build.version.release",
            "ro.build.version.incremental", "ro.product.cpu.abi", "ro.product.cpu.abilist",
            "ro.product.model", "ro.product.manufacturer", "ro.build.type", "ro.build.tags", "ro.kernel.qemu", "ro.boot.qemu",
        ):
            evidence = self.shell(["getprop", name])
            props[name] = evidence["stdout"].strip() if evidence["returnCode"] == 0 else None
        self.report["deviceProperties"] = props
        self.report["imageMatchesRequestedAPIAndABI"] = (
            props.get("ro.build.version.sdk") == "35" and props.get("ro.product.cpu.abi") == "x86_64"
            and "1" in (props.get("ro.kernel.qemu"), props.get("ro.boot.qemu"))
        )
        avd_name = self.adb_command(["emu", "avd", "name"])
        avd = {"nameCommand": avd_name, "expectedName": "browser-probe-" + self.args.target}
        actual_avd_name = avd_name["stdout"].splitlines()[0].strip() if avd_name["stdout"].splitlines() else None
        avd["observedName"] = actual_avd_name
        # Only read the known AVD's configuration, never paths supplied by an app.
        avd_home = Path(os.environ.get("ANDROID_AVD_HOME") or
                        str(Path(os.environ.get("ANDROID_USER_HOME") or str(Path.home() / ".android")) / "avd"))
        config = avd_home / (avd["expectedName"] + ".avd") / "config.ini"
        if config.is_file():
            allowed = {"AvdId", "avd.ini.displayname", "hw.device.name", "hw.cpu.arch", "image.sysdir.1", "tag.id", "tag.display"}
            avd["configuration"] = {key.strip(): value.strip()
                                    for line in config.read_text(encoding="utf-8").splitlines() if "=" in line
                                    for key, value in [line.split("=", 1)] if key.strip() in allowed}
            avd["configurationPath"] = str(config)
        self.report["avd"] = avd
        self.report["adbVersion"] = self.command([self.adb, "version"])
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        image = {"sourcePropertiesFound": False}
        if sdk:
            source = Path(sdk) / "system-images" / "android-35" / self.args.target / "x86_64" / "source.properties"
            image["sourcePropertiesPath"] = str(source)
            if source.is_file():
                image["sourcePropertiesFound"] = True
                source_bytes = source.read_bytes()
                source_text = source_bytes.decode("utf-8", errors="replace")
                image["sourcePropertiesSha256"] = hashlib.sha256(source_bytes).hexdigest()
                image["properties"] = dict(
                    line.split("=", 1) for line in source_text.splitlines()
                    if "=" in line and not line.lstrip().startswith("#")
                )
                image["properties"] = {key.strip(): value.strip() for key, value in image["properties"].items()}
                (self.output / "system-image-source.properties").write_bytes(source_bytes)
            emulator = Path(sdk) / "emulator" / "emulator"
            if emulator.is_file():
                self.report["emulatorVersion"] = self.command([str(emulator), "-version"])
                self.report["accelerationCheck"] = self.command([str(emulator), "-accel-check"])
        self.report["systemImage"] = image
        kvm = Path("/dev/kvm")
        self.report["kvm"] = {"exists": kvm.exists(), "readable": os.access(kvm, os.R_OK),
                              "writable": os.access(kvm, os.W_OK),
                              "isCharacterDevice": stat.S_ISCHR(kvm.stat().st_mode) if kvm.exists() else False}
        installed = self.shell(["pm", "list", "packages", "--user", "0", "-f", "--show-versioncode"])
        system = self.shell(["pm", "list", "packages", "--user", "0", "-s"])
        self.report["packageListCommands"] = {"installed": installed, "system": system}
        self.installed_packages = package_names(installed["stdout"])
        self.system_packages = package_names(system["stdout"])
        if installed["returnCode"] != 0 or system["returnCode"] != 0:
            raise RuntimeError("package_inventory_failed")
        self.flush()

    def query(self, label: str, intent: list[str], default_only: bool = True) -> set[str]:
        flags = ["--query-flags", "65536"] if default_only else []  # MATCH_DEFAULT_ONLY.
        base = ["--brief", "--user", "0", *flags, *intent]
        query = self.shell(["cmd", "package", "query-activities", *base])
        resolve = self.shell(["cmd", "package", "resolve-activity", *base])
        found = components(query["stdout"]) if query["returnCode"] == 0 else []
        self.report["intentEvidence"][label] = {"query": query, "resolve": resolve,
                                                  "components": found,
                                                  "resolvedComponents": components(resolve["stdout"]) if resolve["returnCode"] == 0 else []}
        return {component.split("/", 1)[0] for component in found}

    def intent_inventory(self) -> None:
        http = self.query("httpView", ["-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", PROBE_URL])
        https = self.query("httpsView", ["-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", "https://example.invalid/browser-probe/"])
        launchers = self.query("launcher", ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"], False)
        app_browsers = self.query("appBrowser", ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.APP_BROWSER"], False)
        get_content = self.query("getContent", ["-a", "android.intent.action.GET_CONTENT", "-c", "android.intent.category.OPENABLE", "-t", "*/*"])
        open_document = self.query("openDocument", ["-a", "android.intent.action.OPEN_DOCUMENT", "-c", "android.intent.category.OPENABLE", "-t", "*/*"])
        # A package name containing "browser" proves nothing. Require a launchable
        # installed application with both HTTP and HTTPS VIEW handlers;
        # actual am start + independent foreground + visible page are still required.
        potential = http & https & launchers & self.installed_packages
        self.candidates = {package for package in potential
                           if package != "android" and "webview" not in package.lower()
                           and "intentresolver" not in package.lower()}
        self.report["standaloneBrowserCandidates"] = sorted(self.candidates)
        self.report["independentBrowserInstalled"] = bool(self.candidates)
        self.report["excludedHTTPPackages"] = sorted(http - self.candidates)
        self.report["appBrowserCategoryPackages"] = sorted(app_browsers)
        documents = (DOCUMENTS_PACKAGES & self.installed_packages) | get_content | open_document
        webviews = {package for package in self.installed_packages if "webview" in package.lower()}
        self.report["documentsUIPackages"] = sorted(DOCUMENTS_PACKAGES & self.installed_packages)
        self.report["webViewPackagesExcludedFromBrowserQualification"] = sorted(webviews)
        for package in sorted(http | https | documents | webviews):
            if not re.fullmatch(PACKAGE_RE, package):
                continue
            dump = self.shell(["dumpsys", "package", package])
            text = dump["stdout"]
            self.report["packages"][package] = {
                "installed": package in self.installed_packages,
                "systemPackage": package in self.system_packages,
                "versionCode": first_match(r"\bversionCode=(\d+)", text),
                "versionName": first_match(r"^\s*versionName=(.*)$", text),
                "codePath": first_match(r"^\s*codePath=(.*)$", text),
                "flags": re.findall(r"^\s*(?:pkgFlags|flags|privateFlags|privatePkgFlags)=\[([^\]]*)\]", text, re.MULTILINE),
                "user0State": first_match(r"^\s*(User 0:.*)$", text),
                "metadataCommandSucceeded": dump["returnCode"] == 0,
                "browserCandidate": package in self.candidates,
                "filePickerHandler": package in (get_content | open_document),
            }
        self.flush()

    def foreground(self) -> dict:
        activities = self.shell(["dumpsys", "activity", "activities"])
        # API 35's `dumpsys window windows` omitted the global focus fields in
        # both real image reports. Read the full dump, retaining only focus lines.
        windows = self.shell(["dumpsys", "window"])
        activity_lines = [line.strip() for line in activities["stdout"].splitlines()
                          if any(key in line for key in ("topResumedActivity=", "mResumedActivity:", "ResumedActivity:"))]
        window_lines = [line.strip() for line in windows["stdout"].splitlines()
                        if any(key in line for key in ("mCurrentFocus=", "mFocusedApp="))]
        resumed = None
        for key in ("topResumedActivity=", "mResumedActivity:", "ResumedActivity:"):
            for line in activity_lines:
                found = components(line) if key in line else []
                if found:
                    resumed = found[0]
                    break
            if resumed:
                break
        focused = None
        for line in window_lines:
            if "mCurrentFocus=" in line:
                found = components(line)
                focused = found[0] if found else None
                break
        return {"at": now(), "resumedComponent": resumed, "focusedComponent": focused,
                "activityLines": activity_lines, "windowLines": window_lines,
                "commandsSucceeded": activities["returnCode"] == 0 and windows["returnCode"] == 0}

    def independent_foreground(self, evidence: dict) -> bool:
        resumed, focused = evidence.get("resumedComponent"), evidence.get("focusedComponent")
        package = component_package(resumed)
        return bool(
            evidence["commandsSucceeded"] and package in self.candidates
            and component_package(focused) == package
            and all(term not in (resumed + " " + focused).lower() for term in FORBIDDEN_ACTIVITIES)
        )

    def start_server(self) -> None:
        probe = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args) -> None:
                pass  # Never emit request headers, URL query strings, or account data.

            def do_GET(self) -> None:
                if urlsplit(self.path).path == PAGE_PATH and not urlsplit(self.path).query:
                    with probe.hits_lock:
                        probe.page_hits.append({"at": now(), "method": "GET", "path": PAGE_PATH})
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", str(len(PAGE_BYTES)))
                    self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                    self.end_headers()
                    self.wfile.write(PAGE_BYTES)
                else:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server_thread = threading.Thread(target=self.server.serve_forever, name="browser-probe-http", daemon=True)
        self.server_thread.start()
        host_port = self.server.server_port
        reverse = self.adb_command(["reverse", f"tcp:{DEVICE_PORT}", f"tcp:{host_port}"])
        self.report["loopback"] = {"deviceURL": PROBE_URL, "hostBindAddress": "127.0.0.1",
                                   "hostPort": host_port, "reverse": reverse,
                                   "pageSha256": hashlib.sha256(PAGE_BYTES).hexdigest()}
        if reverse["returnCode"] != 0:
            raise RuntimeError("adb_reverse_failed")
        self.reverse_created = True
        self.flush()

    def capture(self, prefix: str) -> dict:
        evidence = {"foregroundBefore": self.foreground(), "screenshot": None,
                    "hierarchy": None, "visiblePageMarker": False, "captureErrors": [],
                    "hierarchySanitized": True}
        screen_path = self.output / f"{prefix}.png"
        try:
            result = subprocess.run([self.adb, "-s", self.args.serial, "exec-out", "screencap", "-p"],
                                    capture_output=True, timeout=30, check=False)
            if (result.returncode == 0 and result.stdout.startswith(b"\x89PNG\r\n\x1a\n")
                    and len(result.stdout) >= 24 and result.stdout[12:16] == b"IHDR"):
                screen_path.write_bytes(result.stdout)
                evidence["screenshot"] = screen_path.name
                evidence["screenshotWidth"] = int.from_bytes(result.stdout[16:20], "big")
                evidence["screenshotHeight"] = int.from_bytes(result.stdout[20:24], "big")
            else:
                evidence["captureErrors"].append("screenshot_not_valid_png")
        except (OSError, subprocess.TimeoutExpired) as error:
            evidence["captureErrors"].append("screenshot_" + type(error).__name__)
        remote = f"/sdcard/magpie-browser-probe-{prefix}.xml"
        dump = self.shell(["uiautomator", "dump", "--compressed", remote], timeout=45)
        evidence["hierarchyDump"] = dump
        if dump["returnCode"] == 0:
            content = self.adb_command(["exec-out", "cat", remote])
            if content["returnCode"] == 0:
                try:
                    root = ET.fromstring(content["stdout"])
                    if root.tag != "hierarchy":
                        raise ValueError("not_ui_hierarchy")
                    evidence["visiblePageMarker"] = any(
                        PAGE_MARKER in node.attrib.get("text", "") or PAGE_MARKER in node.attrib.get("content-desc", "")
                        for node in root.iter("node")
                    )
                    sanitize_hierarchy(root)
                    hierarchy_path = self.output / f"{prefix}.xml"
                    hierarchy_path.write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
                    evidence["hierarchy"] = hierarchy_path.name
                except (ET.ParseError, ValueError):
                    evidence["captureErrors"].append("hierarchy_not_valid_xml")
            else:
                evidence["captureErrors"].append("hierarchy_read_failed")
        else:
            evidence["captureErrors"].append("hierarchy_dump_failed")
        self.shell(["rm", "-f", remote])  # Only the single exact path created by this attempt.
        evidence["foregroundAfter"] = self.foreground()
        return evidence

    def page_ready(self, evidence: dict, hit_start: int) -> bool:
        with self.hits_lock:
            page_requested = len(self.page_hits) > hit_start
        return bool(
            page_requested and evidence["visiblePageMarker"] and evidence["screenshot"]
            and evidence["hierarchy"] and not evidence["captureErrors"]
            and self.independent_foreground(evidence["foregroundBefore"])
            and self.independent_foreground(evidence["foregroundAfter"])
            and all("firstrun" not in evidence[key]["resumedComponent"].lower()
                    for key in ("foregroundBefore", "foregroundAfter"))
            and component_package(evidence["foregroundBefore"]["resumedComponent"])
            == component_package(evidence["foregroundAfter"]["resumedComponent"])
        )

    def wait_for_page(self, hit_start: int) -> None:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            with self.hits_lock:
                hit_seen = len(self.page_hits) > hit_start
            if hit_seen:
                time.sleep(2)
                break
            time.sleep(1)

    def chrome_first_run_foreground(self, foreground: dict) -> bool:
        return (self.independent_foreground(foreground)
                and foreground["resumedComponent"] == CHROME_FIRST_RUN
                and component_package(foreground["focusedComponent"]) == CHROME_PACKAGE)

    def prepare_browser(self, prefix: str, before: dict, hit_start: int, launch_ok: bool) -> tuple[dict, dict]:
        """Only observed no-account and notification-decline actions; unknown UI fails."""
        preparation = {"status": "inspecting", "beforeEvidence": before, "actions": []}
        self.report["firstRunPreparation"]["attempts"].append(preparation)

        def blocked(reason: str, evidence: dict) -> tuple[dict, dict]:
            preparation.update(status="blocked", reason=reason, afterEvidence=evidence)
            self.report["firstRunPreparation"].update(status="blocked", reason=reason)
            self.preparation_blocked = True
            self.flush()
            return evidence, preparation

        if not launch_ok or not self.reverse_created or self.report["loopback"]["deviceURL"] != PROBE_URL:
            return blocked("safe_loopback_action_view_not_confirmed", before)
        if self.page_ready(before, hit_start):
            preparation.update(status="not_needed", reason="safe_page_already_ready", afterEvidence=before)
            self.report["firstRunPreparation"].update(status="not_needed", reason="safe_page_already_ready")
            self.flush()
            return before, preparation
        if before["captureErrors"] or not before["hierarchy"]:
            return blocked("preparation_evidence_incomplete", before)
        if not all(self.chrome_first_run_foreground(before[key]) for key in ("foregroundBefore", "foregroundAfter")):
            return blocked("unknown_screen_or_chrome_first_run_not_focused", before)
        root = ET.parse(self.output / before["hierarchy"]).getroot()
        titles = [node for node in root.iter("node")
                  if node.attrib.get("package") == CHROME_PACKAGE
                  and node.attrib.get("resource-id") == WELCOME_TITLE_ID
                  and node.attrib.get("text") == "Welcome to Chrome"]
        buttons = [node for node in root.iter("node")
                   if node.attrib.get("package") == CHROME_PACKAGE
                   and node.attrib.get("resource-id") == SKIP_ACCOUNT_ID
                   and node.attrib.get("text") == SKIP_ACCOUNT_TEXT
                   and node.attrib.get("class") == "android.widget.Button"
                   and node.attrib.get("clickable") == "true" and node.attrib.get("enabled") == "true"
                   and node.attrib.get("visible-to-user") != "false"]
        if len(titles) != 1 or len(buttons) != 1:
            return blocked("unknown_first_run_screen_or_ambiguous_no_account_button", before)
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", buttons[0].attrib.get("bounds", ""))
        if not bounds:
            return blocked("no_account_button_bounds_invalid", before)
        left, top, right, bottom = map(int, bounds.groups())
        if not (0 <= left < right <= before.get("screenshotWidth", 0)
                and 0 <= top < bottom <= before.get("screenshotHeight", 0)):
            return blocked("no_account_button_outside_current_screenshot", before)
        if self.report["policy"]["automatedUIClicksPerformed"] != 0:
            return blocked("known_preparation_action_already_attempted", before)
        # Recheck actual focus immediately before the one permitted tap. Every
        # coordinate comes from this capture's exact enabled/clickable UI node.
        current = self.foreground()
        if not self.chrome_first_run_foreground(current):
            return blocked("chrome_first_run_focus_changed_before_tap", self.capture(prefix + "-prepare-focus-changed"))
        x, y = (left + right) // 2, (top + bottom) // 2
        action = {"name": "chrome_welcome_use_without_account", "at": now(),
                  "selector": {"package": CHROME_PACKAGE, "resourceId": SKIP_ACCOUNT_ID, "text": SKIP_ACCOUNT_TEXT},
                  "bounds": [left, top, right, bottom], "tap": {"x": x, "y": y},
                  "foregroundImmediatelyBeforeTap": current, "beforeEvidence": before,
                  "status": "about_to_tap"}
        preparation["actions"].append(action)
        self.flush()  # Preserve the decision and before evidence even if adb fails.
        self.report["policy"]["automatedUIClicksPerformed"] += 1
        action["command"] = self.shell(["input", "tap", str(x), str(y)])
        action["status"] = "tap_sent" if action["command"]["returnCode"] == 0 else "tap_failed"
        self.flush()
        self.wait_for_page(hit_start)
        after = self.capture(prefix + "-prepare-01-after")
        action["afterEvidence"] = after
        preparation["afterEvidence"] = after
        if action["command"]["returnCode"] != 0:
            return blocked("no_account_tap_command_failed", after)
        if not self.page_ready(after, hit_start):
            # Both image artifacts from run 44796099677 show this exact native
            # notification promo after the no-account action. Decline it; never
            # match a generic No thanks / Continue button on an unknown page.
            def notification_foreground(foreground: dict) -> bool:
                return (self.independent_foreground(foreground)
                        and foreground["resumedComponent"] == CHROME_TABBED
                        and foreground["focusedComponent"] == CHROME_TABBED)

            if (after["captureErrors"] or not after["hierarchy"]
                    or not all(notification_foreground(after[key])
                               for key in ("foregroundBefore", "foregroundAfter"))):
                return blocked("unknown_screen_after_known_no_account_action", after)
            root = ET.parse(self.output / after["hierarchy"]).getroot()
            titles = [node for node in root.iter("node")
                      if node.attrib.get("package") == CHROME_PACKAGE
                      and node.attrib.get("class") == "android.widget.TextView"
                      and node.attrib.get("text") == NOTIFICATIONS_TITLE]
            buttons = [node for node in root.iter("node")
                       if node.attrib.get("package") == CHROME_PACKAGE
                       and node.attrib.get("resource-id") == NOTIFICATIONS_DECLINE_ID
                       and node.attrib.get("text") == "No thanks"
                       and node.attrib.get("class") == "android.widget.Button"
                       and node.attrib.get("clickable") == "true" and node.attrib.get("enabled") == "true"
                       and node.attrib.get("visible-to-user") != "false"]
            if len(titles) != 1 or len(buttons) != 1:
                return blocked("unknown_notification_screen_or_ambiguous_decline_button", after)
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", buttons[0].attrib.get("bounds", ""))
            if not bounds:
                return blocked("notification_decline_bounds_invalid", after)
            left, top, right, bottom = map(int, bounds.groups())
            if not (0 <= left < right <= after.get("screenshotWidth", 0)
                    and 0 <= top < bottom <= after.get("screenshotHeight", 0)):
                return blocked("notification_decline_outside_current_screenshot", after)
            current = self.foreground()
            if (not notification_foreground(current)
                    or self.report["policy"]["automatedUIClicksPerformed"] != 1):
                return blocked("notification_decline_focus_or_action_count_changed", self.capture(prefix + "-prepare-02-focus-changed"))
            x, y = (left + right) // 2, (top + bottom) // 2
            decline = {"name": "chrome_notifications_no_thanks", "at": now(),
                       "selector": {"package": CHROME_PACKAGE, "resourceId": NOTIFICATIONS_DECLINE_ID,
                                    "text": "No thanks", "title": NOTIFICATIONS_TITLE},
                       "bounds": [left, top, right, bottom], "tap": {"x": x, "y": y},
                       "foregroundImmediatelyBeforeTap": current, "beforeEvidence": after,
                       "status": "about_to_tap"}
            preparation["actions"].append(decline)
            self.flush()
            self.report["policy"]["automatedUIClicksPerformed"] += 1
            decline["command"] = self.shell(["input", "tap", str(x), str(y)])
            decline["status"] = "tap_sent" if decline["command"]["returnCode"] == 0 else "tap_failed"
            self.flush()
            self.wait_for_page(hit_start)
            after = self.capture(prefix + "-prepare-02-after")
            decline["afterEvidence"] = after
            preparation["afterEvidence"] = after
            if decline["command"]["returnCode"] != 0:
                return blocked("notification_decline_tap_command_failed", after)
        if not (self.page_ready(after, hit_start)
                and all(component_package(after[key]["resumedComponent"]) == CHROME_PACKAGE
                        for key in ("foregroundBefore", "foregroundAfter"))):
            # No unobserved privacy/safety/consent button is guessed. The next
            # CI artifact must identify any new page before extending this list.
            return blocked("unknown_screen_after_known_no_account_action", after)
        preparation.update(status="prepared_page_confirmed", reason="safe_chrome_page_after_no_account_action")
        self.report["firstRunPreparation"].update(status="prepared_page_confirmed")
        self.flush()
        return after, preparation

    def launch(self, package: str | None, index: int) -> bool:
        prefix = f"attempt-{index:02d}"
        attempt = {"kind": "implicit_action_view" if package is None else "package_scoped_action_view",
                   "requestedPackage": package, "startedAt": now()}
        self.shell(["input", "keyevent", "KEYCODE_HOME"])
        with self.hits_lock:
            hit_start = len(self.page_hits)
        intent = ["am", "start", "-W", "--user", "0", "-a", "android.intent.action.VIEW",
                  "-c", "android.intent.category.BROWSABLE", "-d", PROBE_URL]
        if package:
            intent += ["-p", package]
        attempt["launch"] = self.shell(intent, timeout=45)
        self.wait_for_page(hit_start)
        evidence = self.capture(prefix + "-prepare-01-before" if self.args.prepare_browser else prefix)
        preparation = None
        if self.args.prepare_browser:
            evidence, preparation = self.prepare_browser(prefix, evidence, hit_start, attempt["launch"]["returnCode"] == 0)
            attempt["firstRunPreparation"] = preparation
        with self.hits_lock:
            attempt_hits = list(self.page_hits[hit_start:])
        attempt.update(evidence)
        attempt["pageRequestsDuringAttempt"] = attempt_hits
        attempt["independentBrowserForeground"] = (
            self.independent_foreground(evidence["foregroundBefore"])
            and self.independent_foreground(evidence["foregroundAfter"])
            and component_package(evidence["foregroundBefore"]["resumedComponent"])
            == component_package(evidence["foregroundAfter"]["resumedComponent"])
        )
        actual_package = component_package(evidence["foregroundAfter"]["resumedComponent"])
        attempt["requestedPackageReachedForeground"] = package is None or actual_package == package
        attempt["browserReady"] = bool(
            attempt["launch"]["returnCode"] == 0
            and attempt["independentBrowserForeground"] and attempt["requestedPackageReachedForeground"]
            and self.page_ready(evidence, hit_start)
        )
        if preparation and preparation["actions"] and attempt["browserReady"]:
            self.report["firstRunPrepared"] = True
            self.report["firstRunPreparation"].update(status="completed")
        attempt["finishedAt"] = now()
        self.report["launchAttempts"].append(attempt)
        self.flush()
        return attempt["browserReady"]

    def collect(self) -> None:
        self.inventory()
        self.intent_inventory()
        self.report["screenPreparation"] = {
            "wake": self.shell(["input", "keyevent", "KEYCODE_WAKEUP"]),
            "dismissKeyguard": self.shell(["wm", "dismiss-keyguard"]),
        }
        self.start_server()
        # Always observe the exact implicit ACTION_VIEW route, even if it lands in
        # ResolverActivity. A scoped follow-up is diagnostic, never a chooser pass.
        implicit_ready = self.launch(None, 0)
        if not implicit_ready and not self.preparation_blocked:
            for index, package in enumerate(sorted(self.candidates), start=1):
                if self.launch(package, index) or self.preparation_blocked:
                    break
        attempts = self.report["launchAttempts"]
        any_ready = any(attempt["browserReady"] for attempt in attempts)
        any_foreground = any(attempt["independentBrowserForeground"] for attempt in attempts)
        self.report["independentBrowserForeground"] = any_foreground
        self.report["defaultActionViewReady"] = implicit_ready
        self.report["filePickerHandlersPresent"] = all(
            bool(self.report["intentEvidence"][label]["components"])
            for label in ("getContent", "openDocument")
        )
        http_evidence = self.report["intentEvidence"]["httpView"]
        critical_queries_ok = all(
            self.report["intentEvidence"][label]["query"]["returnCode"] == 0
            for label in ("httpView", "httpsView", "launcher")
        )
        image_ok = (self.report["imageMatchesRequestedAPIAndABI"]
                    and self.report["avd"]["observedName"] == self.report["avd"]["expectedName"])
        self.report["browserAvailable"] = bool(any_ready and critical_queries_ok and image_ok)
        if not image_ok:
            reason = "unexpected_emulator_api_abi_or_avd"
        elif not critical_queries_ok:
            reason = "browser_handler_inventory_failed"
        elif any_ready:
            reason = "independent_browser_ready" if implicit_ready else "browser_ready_only_with_package_scoped_view"
        elif not http_evidence["components"]:
            reason = "no_http_action_view_handler"
        elif not self.candidates:
            reason = "no_independent_launchable_http_and_https_browser_candidate"
        elif self.preparation_blocked:
            reason = "explicit_browser_preparation_blocked_unknown_or_unready_screen"
        elif any_foreground:
            reason = "browser_present_but_local_page_not_confirmed_first_run_or_other_blocker"
        else:
            reason = "no_independent_browser_reached_foreground"
        self.report.update(status="completed", availabilityReason=reason)

    def cleanup(self) -> None:
        if self.reverse_created:
            self.report["reverseCleanup"] = self.adb_command(["reverse", "--remove", f"tcp:{DEVICE_PORT}"])
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        if self.server_thread:
            self.server_thread.join(timeout=3)
        with self.hits_lock:
            self.report["safePageRequests"] = list(self.page_hits)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=("google_apis", "google_apis_playstore"))
    parser.add_argument("--output", required=True)
    parser.add_argument("--serial", default="emulator-5554", choices=("emulator-5554",))
    parser.add_argument("--prepare-browser", action="store_true",
                        help="Permit observed Chrome no-account and notification-decline UI actions; unknown pages fail closed.")
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true":
        parser.error("This environment probe is CI-only; do not execute it locally.")
    probe = Probe(args)
    try:
        probe.collect()
    except Exception as error:
        probe.report.update(status="error", browserAvailable=False, availabilityReason="probe_collection_failed")
        probe.report["errors"].append({"kind": type(error).__name__, "message": str(error)[:500]})
    finally:
        try:
            probe.cleanup()
        except Exception as error:
            probe.report["errors"].append({"kind": "cleanup_failed", "error": type(error).__name__})
        probe.report["finishedAt"] = now()
        probe.flush()
    print(json.dumps({key: probe.report.get(key) for key in
                      ("status", "browserAvailable", "availabilityReason", "defaultActionViewReady", "firstRunPrepared")}, sort_keys=True))
    return 0 if probe.report.get("browserAvailable") is True else 1


if __name__ == "__main__":
    sys.exit(main())
