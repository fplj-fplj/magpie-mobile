#!/usr/bin/env bash
# This is a CI experiment, not evidence of APK or plugin runtime success.
set -euo pipefail
cd "$(dirname "$0")/.."
report=build-tmp/arm64-emulator-probe
mkdir -p "$report"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK missing}}"
export PATH="$sdk_root/cmdline-tools/latest/bin:$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
emulator_bin="$sdk_root/emulator/emulator"
backend_bin="$sdk_root/emulator/qemu/linux-x86_64/qemu-system-aarch64-headless"
serial=emulator-5580
emulator_pid=''
cleanup() {
  timeout 10s adb -s "$serial" emu kill >/dev/null 2>&1 || true
  if [[ -n "$emulator_pid" ]]; then kill "$emulator_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
"$emulator_bin" -version > "$report/emulator-version.txt" 2>&1
printf 'no\n' | avdmanager create avd --force --name magpie-arm64-probe \
  --package 'system-images;android-30;default;arm64-v8a' --device pixel_5
started=$(date +%s)
test -x "$backend_bin"
ANDROID_EMULATOR_LAUNCHER_DIR="$sdk_root/emulator" ANDROID_I_WANT_MY_TCG=yes \
  "$backend_bin" -avd magpie-arm64-probe -port 5580 \
  -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -accel off -memory 2048 -cores 2 > "$report/emulator.log" 2>&1 &
emulator_pid=$!
booted=false
for ((attempt=0; attempt<80; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then break; fi
  boot=$(timeout 5s adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then booted=true; break; fi
  sleep 10
done
if [[ "$booted" == true ]]; then
  timeout 10s adb -s "$serial" shell getprop ro.product.cpu.abi > "$report/guest-abi.txt"
  timeout 10s adb -s "$serial" shell uname -m > "$report/guest-machine.txt"
fi
python3 - "$report" "$booted" "$started" <<'PY'
import json, pathlib, platform, sys, time
root = pathlib.Path(sys.argv[1])
booted = sys.argv[2] == 'true'
abi = (root / 'guest-abi.txt').read_text().strip() if booted else ''
result = {'hostMachine': platform.machine(), 'bootCompleted': booted,
          'launchMethod': 'official-qemu2-aarch64-backend',
          'guestABI': abi, 'elapsedSeconds': int(time.time()) - int(sys.argv[3]),
          'androidPluginRuntimeVerified': False}
(root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
if not booted or abi != 'arm64-v8a':
    raise SystemExit('ARM64 emulator boot did not pass; see emulator.log')
PY
