#!/usr/bin/env python3
"""Static guards, not a substitute for Android runtime network validation."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
workflow = (root / '.github/workflows/build.yml').read_text()
assert 'bash scripts/build-android-release-runtime.sh build-tmp/upstream' in workflow, 'Release workflow must use the checked Android build script'
build = (root / 'scripts/build-android-release-runtime.sh').read_text()
assert 'CGO_ENABLED=1 GOOS=android GOARCH=arm64' in build, 'Android core requires Bionic/cgo DNS'
assert 'aarch64-linux-android29-clang' in build and '-linkmode=external' in build
assert '-tags nogui,androidhost ' in build and '-tags nogui,mobile ' not in build
service=(root / 'app/src/main/java/cc/fplj/magpiemobile/GatewayService.kt').read_text()
assert 'SSL_CERT_DIR' in service
assert '/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts' in service
source=(root / 'patches/mobile/overlay/internal/provider/mobile_discovery.go').read_text()
assert 'InsecureSkipVerify: true' not in source and 'MinVersion: tls.VersionTLS12' in source
assert source.count('InsecureSkipVerify = true') == 1, 'Only the explicit exact-pin verifier may replace system TLS'
pin_factory = source.split('func mobileDiscoveryTransportFor(', 1)[1].split('\nfunc MobileDiscoverModels(', 1)[0]
assert 'if pin != "" {' in pin_factory and 'VerifyConnection = func(cs tls.ConnectionState) error' in pin_factory
assert 'netproxy.CertSHA256(cs.PeerCertificates[0])' in pin_factory and 'if got != pin' in pin_factory
assert 'len(cs.PeerCertificates) == 0' in pin_factory and 'PinMismatchError' in pin_factory
assert 'mobileDiscoveryTransportFor(pin, network)' in source and 'provider_models_tls_pin' in source
print('PASS: Android Bionic DNS, system/APEX trust, ordinary TLS and explicit exact-leaf pin verifier retained')
