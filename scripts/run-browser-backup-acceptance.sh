#!/usr/bin/env bash
# Runs inside the one emulator action, so browser preparation and acceptance
# share the same fresh AVD. No local execution, broad suite, release, or retry.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "${GITHUB_REF:-}" == refs/heads/browser-backup-acceptance ]]
[[ "${BROWSER_ACCEPTANCE_OUTPUT:?}" == "${RUNNER_TEMP:?}/browser-backup-acceptance" ]]
mkdir -p "$BROWSER_ACCEPTANCE_OUTPUT"

acceptance_stage=preflight
acceptance_test_started=false
acceptance_gradle_exit=NOT_RUN

collect_and_finalize() {
  local original_exit=$?
  trap - EXIT
  set +e
  export ACCEPTANCE_ORIGINAL_EXIT="$original_exit"
  export ACCEPTANCE_STAGE="$acceptance_stage"
  export ACCEPTANCE_TEST_STARTED="$acceptance_test_started"
  export ACCEPTANCE_GRADLE_EXIT="$acceptance_gradle_exit"
  python3 - <<'PY'
import datetime, json, os, re, shutil, subprocess, tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

output = Path(os.environ["BROWSER_ACCEPTANCE_OUTPUT"])
started = os.environ["ACCEPTANCE_TEST_STARTED"] == "true"
result = {
    "schemaVersion": 1, "status": "FAIL", "stage": os.environ["ACCEPTANCE_STAGE"],
    "instrumentationStarted": started, "originalExitCode": int(os.environ["ACCEPTANCE_ORIGINAL_EXIT"]),
    "gradleExitCode": os.environ["ACCEPTANCE_GRADLE_EXIT"], "failures": [],
    "expectedClass": "cc.fplj.magpiemobile.BrowserBackupInstrumentedTest",
    "junit": {"cases": 0, "failures": 0, "errors": 0, "skipped": 0, "unexpectedCases": 0},
    "sanitizedTestEvidenceCollected": False, "testResultPassed": False,
    "finishedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}

class GateError(Exception):
    pass

def require(condition, reason):
    if not condition:
        raise GateError(reason)

def command(args):
    completed = subprocess.run(args, capture_output=True, timeout=30, check=False)
    require(completed.returncode == 0, "EVIDENCE_ADB_COMMAND_FAILED")
    return completed.stdout.decode("utf-8", errors="strict")

safe_labels = {
    "Settings", "设置", "Sync and backup", "SYNC AND BACKUP", "同步与备份", "Export…", "导出…", "Export", "导出",
    "Import…", "导入…", "Import", "导入", "Choose…", "选择…", "Download · magpie.magpie-backup",
    "下载 · magpie.magpie-backup", "Files", "文件", "Browse", "浏览", "Downloads", "下载内容", "下载",
    "Open", "打开", "Close", "关闭", "Show roots", "显示根目录", "显示根目录列表", "Passphrase", "passphrase", "口令",
    "again", "再输入一次", "No file chosen", "未选择文件", "Cancel", "取消", "Download", "Download anyway",
    "仍然下载", "wrong passphrase, or the file was changed", "", "[redacted]",
}
safe_phases = {
    "initializing", "starting_gateway", "creating_A_via_production_RPC", "opening_production_browser_button",
    "opening_official_sync_settings", "exporting_via_official_web_form", "changing_A_to_B_via_same_connection_RPC",
    "choosing_real_download_from_official_import", "wrong_phrase_must_preserve_B", "correct_phrase_must_restore_A", "passed",
}
safe_xml_names = {
    "browser-backup-clean-entry.xml", "browser-backup-after-download.xml", "browser-backup-real-picker.xml",
    "browser-backup-real-file-selected.xml",
    "browser-backup-wrong-phrase-rejected.xml", "browser-backup-restored.xml", "browser-backup-final-sanitized.xml",
}
try:
    if started:
        # Parse status only. Never archive raw AGP XML/HTML, failure messages,
        # full logcat, dumpsys intents, config files, or the encrypted backup.
        xml_files = list(Path("app/build/outputs/androidTest-results").rglob("*.xml"))
        require(bool(xml_files), "JUNIT_REPORT_MISSING")
        for path in xml_files:
            root = ET.fromstring(path.read_bytes())
            for case in root.iter("testcase"):
                result["junit"]["cases"] += 1
                expected = (case.attrib.get("classname") == result["expectedClass"]
                            and case.attrib.get("name") == "officialBrowserBackupRoundTripUsesRealDownloadedFile")
                if not expected:
                    result["junit"]["unexpectedCases"] += 1
                for field, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                    result["junit"][field] += len(case.findall(tag))

        # Pull only the class's archive into non-uploaded staging. Validate all
        # files before any device artifact enters the always-uploaded directory.
        listing = command(["adb", "-s", "emulator-5554", "shell",
                           "find /data/local/tmp/magpie-plugin-runtime -mindepth 1 -maxdepth 1 -type d -name 'browser-backup-*'"])
        directories = list(filter(None, listing.splitlines()))
        require(len(directories) == 1, "EXACTLY_ONE_TEST_EVIDENCE_DIRECTORY_REQUIRED")
        remote = directories[0].strip()
        require(re.fullmatch(r"/data/local/tmp/magpie-plugin-runtime/browser-backup-[0-9]+", remote), "UNSAFE_EVIDENCE_DIRECTORY")
        with tempfile.TemporaryDirectory(prefix="browser-backup-staging-", dir=os.environ["RUNNER_TEMP"]) as temporary:
            command(["adb", "-s", "emulator-5554", "pull", remote, temporary])
            source = Path(temporary) / remote.rsplit("/", 1)[-1]
            require(source.is_dir(), "PULLED_EVIDENCE_DIRECTORY_MISSING")
            record = json.loads((source / "browser-backup-result.json").read_text())
            require(record.get("status") in {"PASS", "FAIL"} and record.get("phase") in safe_phases, "UNSAFE_TEST_RESULT_STATUS")
            require(record.get("failureType") is None or re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,100}", record["failureType"]), "UNSAFE_FAILURE_TYPE")
            require(record.get("failureReason") is None or re.fullmatch(r"[A-Z][A-Z0-9_]{0,100}", record["failureReason"]), "UNSAFE_FAILURE_REASON")
            require(record.get("failureLine") is None or (type(record["failureLine"]) is int and 1 <= record["failureLine"] <= 20_000), "UNSAFE_FAILURE_LINE")
            require(record.get("browserPackage") == "com.android.chrome" and record.get("keyEqualityClaimed") is False, "TEST_RESULT_SCOPE_MISMATCH")
            for field in ("cleanRedirectObserved", "realDownloadObserved", "downloadRetryClicked"):
                require(type(record.get(field)) is bool, "TEST_RESULT_BOOLEAN_MISSING")
            require(type(record.get("privateArchiveChecks")) is int and 0 <= record["privateArchiveChecks"] <= 3, "INVALID_PRIVATE_ARCHIVE_CHECK_COUNT")
            curated = {key: record.get(key) for key in (
                "status", "phase", "failureType", "failureReason", "failureLine", "cleanRedirectObserved", "realDownloadObserved",
                "downloadRetryClicked", "privateArchiveChecks", "browserPackage", "keyEqualityClaimed")}
            ordinal = record.get("webControlOrdinal")
            require(type(ordinal) is int and 0 <= ordinal <= 20, "INVALID_WEB_CONTROL_ORDINAL")
            observation_keys = {"targetBounds", "paneBounds", "offscreen", "paneCanScrollUp",
                                "paneCanScrollDown", "onScreen"}
            def curate_observation(value, action=False):
                keys = observation_keys | ({"operation", "accepted"} if action else set())
                require(type(value) is dict and set(value) == keys, "UNSAFE_WEB_CONTROL_OBSERVATION")
                for field in ("targetBounds", "paneBounds"):
                    bounds = value[field]
                    require(type(bounds) is list and len(bounds) == 4 and
                            all(type(n) is int and -100_000 <= n <= 100_000 for n in bounds), "UNSAFE_WEB_CONTROL_BOUNDS")
                for field in ("offscreen", "paneCanScrollUp", "paneCanScrollDown", "onScreen"):
                    require(type(value[field]) is bool, "UNSAFE_WEB_CONTROL_BOOLEAN")
                if action:
                    require(value["operation"] in {"SHOW", "FOCUS_PAGE", "FOCUS_PASSWORD",
                                                   "PAGE_DOWN", "PANE_DOWN", "PANE_UP", "TAB", "ENTER",
                                                   "CLICK"} and
                            type(value["accepted"]) is bool, "UNSAFE_WEB_CONTROL_ACTION")
                return {key: value[key] for key in sorted(keys)}
            trace = record.get("webControlTrace")
            require(type(trace) is list and len(trace) <= 8, "UNBOUNDED_WEB_CONTROL_TRACE")
            curated["webControlOrdinal"] = ordinal
            curated["webControlTrace"] = [curate_observation(value, action=True) for value in trace]
            observation = record.get("lastWebObservation")
            curated["lastWebObservation"] = None if observation is None else curate_observation(observation)
            download = record.get("download")
            if download is not None:
                require(isinstance(download, dict) and re.fullmatch(r"magpie(?: \([0-9]+\))?\.magpie-backup", download.get("name", "")), "UNSAFE_DOWNLOAD_NAME")
                require(type(download.get("bytes")) is int and download["bytes"] > 0 and re.fullmatch(r"[0-9a-f]{64}", download.get("sha256", "")), "INVALID_DOWNLOAD_METADATA")
                curated["download"] = {key: download[key] for key in ("name", "bytes", "sha256")}
                safe_labels.add(download["name"])
            files = list(source.iterdir())
            require(all(file.is_file() and not file.is_symlink() for file in files), "UNEXPECTED_NESTED_EVIDENCE")
            allowed_names = safe_xml_names | {"browser-backup-result.json", "browser-backup-restored.png"}
            require(all(file.name in allowed_names for file in files), "UNEXPECTED_EVIDENCE_FILE")
            for file in files:
                if file.suffix != ".xml":
                    continue
                data = file.read_text()
                require(not re.search(r"ticket=|https?://|Bearer |Set-Cookie|<!DOCTYPE|<!ENTITY|[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", data, re.I), "UNSAFE_HIERARCHY_CONTENT")
                tree = ET.fromstring(data)
                require(tree.tag == "hierarchy", "INVALID_HIERARCHY_ROOT")
                for node in tree.iter():
                    require(node.tag in {"hierarchy", "node"}, "INVALID_HIERARCHY_ELEMENT")
                    require(not node.text or node.text.strip() in {"", "[redacted]"}, "UNSAFE_HIERARCHY_TEXT")
                    for key in ("text", "content-desc", "hint"):
                        if key in node.attrib:
                            require(node.attrib[key] in safe_labels, "UNRECOGNIZED_UNREDACTED_UI_LABEL")
            final_png = source / "browser-backup-restored.png"
            if final_png.exists():
                require(final_png.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), "INVALID_FINAL_SCREENSHOT")
            destination = output / "test-evidence" / source.name
            destination.mkdir(parents=True, exist_ok=True)
            (destination / "browser-backup-result.json").write_text(json.dumps(curated, indent=2) + "\n")
            for file in files:
                if file.suffix == ".xml" or (file.name == "browser-backup-restored.png" and record["status"] == "PASS"):
                    shutil.copyfile(file, destination / file.name)
            result["sanitizedTestEvidenceCollected"] = True
            result["testResultPassed"] = (record["status"] == "PASS" and record["phase"] == "passed"
                                          and record.get("failureType") is None and record.get("failureReason") is None
                                          and record["cleanRedirectObserved"] and record["realDownloadObserved"]
                                          and record["privateArchiveChecks"] == 3
                                          and download is not None and final_png.is_file())
        require(result["junit"]["cases"] == 1 and result["junit"]["unexpectedCases"] == 0, "EXACT_ISOLATED_TEST_CASE_REQUIRED")
        require(not any(result["junit"][key] for key in ("failures", "errors", "skipped")), "JUNIT_FAILURE_ERROR_OR_SKIP")
        require(result["testResultPassed"], "REAL_BROWSER_ROUND_TRIP_NOT_PASSED")
    else:
        result["failures"].append("INSTRUMENTATION_NOT_STARTED")
except GateError as error:
    result["failures"].append(str(error))  # Only fixed enum-like strings above.
except Exception as error:
    result["failures"].append("EVIDENCE_COLLECTION_" + type(error).__name__)
passed = (started and result["originalExitCode"] == 0 and result["gradleExitCode"] == "0"
          and not result["failures"] and result["sanitizedTestEvidenceCollected"] and result["testResultPassed"])
result["status"] = "PASS" if passed else "FAIL"
(output / "execution.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({"status": result["status"], "stage": result["stage"], "failures": result["failures"]}, sort_keys=True))
raise SystemExit(0 if passed else 1)
PY
  local collector_exit=$?
  if (( original_exit != 0 )); then
    exit "$original_exit"
  fi
  exit "$collector_exit"
}
trap collect_and_finalize EXIT

adb -s emulator-5554 get-state | grep -Fx device
# Disposable-device precondition: do not erase an installed app or public Downloads.
installed_packages="$(adb -s emulator-5554 shell pm list packages --user 0 cc.fplj.magpiemobile)"
[[ -z "$installed_packages" ]]

acceptance_stage=preparing_real_browser
# Use the latest reviewed probe on the acceptance branch, not an embedded copy.
python3 scripts/browser-backup-probe.py --target google_apis \
  --output "$BROWSER_ACCEPTANCE_OUTPUT/browser-preparation" --serial emulator-5554 --prepare-browser
python3 - <<'PY'
import json, os
from pathlib import Path
report = json.loads((Path(os.environ["BROWSER_ACCEPTANCE_OUTPUT"]) / "browser-preparation/report.json").read_text())
required = (report.get("status") == "completed" and report.get("browserAvailable") is True
            and report.get("defaultActionViewReady") is True and report.get("filePickerHandlersPresent") is True
            and report.get("browserPreparationRequested") is True
            and report.get("firstRunPreparation", {}).get("status") in {"completed", "not_needed"})
attempts = report.get("launchAttempts", [])
chrome_default = any(attempt.get("kind") == "implicit_action_view" and attempt.get("browserReady") is True
                     and attempt.get("foregroundAfter", {}).get("resumedComponent", "").startswith("com.android.chrome/")
                     for attempt in attempts)
if not required or not chrome_default:
    raise SystemExit("Prepared implicit Chrome route and both system file-picker handlers are required")
PY

acceptance_stage=running_isolated_instrumentation
# Same compatibility setting as the existing runtime-emulator test job; no
# Chrome prefs, cookies, account state, DevTools, or first-run bypass is written.
adb -s emulator-5554 shell settings put global hidden_api_policy 1
acceptance_test_started=true
set +e
gradle :app:connectedDebugAndroidTest -PmagpieTestAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=cc.fplj.magpiemobile.BrowserBackupInstrumentedTest \
  --no-daemon --stacktrace
acceptance_gradle_exit=$?
set -e
if (( acceptance_gradle_exit != 0 )); then
  exit "$acceptance_gradle_exit"
fi
acceptance_stage=checking_sanitized_acceptance_evidence
# The EXIT collector rejects no-op, zero-case, skipped, unrelated, failed, or
# incomplete runs even when Gradle itself exits zero. No retry or skip path.
