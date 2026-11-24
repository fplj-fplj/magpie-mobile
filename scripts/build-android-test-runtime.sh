#!/usr/bin/env bash
# Runtime-emulator CI: build the test-only x86_64 Android core from the patched
# pinned upstream and fetch the verified x86_64 Bun Android baseline, both into
# app/src/main/jniLibs/x86_64. Production releases stay ARM64-only.
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM="${1:?usage: build-android-test-runtime.sh <patched-upstream-root>}"
core_version="$(python3 scripts/read-upstream-lock.py --get coreVersion)"
DEST=app/src/main/jniLibs/x86_64
mkdir -p "$DEST" build-tmp

ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
test -n "$ndk" || { echo "NDK not found: set ANDROID_NDK_LATEST_HOME or ANDROID_NDK_HOME" >&2; exit 2; }
CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android29-clang"
test -x "$CC" || { echo "missing NDK compiler: $CC" >&2; exit 2; }

if [[ "${MAGPIE_TEST_CORE_CACHE_HIT:-false}" != true ]]; then
  (
    cd "$UPSTREAM"
    export CGO_ENABLED=1 GOOS=android GOARCH=amd64 CC="$CC"
    go build -trimpath -buildmode=pie -tags nogui,androidhost \
      -ldflags "-s -w -X main.version=${core_version} -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
      -o "$OLDPWD/build-tmp/magpie-mobile-android-x86_64" .
  )
  sha256sum build-tmp/magpie-mobile-android-x86_64 > build-tmp/test-core-x86_64.sha256
  go version -m build-tmp/magpie-mobile-android-x86_64 > build-tmp/test-core-x86_64-build-info.txt
fi
sha256sum -c build-tmp/test-core-x86_64.sha256
cp build-tmp/magpie-mobile-android-x86_64 "$DEST/libmagpie_exec.so"

MAGPIE_X86_BUN_ZIP_SHA256=fe36d8d4795e0eadc22fb6696d44d168491c2e5b9b7cbb12b8c96b0c0c40a4f9
BUN_X86_URL=https://github.com/oven-sh/bun/releases/download/bun-v1.4.2/bun-linux-x64-android-baseline.zip

fetch() { # fetch <out> <url>
  local out=$1 url=$2
  if curl -fL --retry 2 --connect-timeout 15 --max-time 600 -o "$out" "$url"; then return 0; fi
  local m
  for m in https://gh-proxy.com/ https://ghproxy.net/; do
    if curl -fL --retry 1 --connect-timeout 15 --max-time 600 -o "$out" "$m$url"; then return 0; fi
  done
  return 1
}

if [ ! -f build-tmp/bun-x86_64.zip ]; then
  fetch build-tmp/bun-x86_64.zip "$BUN_X86_URL"
fi
echo "$MAGPIE_X86_BUN_ZIP_SHA256  build-tmp/bun-x86_64.zip" | sha256sum -c - >/dev/null \
  || { echo "哈希不匹配: build-tmp/bun-x86_64.zip" >&2; exit 2; }

python3 - "$DEST" <<'PY'
from pathlib import Path
import sys, zipfile
dest = Path(sys.argv[1])
with zipfile.ZipFile("build-tmp/bun-x86_64.zip") as archive:
    entries = [item for item in archive.infolist() if not item.is_dir() and item.filename.rsplit("/", 1)[-1] == "bun"]
    if len(entries) != 1:
        raise SystemExit("Expected exactly one Bun executable in the verified x86_64 archive")
    (dest / "libbun_exec.so").write_bytes(archive.read(entries[0]))
PY

chmod 755 "$DEST"/*.so
sha256sum "$DEST"/*.so
echo "x86_64 test runtime ready:"
ls -l "$DEST"
