#!/usr/bin/env bash
# This is a CI experiment, not evidence of APK or plugin runtime success.
set -euo pipefail
cd "$(dirname "$0")/.."
report=build-tmp/arm64-emulator-probe
mkdir -p "$report"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK missing}}"
export PATH="$sdk_root/cmdline-tools/latest/bin:$sdk_root/platform-tools:$sdk_root/emulator:$PATH"
export ANDROID_AVD_HOME="${ANDROID_USER_HOME:-$HOME/.android}/avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_bin="$sdk_root/emulator/emulator"
backend_bin="$sdk_root/emulator/qemu/linux-x86_64/qemu-system-aarch64-headless"
emulator_library_path="$sdk_root/emulator/lib64:$sdk_root/emulator/lib64/gles_swiftshader:$sdk_root/emulator/lib64/vulkan:$sdk_root/emulator/lib64/libstdc++${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
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
test -f "$ANDROID_AVD_HOME/magpie-arm64-probe.ini"
cp "$ANDROID_AVD_HOME/magpie-arm64-probe.ini" "$report/avd-location.ini"
python3 - "$ANDROID_AVD_HOME/magpie-arm64-probe.ini" "$report" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
settings = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
# Host metadata only: avoid QEMU's unconditional API>=26 HDA device on a PCI-less ARM machine.
# The installed boot/system/vendor images remain Android API 30; verify the guest's actual API.
settings['target'] = 'android-25'
path.write_text(''.join(key.strip() + '=' + value.strip() + '\n' for key, value in settings.items()))
Path(sys.argv[2], 'avd-host-target-override.ini').write_text(path.read_text())
PY
python3 - "$ANDROID_AVD_HOME/magpie-arm64-probe.avd/config.ini" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
settings = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)
settings.update({'hw.audioInput': 'no', 'hw.audioOutput': 'no',
                 'hw.lcd.width': '480', 'hw.lcd.height': '800', 'hw.lcd.density': '160'})
path.write_text(''.join(key.strip() + '=' + value.strip() + '\n' for key, value in settings.items()))
PY
started=$(date +%s)
test -x "$backend_bin"
LD_LIBRARY_PATH="$emulator_library_path" ldd "$backend_bin" > "$report/backend-dependencies.txt"
LD_LIBRARY_PATH="$emulator_library_path" ANDROID_EMULATOR_LAUNCHER_DIR="$sdk_root/emulator" ANDROID_I_WANT_MY_TCG=yes \
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
  timeout 10s adb -s "$serial" shell getprop ro.build.version.sdk > "$report/guest-api.txt"
fi
python3 - "$report" "$booted" "$started" <<'PY'
import json, pathlib, platform, sys, time
root = pathlib.Path(sys.argv[1])
booted = sys.argv[2] == 'true'
abi = (root / 'guest-abi.txt').read_text().strip() if booted else ''
result = {'hostMachine': platform.machine(), 'bootCompleted': booted,
          'launchMethod': 'official-qemu2-aarch64-backend',
          'hostTargetOverride': 25, 'installedSystemImageAPI': 30,
          'guestAPI': (root / 'guest-api.txt').read_text().strip() if booted else '',
          'guestABI': abi, 'elapsedSeconds': int(time.time()) - int(sys.argv[3]),
          'androidPluginRuntimeVerified': False}
(root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
if not booted or abi != 'arm64-v8a' or int(result['guestAPI']) < 29:
    raise SystemExit('ARM64 emulator boot did not pass; see emulator.log')
PY
