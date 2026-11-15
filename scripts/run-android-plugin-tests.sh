#!/usr/bin/env bash
# Invoked inside the emulator action, while adb and the device are still alive.
set -euo pipefail
cd "$(dirname "$0")/.."

collect_runtime_evidence() {
  mkdir -p build-tmp/android-plugin-runtime
  timeout 20s adb pull /sdcard/Android/data/cc.fplj.magpiemobile/files/plugin-runtime \
    build-tmp/android-plugin-runtime || true
  timeout 20s adb logcat -d -s AndroidRuntime:E > build-tmp/android-plugin-runtime/android-errors.txt || true
}
trap collect_runtime_evidence EXIT
adb shell settings put global hidden_api_policy 1
gradle :app:connectedDebugAndroidTest -PmagpieTestAbi=x86_64 --no-daemon --stacktrace
