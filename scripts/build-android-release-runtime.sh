#!/usr/bin/env bash
# CI only. Cache identity includes fixed upstream, Go version, patches and this script.
set -euo pipefail
cd "$(dirname "$0")/.."
upstream_root="${1:?usage: build-android-release-runtime.sh <patched-upstream-root>}"
mkdir -p build-tmp app/src/main/jniLibs/arm64-v8a app/src/main/assets/licenses/magpie
if [[ "${MAGPIE_CORE_CACHE_HIT:-false}" != true ]]; then
  ndk_root="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
  test -n "$ndk_root"
  compiler="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
  test -x "$compiler"
  (
    cd "$upstream_root"
    export CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$compiler"
    go build -trimpath -buildmode=pie -tags nogui,androidhost \
      -ldflags '-s -w -X main.version=0.1.3781-androidhost.3 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' \
      -o "$OLDPWD/build-tmp/magpie-mobile-android-arm64" .
  )
  sha256sum build-tmp/magpie-mobile-android-arm64 > build-tmp/mobile-core.sha256
  go version -m build-tmp/magpie-mobile-android-arm64 > build-tmp/mobile-core-build-info.txt
fi
sha256sum -c build-tmp/mobile-core.sha256
cp build-tmp/magpie-mobile-android-arm64 app/src/main/jniLibs/arm64-v8a/libmagpie_exec.so
cp "$upstream_root/LICENSE" app/src/main/assets/licenses/magpie/LICENSE.txt
chmod 755 app/src/main/jniLibs/arm64-v8a/libmagpie_exec.so
