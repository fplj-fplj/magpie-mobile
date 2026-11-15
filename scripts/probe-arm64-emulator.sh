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
  mkdir -p build-tmp/android-plugin-runtime
  timeout 20s adb -s "$serial" pull /data/local/tmp/magpie-plugin-runtime build-tmp/android-plugin-runtime >/dev/null 2>&1 || true
  timeout 20s adb -s "$serial" logcat -d > "$report/guest-logcat.txt" 2>/dev/null || true
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
# Keep the real image API; override QEMU's host-selected ranchu machine with ARM virt below.
# Android's virt machine provides the PCI bus needed by modern guest devices.
settings['target'] = 'android-30'
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
  -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -show-kernel -verbose \
  -feature -VirtioInput,-VirtioWifi,-VirtioVsockPipe,-VirtioSndCard \
  -accel off -memory 2048 -cores 2 -qemu -machine type=virt > "$report/emulator.log" 2>&1 &
emulator_pid=$!
booted=false
for ((attempt=0; attempt<80; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then break; fi
  boot=$(timeout 5s adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  printf 'boot attempt %s: %s\n' "$attempt" "${boot:-not-connected}" >> "$report/boot-progress.txt"
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
          'hostTargetOverride': 30, 'qemuMachineOverride': 'virt', 'installedSystemImageAPI': 30,
          'guestAPI': (root / 'guest-api.txt').read_text().strip() if booted else '',
          'guestABI': abi, 'elapsedSeconds': int(time.time()) - int(sys.argv[3]),
          'androidPluginRuntimeVerified': False}
(root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
if not booted or abi != 'arm64-v8a' or int(result['guestAPI']) < 29:
    raise SystemExit('ARM64 emulator boot did not pass; see emulator.log')
PY
if [[ "${MAGPIE_ARM64_RUN_PLUGINS:-false}" == true ]]; then
  export ANDROID_SERIAL="$serial"
  adb -s "$serial" shell settings put global window_animation_scale 0
  adb -s "$serial" shell settings put global transition_animation_scale 0
  adb -s "$serial" shell settings put global animator_duration_scale 0
  adb -s "$serial" shell settings put global hidden_api_policy 1
  timeout 30m gradle :app:connectedDebugAndroidTest -PmagpieTestAbi=arm64-v8a --no-daemon --stacktrace
  python3 - "$report" <<'PY'
import json, pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
suites = [ET.parse(p).getroot() for p in pathlib.Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml')]
assert suites and sum(int(s.get('tests', '0')) for s in suites) > 0
assert all(all(int(s.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')) for s in suites)
result = json.loads((root / 'result.json').read_text())
result['androidPluginRuntimeVerified'] = True
result['payloadSource'] = 'fixed-signed production APK 3f4f78da'
(root / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
PY
fi
